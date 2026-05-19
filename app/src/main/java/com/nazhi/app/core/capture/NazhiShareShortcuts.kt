package com.nazhi.app.core.capture

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.nazhi.app.R
import com.nazhi.app.ShareCaptureActivity

object NazhiShareShortcuts {
    const val QUICK_SAVE_SHORTCUT_ID = "quick_save_to_today"
    const val QUICK_SAVE_SHARE_CATEGORY = "com.nazhi.app.category.QUICK_SAVE_SHARE"

    fun publish(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return
        }

        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcutBuilder = ShortcutInfo.Builder(context, QUICK_SAVE_SHORTCUT_ID)
            .setShortLabel("收纳到今日")
            .setLongLabel("快速收纳到纳知今日")
            .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher_foreground))
            .setIntent(
                Intent(context, ShareCaptureActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
            )
            .setCategories(setOf(QUICK_SAVE_SHARE_CATEGORY))
            .setRank(0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            shortcutBuilder.setLongLived(true)
        }

        runCatching {
            manager.setDynamicShortcuts(listOf(shortcutBuilder.build()))
        }
    }

    fun reportQuickSaveUsed(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return
        }

        runCatching {
            context.getSystemService(ShortcutManager::class.java)
                ?.reportShortcutUsed(QUICK_SAVE_SHORTCUT_ID)
        }
    }
}
