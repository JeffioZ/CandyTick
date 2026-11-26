package io.candy.tick.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.candy.tick.data.model.AppIconMode

/**
 * 动态切换桌面图标（跟随系统 / 固定浅色 / 固定深色）。
 * 通过 3 个 activity-alias 切换；先启用目标再禁用其余，确保桌面上始终有唯一有效入口。
 */
object AppIconManager {

    private const val ALIAS_SYSTEM = "io.candy.tick.LauncherSystem"
    private const val ALIAS_LIGHT = "io.candy.tick.LauncherLight"
    private const val ALIAS_DARK = "io.candy.tick.LauncherDark"

    private val modeToAlias = mapOf(
        AppIconMode.SYSTEM to ALIAS_SYSTEM,
        AppIconMode.LIGHT to ALIAS_LIGHT,
        AppIconMode.DARK to ALIAS_DARK,
    )

    fun apply(context: Context, mode: AppIconMode): Boolean = runCatching {
        val pm = context.packageManager
        val target = modeToAlias[mode] ?: ALIAS_SYSTEM
        // 先启用目标 alias，再停用其他 alias，避免极端情况下出现短暂无 LAUNCHER 入口
        pm.setComponentEnabledSetting(
            ComponentName(context, target),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        for ((m, alias) in modeToAlias) {
            if (m == mode) continue
            pm.setComponentEnabledSetting(
                ComponentName(context, alias),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        true
    }.getOrDefault(false)
}
