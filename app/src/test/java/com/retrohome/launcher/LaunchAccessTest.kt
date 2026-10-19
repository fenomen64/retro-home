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

    @Test fun everyInstalledEdenBuildIsSeparateChoice() {
        // Стоят обычный Eden и Eden Legacy Nightly — оба в списке, каждый под своим названием
        val installed = setOf("dev.eden.eden_emulator", "dev.legacy.eden_emulator")
        val labels = mapOf("dev.eden.eden_emulator" to "Eden", "dev.legacy.eden_emulator" to "Eden Legacy Nightly")
        val options = Launchers.available(GameSystem.SWITCH, installed, labels = labels)
        assertEquals(listOf("Eden", "Eden Legacy Nightly"), options.map { it.name })
        // Прежний выбор «eden» по-прежнему означает основную сборку
        assertEquals("eden", options.first().id)
        val legacy = Launchers.choose(GameSystem.SWITCH, installed, "eden@dev.legacy.eden_emulator", labels = labels)!!
        assertEquals("dev.legacy.eden_emulator", Launchers.installedPackage(legacy, installed))
        // Запуск — родным способом Eden, но в выбранной сборке
        val uri = Uri.parse("content://roms/game.nsp")
        val i = legacy.build(LaunchContext("dev.legacy.eden_emulator", legacy.activities.first(), uri, "/roms/game.nsp", GameSystem.SWITCH))
        assertEquals("dev.legacy.eden_emulator", i.component!!.packageName)
        assertEquals("android.nfc.action.TECH_DISCOVERED", i.action)
        // Одна сборка — один пункт, как раньше
        assertEquals(listOf("Eden / Yuzu"), Launchers.available(GameSystem.SWITCH, setOf("dev.legacy.eden_emulator")).map { it.name })
        assertEquals("switch", Emulators.match("dev.legacy.eden_emulator", "Eden Legacy")!!.id)
    }

    @Test fun edenUnderUnknownPackageIsFoundByItsEmulationScreen() {
        val app = RuntimeEnvironment.getApplication()
        val pkg = "org.eden.legacy.nightly"
        assertNull(SpoofedEmulators.detectUnknown(app, pkg))
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(pkg, "org.yuzu.yuzu_emu.activities.EmulationActivity"))
        assertEquals("switch", SpoofedEmulators.detectUnknown(app, pkg)!!.id)
        val sig = SpoofedEmulators.signatureOf(app, pkg)!!
        val r = Launchers.forSignature(sig.recipeId, pkg, "Eden Legacy Nightly")!!
        assertEquals("eden@$pkg", r.id)
        assertEquals(listOf(r.id), Launchers.available(GameSystem.SWITCH, setOf(pkg), listOf(r)).map { it.id })
        // Обычное приложение без экрана эмуляции — не эмулятор
        assertNull(SpoofedEmulators.detectUnknown(app, "com.some.app"))
    }
}
