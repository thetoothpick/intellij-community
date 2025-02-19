// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplaceGetOrSet", "ReplacePutWithAssignment")

package com.intellij.ide

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.IconDeferrer
import com.intellij.ui.JBColor
import com.intellij.ui.icons.loadPng
import com.intellij.ui.icons.toRetinaAwareIcon
import com.intellij.ui.paint.withTxAndClipAligned
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.svg.loadWithSizes
import com.intellij.util.IconUtil
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.io.basicAttributesIfExists
import com.intellij.util.ui.*
import org.imgscalr.Scalr
import org.jetbrains.annotations.SystemIndependent
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.*
import javax.swing.Icon

private fun unscaledProjectIconSize() = Registry.intValue("ide.project.icon.size", 20)

private fun userScaledProjectIconSize() = JBUIScale.scale(unscaledProjectIconSize())

private const val IDEA_DIR = Project.DIRECTORY_STORE_FOLDER

private fun getDotIdeaPath(path: Path): Path {
  if (Files.isDirectory(path) || path.parent == null) {
    return path.resolve(IDEA_DIR)
  }

  val fileName = path.fileName.toString()
  val dotIndex = fileName.lastIndexOf('.')
  val fileNameWithoutExt = if (dotIndex == -1) fileName else fileName.substring(0, dotIndex)
  return path.parent.resolve("$IDEA_DIR/$IDEA_DIR.$fileNameWithoutExt/$IDEA_DIR")
}

private val projectIconCache = ContainerUtil.createSoftValueMap<String, ProjectIcon>()

internal class RecentProjectIconHelper {
  companion object {
    internal fun getDotIdeaPath(path: String): Path? {
      try {
        return getDotIdeaPath(Path.of(path))
      }
      catch (e: InvalidPathException) {
        return null
      }
    }

    internal fun createIcon(file: Path): Icon = ProjectFileIcon(loadIconFile(file), userScaledProjectIconSize())

    fun refreshProjectIcon(path: @SystemIndependent String) {
      projectIconCache.remove(path)
    }

    fun getProjectName(path: @SystemIndependent String, recentProjectManager: RecentProjectsManagerBase): String {
      val displayName = recentProjectManager.getDisplayName(path)
      return when {
        displayName == null -> recentProjectManager.getProjectName(path)
        displayName.contains(',') -> iconTextForCommaSeparatedName(displayName)
        else -> displayName
      }
    }

    @JvmStatic
    fun generateProjectIcon(path: @SystemIndependent String, isProjectValid: Boolean): Icon {
      val name = getProjectName(path, RecentProjectsManagerBase.getInstanceEx())
      var generatedProjectIcon: Icon = AvatarIcon(targetSize = unscaledProjectIconSize(),
                                                  arcRatio = 0.3,
                                                  gradientSeed = name,
                                                  avatarName = name,
                                                  palette = ProjectIconPalette).withIconPreScaled(false)

      if (!isProjectValid) {
        generatedProjectIcon = IconUtil.desaturate(generatedProjectIcon)
      }

      projectIconCache.put(path, ProjectIcon(icon = generatedProjectIcon,
                                             isProjectValid = isProjectValid,
                                             lastUsedProjectIconSize = userScaledProjectIconSize()))

      return generatedProjectIcon
    }

    // Examples:
    // - "First, Second" => "FS"
    // - "First Project, Second Project" => "FS"
    private fun iconTextForCommaSeparatedName(name: String): String {
      return name.split(',')
        .take(2)
        .map { word -> word.firstOrNull { !it.isWhitespace() } ?: "" }
        .joinToString("")
        .uppercase(Locale.getDefault())
    }
  }

  fun getProjectIcon(path: @SystemIndependent String, isProjectValid: Boolean = true): Icon {
    val iconSize = userScaledProjectIconSize()

    if (!RecentProjectsManagerBase.isFileSystemPath(path)) {
      return EmptyIcon.create(iconSize)
    }

    return IconDeferrer.getInstance().defer(EmptyIcon.create(iconSize), Triple(path, isProjectValid, iconSize)) {
      getCustomIcon(path = it.first, isProjectValid = it.second) ?: getGeneratedProjectIcon(path = it.first, isProjectValid = it.second)
    }
  }
}

private fun getCustomIcon(path: @SystemIndependent String, isProjectValid: Boolean): Icon? {
  val file = sequenceOf("icon.svg", "icon.png")
               .mapNotNull { RecentProjectIconHelper.getDotIdeaPath(path)?.resolve(it) }
               .firstOrNull { Files.exists(it) } ?: return null

  val fileInfo = file.basicAttributesIfExists() ?: return null
  val timestamp = fileInfo.lastModifiedTime().toMillis()

  var iconWrapper = projectIconCache.get(path)
  if (iconWrapper != null && isCachedIcon(iconWrapper, isProjectValid, timestamp)) {
    return iconWrapper.icon
  }

  var icon = RecentProjectIconHelper.createIcon(file)
  if (!isProjectValid) {
    icon = IconUtil.desaturate(icon)
  }

  iconWrapper = ProjectIcon(icon = icon,
                            isProjectValid = isProjectValid,
                            lastUsedProjectIconSize = userScaledProjectIconSize(),
                            timestamp = timestamp)
  projectIconCache.put(path, iconWrapper)
  return iconWrapper.icon
}

private fun getGeneratedProjectIcon(path: @SystemIndependent String, isProjectValid: Boolean): Icon {
  val projectIcon = projectIconCache.get(path)
  if (projectIcon != null && isCachedIcon(projectIcon, isProjectValid)) {
    return projectIcon.icon
  }
  return RecentProjectIconHelper.generateProjectIcon(path, isProjectValid)
}

private fun isCachedIcon(icon: ProjectIcon, isProjectValid: Boolean, timestamp: Long? = null): Boolean {
  val isCached = icon.isProjectValid == isProjectValid && icon.lastUsedProjectIconSize == userScaledProjectIconSize()
  return if (timestamp == null) isCached else isCached && icon.timestamp == timestamp
}

private data class ProjectIcon(
  @JvmField val icon: Icon,
  @JvmField val isProjectValid: Boolean,
  @JvmField val lastUsedProjectIconSize: Int,
  @JvmField val timestamp: Long? = null
)

private class ProjectFileIcon(
  private val iconData: IconData,
  private val userScaledSize: Int,
) : JBCachingScalableIcon<ProjectFileIcon>() {

  private var cachedIcon: Icon? = null
  private var cachedIconSysScale: Float? = null
  private var cachedIconPixScale: Float? = null

  override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
    g as Graphics2D
    val sysScale = JBUIScale.sysScale(g)
    val pixScale = JBUI.pixScale(g.deviceConfiguration)
    val cachedIcon = this.cachedIcon
    val delegate: Icon
    if (cachedIcon != null &&
        cachedIconSysScale == sysScale &&
        cachedIconPixScale == pixScale) {
      delegate = cachedIcon
    }
    else {
      delegate = iconData.getScaledIcon(sysScale, pixScale)
      this.cachedIcon = delegate
      cachedIconSysScale = sysScale
      cachedIconPixScale = pixScale
    }
    withTxAndClipAligned(g = g, x = x, y = y, width = delegate.iconWidth, height = delegate.iconHeight) { ag ->
      delegate.paintIcon(c, ag, 0, 0)
    }
  }

  override fun getIconWidth(): Int = userScaledSize

  override fun getIconHeight(): Int = userScaledSize

  override fun copy(): ProjectFileIcon = ProjectFileIcon(iconData, userScaledSize)
}

private fun loadIconFile(file: Path): IconData {
  try {
    if (file.toString().endsWith(".svg", ignoreCase = true)) {
      return SvgIconData(file = file)
    }
    else {
      return PngIconData(Files.newInputStream(file).use { loadPng(it) })
    }
  }
  catch (e: Exception) {
    logger<RecentProjectIconHelper>().debug(e)
    return EmptyIconData
  }
}

private sealed class IconData() {
  abstract fun getScaledIcon(sysScale: Float, pixScale: Float): Icon
}

private class SvgIconData(private val file: Path) : IconData() {
  override fun getScaledIcon(sysScale: Float, pixScale: Float): Icon {
    return JBImageIcon(loadWithSizes(sizes = listOf(unscaledProjectIconSize()), data = Files.readAllBytes(file), scale = pixScale).first())
  }
}

private class PngIconData(private val sourceImage: BufferedImage) : IconData() {
  override fun getScaledIcon(sysScale: Float, pixScale: Float): Icon {
    val targetSize = ((unscaledProjectIconSize() * pixScale) + 0.5f).toInt()
    val image = Scalr.resize(sourceImage, Scalr.Method.ULTRA_QUALITY, targetSize)
    return toRetinaAwareIcon(image = image, sysScale = sysScale)
  }
}

private object EmptyIconData : IconData() {
  override fun getScaledIcon(sysScale: Float, pixScale: Float): Icon {
    return EmptyIcon.create(((unscaledProjectIconSize() * pixScale) + 0.5f).toInt())
  }
}

private object ProjectIconPalette : ColorPalette {
  override val gradients: Array<Pair<Color, Color>>
    get() {
      return arrayOf(
        JBColor.namedColor("RecentProject.Color1.Avatar.Start", JBColor(0xDB3D3C, 0xCE443C))
        to JBColor.namedColor("RecentProject.Color1.Avatar.End", JBColor(0xFF8E42, 0xE77E41)),
        JBColor.namedColor("RecentProject.Color2.Avatar.Start", JBColor(0xF57236, 0xE27237))
        to JBColor.namedColor("RecentProject.Color2.Avatar.End", JBColor(0xFCBA3F, 0xE8A83E)),
        JBColor.namedColor("RecentProject.Color3.Avatar.Start", JBColor(0x2BC8BB, 0x2DBCAD))
        to JBColor.namedColor("RecentProject.Color3.Avatar.End", JBColor(0x36EBAE, 0x35D6A4)),
        JBColor.namedColor("RecentProject.Color4.Avatar.Start", JBColor(0x359AF2, 0x3895E1))
        to JBColor.namedColor("RecentProject.Color4.Avatar.End", JBColor(0x57DBFF, 0x51C5EA)),
        JBColor.namedColor("RecentProject.Color5.Avatar.Start", JBColor(0x8379FB, 0x7B75E8))
        to JBColor.namedColor("RecentProject.Color5.Avatar.End", JBColor(0x85A8FF, 0x7D99EB)),
        JBColor.namedColor("RecentProject.Color6.Avatar.Start", JBColor(0x7E54B5, 0x7854AD))
        to JBColor.namedColor("RecentProject.Color6.Avatar.End", JBColor(0x9486FF, 0x897AE6)),
        JBColor.namedColor("RecentProject.Color7.Avatar.Start", JBColor(0xD63CC8, 0x8F4593))
        to JBColor.namedColor("RecentProject.Color7.Avatar.End", JBColor(0xF582B9, 0xB572E3)),
        JBColor.namedColor("RecentProject.Color8.Avatar.Start", JBColor(0x954294, 0xC840B9))
        to JBColor.namedColor("RecentProject.Color8.Avatar.End", JBColor(0xC87DFF, 0xE074AE)),
        JBColor.namedColor("RecentProject.Color9.Avatar.Start", JBColor(0xE75371, 0xD75370))
        to JBColor.namedColor("RecentProject.Color9.Avatar.End", JBColor(0xFF78B5, 0xE96FA3))
      )
    }
}
