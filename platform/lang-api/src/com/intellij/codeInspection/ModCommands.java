// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection;

import com.intellij.codeInspection.util.IntentionName;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.modcommand.*;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Utility methods to create commands
 *
 * @see ModCommand
 */
@ApiStatus.Experimental
public final class ModCommands {
  /**
   * @return a command that does nothing
   */
  public static @NotNull ModCommand nop() {
    return ModNothing.NOTHING;
  }

  /**
   * @param message error message to display
   * @return a command that displays the specified error message in the editor
   */
  public static @NotNull ModCommand error(@NotNull @NlsContexts.Tooltip String message) {
    return new ModDisplayError(message);
  }
  
  /**
   * @param target element to select
   * @return a command that selects given element in the editor, assuming that it's opened in the editor
   */
  public static @NotNull ModCommand select(@NotNull PsiElement target) {
    PsiFile psiFile = target.getContainingFile();
    TextRange range = target.getTextRange();
    Document document = psiFile.getViewProvider().getDocument();
    if (document instanceof DocumentWindow window) {
      range = window.injectedToHost(range);
      psiFile = InjectedLanguageManager.getInstance(psiFile.getProject()).getTopLevelFile(psiFile);
    }
    VirtualFile file = psiFile.getVirtualFile();
    return new ModNavigate(file, range.getStartOffset(), range.getEndOffset(), range.getStartOffset());
  }

  /**
   * @param context a context of the original action
   * @param updater a function that accepts an updater, so it can query writable copies from it and perform modifications;
   *                also additional editor operation like caret positioning could be performed
   * @return a command that will perform the corresponding update to the original elements and the editor
   */
  public static @NotNull ModCommand psiUpdate(@NotNull ModCommandAction.ActionContext context,
                                              @NotNull Consumer<@NotNull ModPsiUpdater> updater) {
    return ModCommandService.getInstance().psiUpdate(context, updater);
  }

  /**
   * @param orig    PsiElement to update
   * @param updater a function that accepts a non-physical copy of the supplied orig element and performs
   *                PSI write operations in background to modify this copy
   * @return a command that will perform the corresponding update to the original element
   */
  public static <E extends PsiElement> @NotNull ModCommand psiUpdate(@NotNull E orig, @NotNull Consumer<@NotNull E> updater) {
    return psiUpdate(orig, (e, ctx) -> updater.accept(e));
  }
  
  /**
   * @param orig    PsiElement to update
   * @param updater a function that accepts a non-physical copy of the supplied orig element and a context to
   *                perform additional editor operations if necessary; and performs PSI write operations in background
   *                to modify this copy
   * @return a command that will perform the corresponding update to the original element
   */
  public static <E extends PsiElement> @NotNull ModCommand psiUpdate(@NotNull E orig,
                                                                     @NotNull BiConsumer<@NotNull E, @NotNull ModPsiUpdater> updater) {
    return psiUpdate(ModCommandAction.ActionContext.from(null, orig.getContainingFile()), eu -> updater.accept(eu.getWritable(orig), eu));
  }

  /**
   * Create an action that depends on a PSI element in current file
   *
   * @param element element
   * @param title action title
   * @param function factory to create a final command
   * @param range range to select
   * @param <T> type of the element
   * @return an action suitable to store inside {@link ModChooseAction}
   */
  public static @NotNull <T extends PsiElement> ModCommandAction psiBasedStep(
    @NotNull T element,
    @NotNull @IntentionName final String title,
    @NotNull Function<@NotNull T, @NotNull ModCommand> function,
    @NotNull Function<@NotNull T, @NotNull TextRange> range) {
    return new PsiBasedModCommandAction<T>(element) {
      @Override
      protected @NotNull ModCommand perform(@NotNull ActionContext context, @NotNull T element) {
        return function.apply(element);
      }

      @Override
      protected @NotNull Presentation getPresentation(@NotNull ActionContext context, @NotNull T section) {
        return Presentation.of(getFamilyName()).withHighlighting(range.apply(section));
      }

      @Override
      public @NotNull String getFamilyName() {
        return title;
      }
    };
  }

  /**
   * Create an action that depends on a PSI element in current file and performs PSI update
   *
   * @param element element
   * @param title action title
   * @param action action to perform on non-physical element copy
   * @param range function to extract a range to select
   * @param <T> type of the element
   * @return an action suitable to store inside {@link ModChooseAction}
   */
  public static @NotNull <T extends PsiElement> ModCommandAction psiUpdateStep(
    @NotNull T element,
    @NotNull @IntentionName final String title,
    @NotNull BiConsumer<@NotNull T, @NotNull ModPsiUpdater> action,
    @NotNull Function<@NotNull T, @NotNull TextRange> range) {
    return new PsiUpdateModCommandAction<T>(element) {
      @Override
      protected void invoke(@NotNull ActionContext context, @NotNull T element, @NotNull ModPsiUpdater updater) {
        action.accept(element, updater);
      }

      @Override
      protected @NotNull Presentation getPresentation(@NotNull ActionContext context, @NotNull T section) {
        return Presentation.of(getFamilyName()).withHighlighting(range.apply(section));
      }

      @Override
      public @NotNull String getFamilyName() {
        return title;
      }
    };
  }

  /**
   * Create an action that depends on a PSI element in current file and performs PSI update
   *
   * @param element element
   * @param title action title
   * @param action action to perform on non-physical element copy
   * @param <T> type of the element
   * @return an action suitable to store inside {@link ModChooseAction}
   */
  public static @NotNull <T extends PsiElement> ModCommandAction psiUpdateStep(
    @NotNull T element,
    @NotNull @IntentionName final String title,
    @NotNull BiConsumer<@NotNull T, @NotNull ModPsiUpdater> action) {
    return psiUpdateStep(element, title, action, PsiElement::getTextRange);
  }
}
