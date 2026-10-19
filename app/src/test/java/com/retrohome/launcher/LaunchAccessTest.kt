package com.retrohome.launcher

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import com.retrohome.launcher.data.Emulators
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.LaunchContext
import com.retrohome.launcher.data.Launchers
import com.retrohome.launcher.data.Lemuroid
import com.retrohome.launcher.data.SpoofedEmulators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Доступ эмуляторов к папке ROMs и ночные сборки Eden. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LaunchAccessTest {
    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AROMs")
    private val cue = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2FPSX%2FCivilization%20II%20%5BSLUS-00792%5D%2FCivilization%20II.cue")

    /** Как у пользователя: DuckStation открывает .cue, а .bin рядом — только с доступом к папке. */
    @Test fun duckStationGetsFolderAccessForBinNextToCue() {
        val ctx = LaunchContext("com.github.stenzek.duckstation", "com.github.stenzek.duckstation.EmulationActivity", cue, "/x.cue", GameSystem.PS1, tree = tree)
        val i = Launchers.withFolderAccess(Launchers.byId("duckstation")!!.build(ctx), ctx.tree)
        val clip = i.clipData!!
        assertEquals(listOf(cue, tree), (0 until clip.itemCount).map { clip.getItemAt(it).uri })
        assertTrue(i.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION != 0)
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(cue.toString(), i.getStringExtra("bootPath"))
    }

    @Test fun folderIsNotAddedTwiceAndNotToNonFileLaunches() {
        // Explusalpha уже добавляет папку — второй раз не нужно
        val ctx = LaunchContext("com.explusalpha.GbaEmu", "com.imagine.BaseActivity", cue, "/x", GameSystem.GBA, tree = tree)
        val i = Launchers.withFolderAccess(Launchers.byId("gbaemu")!!.build(ctx), tree)
        assertEquals(2, i.clipData!!.itemCount)
        // Lemuroid (ссылка lemuroid://) и RetroArch (обычный путь) файлов по content:// не получают — папка им не нужна
        Lemuroid.links = mapOf(Lemuroid.normalize("Civilization II") to 3)
        try {
            val l = Launchers.withFolderAccess(Launchers.byId("lemuroid")!!.build(LaunchContext("com.swordfish.lemuroid", Lemuroid.GAME_ACTIVITY, cue, "/x", GameSystem.PS1, titles = listOf("Civilization II"), tree = tree)), tree)
            assertNull(l.clipData)
            assertEquals(0, l.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        } finally { Lemuroid.links = emptyMap() }
        val ra = Launchers.withFolderAccess(Launchers.byId("retroarch")!!.build(LaunchContext("com.retroarch.aarch64", "com.retroarch.browser.retroactivity.RetroActivityFuture", cue, "/x.cue", GameSystem.PS1, tree = tree)), tree)
        assertNull(ra.clipData)
    }

    @Test fun edenNightlyBuildsAreRecognized() {
        // Ночная сборка «рядом с основной» — любой пакет dev.eden.*
        assertEquals("switch", Emulators.match("dev.eden.eden_nightly_coexist", "Eden Nightly")!!.id)
        assertEquals(listOf("eden"), Launchers.available(GameSystem.SWITCH, setOf("dev.eden.eden_nightly_coexist")).map { it.id })
        assertTrue(Launchers.knows("dev.eden.something"))
        // «Оптимизированная» — под пакетом Genshin Impact
        assertEquals(listOf("eden"), Launchers.available(GameSystem.SWITCH, setOf(SpoofedEmulators.GENSHIN)).map { it.id })
    }

    @Test fun genshinIsEdenOnlyIfItHasEdenInside() {
        val app = RuntimeEnvironment.getApplication()
        assertNull("настоящая игра Genshin — не эмулятор", SpoofedEmulators.detect(app, SpoofedEmulators.GENSHIN))
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(SpoofedEmulators.GENSHIN, "org.yuzu.yuzu_emu.activities.EmulationActivity"))
        assertEquals("switch", SpoofedEmulators.detect(app, SpoofedEmulators.GENSHIN)!!.id)
        assertNull(SpoofedEmulators.detect(app, "com.other.game"))
    }
}
