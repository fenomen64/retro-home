package com.retrohome.launcher.data

import android.content.Context
import android.content.pm.LauncherApps
import android.net.Uri

/**
 * Lemuroid не принимает файл игры от других приложений: снаружи игру можно открыть только ссылкой
 * lemuroid://<пакет>/play-game/id/<номер> с номером игры в его собственной библиотеке.
 * Номер приходит в ярлыке, который Lemuroid создаёт по команде «Создать ярлык» (id ярлыка — «game_<номер>»,
 * подпись — название игры). Главный экран принимает такие ярлыки и запоминает: название → номер.
 */
object Lemuroid {
    const val PACKAGE_PREFIX = "com.swordfish.lemuroid"
    const val GAME_ACTIVITY = "com.swordfish.lemuroid.app.shared.game.ExternalGameLauncherActivity"
    const val MAIN_ACTIVITY = "com.swordfish.lemuroid.app.mobile.feature.main.MainActivity"
    private const val SHORTCUT_PREFIX = "game_"
    private const val PREFS = "lemuroid_links"

    val systems: List<GameSystem> = listOf(
        GameSystem.NES, GameSystem.SNES, GameSystem.N64, GameSystem.GB, GameSystem.GBC, GameSystem.GBA,
        GameSystem.NDS, GameSystem.N3DS, GameSystem.PS1, GameSystem.PSP, GameSystem.MD, GameSystem.SMS,
        GameSystem.GG, GameSystem.ARCADE, GameSystem.PCE,
    )

    /** Номер игры в библиотеке Lemuroid по сокращённому названию (см. [normalize]). */
    @Volatile var links: Map<String, Int> = emptyMap()

    /**
     * Название для сравнения: без пометок в скобках (регион, версия), без артикля «The»,
     * без знаков и регистра. «Legend of Zelda, The - The Minish Cap (USA)» → «legendofzeldaminishcap».
     */
    fun normalize(title: String): String {
        // «Pokémon» и «Pokemon» — одно и то же
        val s = java.text.Normalizer.normalize(title.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""\([^)]*\)|\[[^\]]*]"""), " ")
            .replace("&", " and ")
        return s.split(Regex("""[^\p{L}\p{Nd}]+""")).filter { it.isNotEmpty() && it != "the" }.joinToString("")
    }

    /** Номер игры из ярлыка Lemuroid; null — это не ярлык игры Lemuroid. */
    fun gameIdOf(pkg: String, shortcutId: String): Int? =
        if (pkg.startsWith(PACKAGE_PREFIX) && shortcutId.startsWith(SHORTCUT_PREFIX)) shortcutId.removePrefix(SHORTCUT_PREFIX).toIntOrNull() else null

    /** Номер игры по любому из её названий (своё, из имени файла, исправленное вручную). */
    fun idFor(titles: List<String>): Int? {
        val map = links
        if (map.isEmpty()) return null
        return titles.asSequence().map(::normalize).filter { it.isNotEmpty() }.firstNotNullOfOrNull { map[it] }
    }

    fun gameUri(pkg: String, id: Int): Uri = Uri.parse("lemuroid://$pkg/play-game/id/$id")

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        links = p.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
    }

    /** Запомнить игру из принятого ярлыка. */
    fun remember(context: Context, label: String, id: Int) {
        val key = normalize(label).takeIf { it.isNotEmpty() } ?: return
        links = links + (key to id)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(key, id).apply()
    }

    /**
     * Подтянуть ярлыки Lemuroid, закреплённые на этом главном экране (например, после переустановки
     * лаунчера). Работает, только когда Retro Home — главный экран.
     */
    fun syncPinned(context: Context, packages: Collection<String>) {
        val la = context.getSystemService(LauncherApps::class.java) ?: return
        runCatching {
            if (!la.hasShortcutHostPermission()) return
            for (pkg in packages.filter { it.startsWith(PACKAGE_PREFIX) }) {
                val q = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                val list = la.getShortcuts(q, android.os.Process.myUserHandle()) ?: continue
                for (sc in list) {
                    val id = gameIdOf(pkg, sc.id) ?: continue
                    val label = (sc.shortLabel ?: sc.longLabel)?.toString() ?: continue
                    if (links[normalize(label)] != id) remember(context, label, id)
                }
            }
        }
    }

    /** Может ли лаунчер принимать ярлыки (он сейчас главный экран). */
    fun canReceiveShortcuts(context: Context): Boolean =
        runCatching { context.getSystemService(LauncherApps::class.java)?.hasShortcutHostPermission() == true }.getOrDefault(false)
}
