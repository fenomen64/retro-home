package com.retrohome.launcher

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import com.retrohome.launcher.data.Emulators
import com.retrohome.launcher.data.FileOpeners
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.LaunchContext
import com.retrohome.launcher.data.Launchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** ARMSX2 и эмуляторы, которых нет в списке известных: консоль по названию, запуск через «открыть файл». */
class NewEmulatorsTest {
    @Test fun armsx2IsRecognized() {
        assertEquals("armsx2", Emulators.match("com.armsx2", "ARMSX2")!!.id)
        // Ночная сборка ставится отдельным пакетом
        assertEquals("armsx2", Emulators.match("com.armsx2.nightly", "ARMSX2 Nightly")!!.id)
        assertEquals(listOf(GameSystem.PS2), Emulators.match("com.armsx2", "ARMSX2")!!.systems)
    }

    @Test fun armsx2IsOfferedForPs2() {
        assertEquals(listOf("armsx2"), Launchers.available(GameSystem.PS2, setOf("com.armsx2")).map { it.id })
        assertEquals("com.armsx2.nightly", Launchers.installedPackage(Launchers.byId("armsx2")!!, setOf("com.armsx2.nightly")))
        // Рядом с NetherSX2 по умолчанию — ARMSX2 (он развивается), но выбранный вручную остаётся
        val both = setOf("com.armsx2", "xyz.aethersx2.android")
        assertEquals("armsx2", Launchers.choose(GameSystem.PS2, both, null)!!.id)
        assertEquals("sx2", Launchers.choose(GameSystem.PS2, both, "sx2")!!.id)
    }

    @Test fun systemsGuessedFromName() {
        fun g(pkg: String, label: String) = Emulators.guessSystems(pkg, label)
        assertEquals(listOf(GameSystem.PS2), g("org.example.mobile", "PCSX2 Mobile"))
        assertEquals(listOf(GameSystem.PS2), g("com.dev.ps2emu", "Some Emu"))
        assertEquals(listOf(GameSystem.WIIU), g("com.foo.wiiuemu", "Wii U Emulator"))
        assertEquals(listOf(GameSystem.GBA), g("x.y", "Super Game Boy Advance Player"))
        assertEquals(listOf(GameSystem.N3DS), g("io.github.newemu", "New 3DS Emulator"))
        assertEquals(listOf(GameSystem.DC), g("org.reicast", "Dreamcast Emu"))
        assertTrue(g("com.android.chrome", "Chrome").isEmpty())
    }

    @Test fun guessNeedsFileOpening() {
        // Похожее название, но приложение не открывает файлы — это не эмулятор (игра или утилита)
        assertNull(Emulators.guess("com.retro.nesgames", "Retro NES Collection", opensFiles = false))
        assertNull(Emulators.guess("com.google.android.accessibility.switchaccess", "Switch Access", opensFiles = false))
        val def = Emulators.guess("org.example.mobile", "PCSX2 Mobile", opensFiles = true)!!
        assertEquals(listOf(GameSystem.PS2), def.systems)
        assertTrue(def.id.startsWith(Emulators.AUTO_PREFIX))
        // Открывает файлы, но консоль по названию не понять — не угадываем
        assertNull(Emulators.guess("com.files.manager", "Files", opensFiles = true))
    }

    @Test fun knownRecipes() {
        assertTrue(Launchers.knows("com.armsx2"))
        assertTrue(Launchers.knows("xyz.aethersx2.android"))
        assertFalse(Launchers.knows("com.epsxe.ePSXe"))
        assertFalse(Launchers.knows("org.example.mobile"))
    }

    @Test fun genericRecipeIsOfferedAfterKnownOnes() {
        val auto = Launchers.generic("org.example.mobile", "PCSX2 Mobile", setOf(GameSystem.PS2), "org.example.Main", withType = false)
        val installed = setOf("org.example.mobile", "xyz.aethersx2.android")
        assertEquals(listOf("sx2", "auto:org.example.mobile"), Launchers.available(GameSystem.PS2, installed, listOf(auto)).map { it.id })
        assertEquals("auto:org.example.mobile", Launchers.choose(GameSystem.PS2, setOf("org.example.mobile"), null, listOf(auto))!!.id)
    }
}

/** Как собирается команда запуска (нужны классы Android). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EmulatorIntentTest {
    private val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2Fps2%2FOkami.iso")

    @Test fun armsx2GetsFileLink() {
        val r = Launchers.byId("armsx2")!!
        val i = r.build(LaunchContext("com.armsx2", "com.armsx2.Main", uri, "/storage/emulated/0/ROMs/ps2/Okami.iso", GameSystem.PS2))
        assertEquals("com.armsx2", i.component!!.packageName)
        assertEquals("com.armsx2.Main", i.component!!.className)
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(uri, i.data)
        assertEquals(uri, i.clipData!!.getItemAt(0).uri)
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun genericRecipeMatchesHowAppDeclaredIt() {
        val noType = Launchers.generic("org.example.mobile", "PCSX2 Mobile", setOf(GameSystem.PS2), "org.example.Main", withType = false)
            .build(LaunchContext("org.example.mobile", "org.example.Main", uri, "/x", GameSystem.PS2))
        assertEquals(uri, noType.data)
        assertNull(noType.type)
        val typed = Launchers.generic("org.example.mobile", "PCSX2 Mobile", setOf(GameSystem.PS2), "org.example.Main", withType = true)
            .build(LaunchContext("org.example.mobile", "org.example.Main", uri, "/x", GameSystem.PS2))
        assertEquals("application/octet-stream", typed.type)
        assertEquals("org.example.Main", typed.component!!.className)
    }

    @Test fun fileOpenersFoundThroughPackageManager() {
        val app = RuntimeEnvironment.getApplication()
        val spm = shadowOf(app.packageManager)
        // Новый эмулятор объявил: открываю content:// без типа файла (как ARMSX2)
        val probe = Intent(Intent.ACTION_VIEW).setData(Uri.parse("content://com.retrohome.probe/roms/game.bin"))
        val ri = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "org.example.mobile"; name = "org.example.Main" } }
        spm.addResolveInfoForIntent(probe, ri)
        assertTrue("org.example.mobile" in FileOpeners.packages(app))
        val withPkg = Intent(probe).setPackage("org.example.mobile")
        spm.addResolveInfoForIntent(withPkg, ri)
        val found = FileOpeners.activityOf(app, "org.example.mobile")
        assertNotNull(found)
        assertEquals("org.example.Main" to false, found)
    }
}
