package com.retrohome.launcher

import android.app.Activity
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast
import com.retrohome.launcher.data.Lemuroid
import com.retrohome.launcher.i18n.stringsFor

/**
 * Приложение просит добавить ярлык на главный экран. Принимаем ярлыки игр Lemuroid:
 * в них номер игры в его библиотеке — по нему игра потом запускается напрямую.
 */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = stringsFor((application as LauncherApp).controller.store.data.value.settings.lang)
        val req = runCatching { getSystemService(LauncherApps::class.java)?.getPinItemRequest(intent) }.getOrNull()
        val info = req?.takeIf { it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT }?.shortcutInfo
        val id = info?.let { Lemuroid.gameIdOf(it.`package`, it.id) }
        val label = (info?.shortLabel ?: info?.longLabel)?.toString().orEmpty()
        val accepted = req != null && id != null && label.isNotBlank() && runCatching { req.isValid && req.accept() }.getOrDefault(false)
        if (accepted) {
            Lemuroid.remember(this, label, id!!)
            Toast.makeText(this, s.ext.emu.lemuroidLinked.format(label), Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, s.ext.emu.shortcutUnsupported, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
