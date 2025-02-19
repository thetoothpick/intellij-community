// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.intentions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameSuggestionProvider
import org.jetbrains.kotlin.idea.base.fe10.codeInsight.newDeclaration.Fe10KotlinNameSuggester
import org.jetbrains.kotlin.idea.base.fe10.codeInsight.newDeclaration.Fe10KotlinNewDeclarationNameValidator
import org.jetbrains.kotlin.idea.base.projectStructure.languageVersionSettings
import org.jetbrains.kotlin.idea.base.psi.copied
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.caches.resolve.safeAnalyzeNonSourceRootCode
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.IntentionBasedInspection
import org.jetbrains.kotlin.idea.codeinsight.api.classic.intentions.SelfTargetingRangeIntention
import org.jetbrains.kotlin.idea.core.isVisible
import org.jetbrains.kotlin.idea.util.application.runWriteActionIfPhysical
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.addRemoveModifier.setModifierList
import org.jetbrains.kotlin.psi.psiUtil.PsiChildRange
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedExpressionForReceiver
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns

class DestructureInspection : IntentionBasedInspection<KtDeclaration>(
    DestructureIntention::class,
    { element, _ ->
        val usagesToRemove = collectUsagesToRemove(element)?.data
        if (element is KtParameter) {
            usagesToRemove != null &&
                    (usagesToRemove.any { it.declarationToDrop is KtDestructuringDeclaration } ||
                            usagesToRemove.filter { it.usagesToReplace.isNotEmpty() }.size > usagesToRemove.size / 2)
        } else {
            usagesToRemove?.any { it.declarationToDrop is KtDestructuringDeclaration } ?: false
        }
    }
)

class DestructureIntention : SelfTargetingRangeIntention<KtDeclaration>(
    KtDeclaration::class.java,
    KotlinBundle.lazyMessage("use.destructuring.declaration")
) {
    override fun applyTo(element: KtDeclaration, editor: Editor?) {
        val (usagesToRemove, removeSelectorInLoopRange) = collectUsagesToRemove(element) ?: return
        val psiFactory = KtPsiFactory(element.project)
        val parent = element.parent
        val (container, anchor) = if (parent is KtParameterList) parent.parent to null else parent to element
        val validator = Fe10KotlinNewDeclarationNameValidator(
            container = container, anchor = anchor, target = KotlinNameSuggestionProvider.ValidatorTarget.VARIABLE,
            excludedDeclarations = usagesToRemove.map {
                (it.declarationToDrop as? KtDestructuringDeclaration)?.entries ?: listOfNotNull(it.declarationToDrop)
            }.flatten()
        )

        val names = ArrayList<String>()
        val underscoreSupported = element.languageVersionSettings.supportsFeature(LanguageFeature.SingleUnderscoreForParameterName)
        // For all unused we generate normal names, not underscores
        val allUnused = usagesToRemove.all { (_, usagesToReplace, variableToDrop) ->
            usagesToReplace.isEmpty() && variableToDrop == null
        }

        usagesToRemove.forEach { (descriptor, usagesToReplace, variableToDrop, name) ->
            val suggestedName =
                if (usagesToReplace.isEmpty() && variableToDrop == null && underscoreSupported && !allUnused) {
                    "_"
                } else {
                    Fe10KotlinNameSuggester.suggestNameByName(name ?: descriptor.name.asString(), validator)
                }

            runWriteActionIfPhysical(element) {
                variableToDrop?.delete()
                usagesToReplace.forEach {
                    it.replace(psiFactory.createExpression(suggestedName))
                }
            }
            names.add(suggestedName)
        }

        val joinedNames = names.joinToString()
        when (element) {
            is KtParameter -> {
                val loopRange = (element.parent as? KtForExpression)?.loopRange
                runWriteActionIfPhysical(element) {
                    val type = element.typeReference?.let { ": ${it.text}" } ?: ""
                    element.replace(psiFactory.createDestructuringParameter("($joinedNames)$type"))
                    if (removeSelectorInLoopRange && loopRange is KtDotQualifiedExpression) {
                        loopRange.replace(loopRange.receiverExpression)
                    }
                }
            }

            is KtFunctionLiteral -> {
                val lambda = element.parent as KtLambdaExpression
                SpecifyExplicitLambdaSignatureIntention().applyTo(lambda, editor)
                runWriteActionIfPhysical(element) {
                    lambda.functionLiteral.valueParameters.singleOrNull()?.replace(
                        psiFactory.createDestructuringParameter("($joinedNames)")
                    )
                }
            }

            is KtVariableDeclaration -> {
                val rangeAfterEq = PsiChildRange(element.initializer, element.lastChild)
                val modifierList = element.modifierList?.copied()
                runWriteActionIfPhysical(element) {
                    val result = element.replace(
                        psiFactory.createDestructuringDeclarationByPattern(
                            "val ($joinedNames) = $0", rangeAfterEq
                        )
                    ) as KtModifierListOwner

                    if (modifierList != null) {
                        result.setModifierList(modifierList)
                    }
                }
            }
        }
    }

    override fun applicabilityRange(element: KtDeclaration): TextRange? {
        if (!element.isSuitableDeclaration()) return null

        val usagesToRemove = collectUsagesToRemove(element)?.data ?: return null
        if (usagesToRemove.isEmpty()) return null

        return when (element) {
            is KtFunctionLiteral -> element.lBrace.textRange
            is KtNamedDeclaration -> element.nameIdentifier?.textRange
            else -> null
        }
    }
}

internal fun KtDeclaration.isSuitableDeclaration() = getUsageScopeElement() != null

private fun KtDeclaration.getUsageScopeElement(): PsiElement? {
    val lambdaSupported = languageVersionSettings.supportsFeature(LanguageFeature.DestructuringLambdaParameters)
    return when (this) {
        is KtParameter -> {
            val parent = parent
            when {
                parent is KtForExpression -> parent
                parent.parent is KtFunctionLiteral -> if (lambdaSupported) parent.parent else null
                else -> null
            }
        }
        is KtProperty -> parent.takeIf { isLocal }
        is KtFunctionLiteral -> if (!hasParameterSpecification() && lambdaSupported) this else null
        else -> null
    }
}

internal data class UsagesToRemove(val data: List<UsageData>, val removeSelectorInLoopRange: Boolean)

internal fun collectUsagesToRemove(declaration: KtDeclaration): UsagesToRemove? {
    val context = declaration.safeAnalyzeNonSourceRootCode()

    val variableDescriptor = when (declaration) {
        is KtParameter -> context.get(BindingContext.VALUE_PARAMETER, declaration)
        is KtFunctionLiteral -> context.get(BindingContext.FUNCTION, declaration)?.valueParameters?.singleOrNull()
        is KtVariableDeclaration -> context.get(BindingContext.VARIABLE, declaration)
        else -> null
    } ?: return null

    val variableType = variableDescriptor.type
    if (variableType.isMarkedNullable) return null
    val classDescriptor = variableType.constructor.declarationDescriptor as? ClassDescriptor ?: return null

    val mapEntryClassDescriptor = classDescriptor.builtIns.mapEntry

    val usageScopeElement = declaration.getUsageScopeElement() ?: return null
    val nameToSearch = when (declaration) {
        is KtParameter -> declaration.nameAsName
        is KtVariableDeclaration -> declaration.nameAsName
        else -> Name.identifier("it")
    } ?: return null

    // Note: list should contain properties in order to create destructuring declaration
    val usagesToRemove = mutableListOf<UsageData>()
    var noBadUsages = true
    var removeSelectorInLoopRange = false
    when {
        DescriptorUtils.isSubclass(classDescriptor, mapEntryClassDescriptor) -> {
            val forLoop = declaration.parent as? KtForExpression
            if (forLoop != null) {
                val loopRangeDescriptor = forLoop.loopRange.getResolvedCall(context)?.resultingDescriptor
                if (loopRangeDescriptor != null) {
                    val loopRangeDescriptorOwner = loopRangeDescriptor.containingDeclaration
                    val mapClassDescriptor = classDescriptor.builtIns.map
                    if (loopRangeDescriptorOwner is ClassDescriptor &&
                        DescriptorUtils.isSubclass(loopRangeDescriptorOwner, mapClassDescriptor)
                    ) {
                        removeSelectorInLoopRange = loopRangeDescriptor.name.asString().let { it == "entries" || it == "entrySet" }
                    }
                }
            }

            listOf("key", "value").mapTo(usagesToRemove) {
                UsageData(
                    descriptor = mapEntryClassDescriptor.unsubstitutedMemberScope.getContributedVariables(
                        Name.identifier(it), NoLookupLocation.FROM_BUILTINS
                    ).single()
                )
            }

            usageScopeElement.iterateOverMapEntryPropertiesUsages(
                context,
                nameToSearch,
                variableDescriptor,
                { index, usageData -> noBadUsages = usagesToRemove[index].add(usageData, index) && noBadUsages },
                { noBadUsages = false }
            )
        }
        classDescriptor.isData -> {

            val valueParameters = classDescriptor.unsubstitutedPrimaryConstructor?.valueParameters ?: return null
            valueParameters.mapTo(usagesToRemove) { UsageData(descriptor = it) }

            val constructorParameterNameMap = mutableMapOf<Name, ValueParameterDescriptor>()
            valueParameters.forEach { constructorParameterNameMap[it.name] = it }

            usageScopeElement.iterateOverDataClassPropertiesUsagesWithIndex(
                context,
                nameToSearch,
                variableDescriptor,
                constructorParameterNameMap,
                { index, usageData -> noBadUsages = usagesToRemove[index].add(usageData, index) && noBadUsages },
                { noBadUsages = false }
            )
        }
        else -> return null
    }
    if (!noBadUsages) return null

    val droppedLastUnused = usagesToRemove.dropLastWhile { it.usagesToReplace.isEmpty() && it.declarationToDrop == null }
    return if (droppedLastUnused.isEmpty()) {
        UsagesToRemove(usagesToRemove, removeSelectorInLoopRange)
    } else {
        UsagesToRemove(droppedLastUnused, removeSelectorInLoopRange)
    }
}

private fun PsiElement.iterateOverMapEntryPropertiesUsages(
    context: BindingContext,
    parameterName: Name,
    variableDescriptor: VariableDescriptor,
    process: (Int, SingleUsageData) -> Unit,
    cancel: () -> Unit
) {
    anyDescendantOfType<KtNameReferenceExpression> {
        when {
            it.getReferencedNameAsName() != parameterName -> false
            it.getResolvedCall(context)?.resultingDescriptor != variableDescriptor -> false
            else -> {
                val applicableUsage = getDataIfUsageIsApplicable(it, context)
                if (applicableUsage != null) {
                    val usageDescriptor = applicableUsage.descriptor
                    if (usageDescriptor == null) {
                        process(0, applicableUsage)
                        process(1, applicableUsage)
                        return@anyDescendantOfType false
                    }
                    when (usageDescriptor.name.asString()) {
                        "key", "getKey" -> {
                            process(0, applicableUsage)
                            return@anyDescendantOfType false
                        }
                        "value", "getValue" -> {
                            process(1, applicableUsage)
                            return@anyDescendantOfType false
                        }
                    }
                }
                cancel()
                true
            }
        }
    }
}

private fun PsiElement.iterateOverDataClassPropertiesUsagesWithIndex(
    context: BindingContext,
    parameterName: Name,
    variableDescriptor: VariableDescriptor,
    constructorParameterNameMap: Map<Name, ValueParameterDescriptor>,
    process: (Int, SingleUsageData) -> Unit,
    cancel: () -> Unit
) {
    anyDescendantOfType<KtNameReferenceExpression> {
        when {
            it.getReferencedNameAsName() != parameterName -> false
            it.getResolvedCall(context)?.resultingDescriptor != variableDescriptor -> false
            else -> {
                val applicableUsage = getDataIfUsageIsApplicable(it, context)
                if (applicableUsage != null) {
                    val usageDescriptor = applicableUsage.descriptor
                    if (usageDescriptor == null) {
                        for (parameter in constructorParameterNameMap.values) {
                            process(parameter.index, applicableUsage)
                        }
                        return@anyDescendantOfType false
                    }
                    val parameter = constructorParameterNameMap[usageDescriptor.name]
                    if (parameter != null) {
                        process(parameter.index, applicableUsage)
                        return@anyDescendantOfType false
                    }
                }

                cancel()
                true
            }
        }
    }
}

private fun getDataIfUsageIsApplicable(dataClassUsage: KtReferenceExpression, context: BindingContext): SingleUsageData? {
    val destructuringDecl = dataClassUsage.parent as? KtDestructuringDeclaration
    if (destructuringDecl != null && destructuringDecl.initializer == dataClassUsage) {
        return SingleUsageData(descriptor = null, usageToReplace = null, declarationToDrop = destructuringDecl)
    }
    val qualifiedExpression = dataClassUsage.getQualifiedExpressionForReceiver() ?: return null
    val parent = qualifiedExpression.parent
    when (parent) {
        is KtBinaryExpression -> {
            if (parent.operationToken in KtTokens.ALL_ASSIGNMENTS && parent.left == qualifiedExpression) return null
        }
        is KtUnaryExpression -> {
            if (parent.operationToken == KtTokens.PLUSPLUS || parent.operationToken == KtTokens.MINUSMINUS) return null
        }
    }

    val property = parent as? KtProperty // val x = d.y
    if (property != null && property.isVar) return null

    val descriptor = qualifiedExpression.getResolvedCall(context)?.resultingDescriptor ?: return null
    if (!descriptor.isVisible(
            dataClassUsage, qualifiedExpression.receiverExpression,
            context, dataClassUsage.containingKtFile.getResolutionFacade()
        )
    ) {
        return null
    }
    return SingleUsageData(descriptor = descriptor, usageToReplace = qualifiedExpression, declarationToDrop = property)
}

internal data class SingleUsageData(
    val descriptor: CallableDescriptor?,
    val usageToReplace: KtExpression?,
    val declarationToDrop: KtDeclaration?
)

internal data class UsageData(
    val descriptor: CallableDescriptor,
    val usagesToReplace: MutableList<KtExpression> = mutableListOf(),
    var declarationToDrop: KtDeclaration? = null,
    var name: String? = null
) {
    // Returns true if data is successfully added, false otherwise
    fun add(newData: SingleUsageData, componentIndex: Int): Boolean {
        if (newData.declarationToDrop is KtDestructuringDeclaration) {
            val destructuringEntries = newData.declarationToDrop.entries
            if (componentIndex < destructuringEntries.size) {
                if (declarationToDrop != null) return false
                name = destructuringEntries[componentIndex].name ?: return false
                declarationToDrop = newData.declarationToDrop
            }
        } else {
            name = name ?: newData.declarationToDrop?.name
            declarationToDrop = declarationToDrop ?: newData.declarationToDrop
        }

        newData.usageToReplace?.let { usagesToReplace.add(it) }
        return true
    }
}
