package com.retrohome.launcher.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.staticCompositionLocalOf
import com.retrohome.launcher.R

/** События интерфейса, у которых есть звук. */
enum class Sfx(val file: String) {
    MOVE("move"), TAB("tab"), SELECT("select"), LAUNCH("launch"), BACK("back"), MENU("menu"), FAVORITE("favorite")
}

/** Наборы звуков. id совпадает с частью имени файла в res/raw. */
enum class SoundPack(val id: String) { OFF("off"), SOFT("soft"), RETRO("retro"), CRYSTAL("crystal"), CLICK("click") }

/** Встроенные треки и «своя мелодия». */
enum class MusicTrack(val id: String, val res: Int?) {
    OFF("off", null),
    AMBIENT("ambient", R.raw.music_ambient),
    CHIPTUNE("chiptune", R.raw.music_chiptune),
    LOFI("lofi", R.raw.music_lofi),
    CUSTOM("custom", null),
}

fun soundPackOf(id: String) = SoundPack.entries.firstOrNull { it.id == id } ?: SoundPack.SOFT
fun musicTrackOf(id: String) = MusicTrack.entries.firstOrNull { it.id == id } ?: MusicTrack.OFF

interface SfxPlayer {
    fun play(s: Sfx)
}

val LocalSfx = staticCompositionLocalOf<SfxPlayer> { object : SfxPlayer { override fun play(s: Sfx) {} } }

/** Какой мотор будет вибрировать (для подсказки в настройках). */
val LocalHapticTarget = staticCompositionLocalOf<() -> HapticTarget> { { HapticTarget.NONE } }

/** Звуки интерфейса: загружаются один раз на набор и играют без задержки. */
class SoundPlayer(private val context: Context) : SfxPlayer {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()
    private var pack = SoundPack.OFF
    private val ids = HashMap<Sfx, Int>()
    private var volume = 0.7f
    private var lastMove = 0L

    fun configure(newPack: SoundPack, newVolume: Int) {
        volume = (newVolume / 100f).coerceIn(0f, 1f)
        if (newPack == pack) return
        ids.values.forEach { pool.unload(it) }
        ids.clear()
        pack = newPack
        if (newPack == SoundPack.OFF) return
        for (s in Sfx.entries) {
            val res = context.resources.getIdentifier("sfx_${newPack.id}_${s.file}", "raw", context.packageName)
            if (res != 0) ids[s] = pool.load(context, res, 1)
        }
    }

    override fun play(s: Sfx) {
        if (pack == SoundPack.OFF || volume <= 0f) return
        // При зажатой крестовине щелчки не должны сливаться в треск
        if (s == Sfx.MOVE) {
            val now = System.currentTimeMillis()
            if (now - lastMove < 45) return
            lastMove = now
        }
        val id = ids[s] ?: return
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    fun release() = pool.release()
}

/**
 * Фоновая музыка. Играет, только когда одновременно: лаунчер на экране,
 * включён консольный режим и выбран трек. Включается и выключается плавно.
 */
class MusicPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var loadedKey: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var wanted = false
    private var track = MusicTrack.OFF
    private var custom: Uri? = null
    private var target = 0.4f
    private var current = 0f

    fun setResumed(r: Boolean) { resumed = r; apply() }

    fun configure(consoleMode: Boolean, t: MusicTrack, customUri: String?, volume: Int) {
        wanted = consoleMode
        track = t
        custom = customUri?.let(Uri::parse)
        target = (volume / 100f).coerceIn(0f, 1f)
        apply()
    }

    private fun shouldPlay() = resumed && wanted && track != MusicTrack.OFF && target > 0f &&
        (track != MusicTrack.CUSTOM || custom != null)

    private fun apply() {
        if (!shouldPlay()) {
            fadeTo(0f) { player?.takeIf { runCatching { it.isPlaying }.getOrDefault(false) }?.pause() }
            return
        }
        val key = if (track == MusicTrack.CUSTOM) "custom:$custom" else track.id
        if (key != loadedKey) {
            releasePlayer()
            player = runCatching {
                if (track == MusicTrack.CUSTOM) {
                    MediaPlayer().apply {
                        setAudioAttributes(attrs())
                        setDataSource(context, custom!!)
                        prepare()
                    }
                } else MediaPlayer.create(context, track.res!!, attrs(), 0)
            }.getOrNull()
            loadedKey = key
            current = 0f
            player?.isLooping = true
            player?.setVolume(0f, 0f)
        }
        val p = player ?: return
        if (!runCatching { p.isPlaying }.getOrDefault(false)) runCatching { p.start() }
        fadeTo(target) {}
    }

    private fun attrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** Плавное изменение громкости шагами по 50 мс. */
    private fun fadeTo(to: Float, then: () -> Unit) {
        handler.removeCallbacksAndMessages(null)
        val step = if (to > current) 0.04f else -0.08f
        val tick = object : Runnable {
            override fun run() {
                val p = player ?: return then()
                current = if (step > 0) minOf(to, current + step) else maxOf(to, current + step)
                runCatching { p.setVolume(current, current) }
                if (current != to) handler.postDelayed(this, 50) else then()
            }
        }
        handler.post(tick)
    }

    private fun releasePlayer() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.release() }
        player = null
        loadedKey = null
    }

    fun release() = releasePlayer()
}

/** Сила вибрации при навигации. */
enum class HapticLevel(val id: String, val amplitude: Int, val scale: Float) {
    OFF("off", 0, 0f), LIGHT("light", 130, 0.8f), MEDIUM("medium", 200, 1f), STRONG("strong", 255, 1.35f)
}

fun hapticLevelOf(id: String) = HapticLevel.entries.firstOrNull { it.id == id } ?: HapticLevel.LIGHT

/** Чем вибрировать: мотором встроенного геймпада, системным мотором или нечем. */
enum class HapticTarget { GAMEPAD, SYSTEM, NONE }

/**
 * Короткая вибрация на те же события, что и звуки: шаг — едва заметно, запуск — сильнее.
 *
 * У игровых приставок мотор часто принадлежит встроенному геймпаду (как вибрация в играх),
 * а системного «телефонного» мотора нет или он другой. Поэтому сначала ищем мотор у геймпада,
 * с которого пришло последнее нажатие, затем у любого подключённого геймпада, и только потом — системный.
 */
class HapticPlayer(private val context: Context) : SfxPlayer {
    private val system: android.os.Vibrator? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.os.Vibrator::class.java)
        }
    }.getOrNull()?.takeIf { it.hasVibrator() }

    var level = HapticLevel.LIGHT
    /** Устройство, с которого пришло последнее нажатие кнопки. */
    @Volatile var lastDeviceId = -1
    private var lastMove = 0L

    /**
     * Длительность вибрации в мс для события (до учёта силы). Мотору нужно 20–30 мс, чтобы
     * раскрутиться: более короткий импульс при одиночном нажатии почти не чувствуется.
     */
    fun durationFor(s: Sfx): Long = when (s) {
        Sfx.MOVE -> 40
        Sfx.TAB, Sfx.BACK -> 50
        Sfx.SELECT, Sfx.MENU -> 55
        Sfx.FAVORITE -> 70
        Sfx.LAUNCH -> 100
    }

    /** Короче не бывает: иначе мотор не успевает раскрутиться. */
    private val minMs = 35L
    /** При зажатой крестовине импульсы короче — мотор и так уже крутится. */
    private val repeatMs = 25L
    private var lastPulse = 0L

    private fun hasMotor(d: android.view.InputDevice): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 31) d.vibratorManager.vibratorIds.isNotEmpty()
        else @Suppress("DEPRECATION") d.vibrator.hasVibrator()
    }.getOrDefault(false)

    private fun isGamepad(d: android.view.InputDevice) =
        d.sources and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD ||
            d.sources and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK

    /** Геймпад с мотором: последний нажатый, иначе любой подключённый. */
    private fun gamepad(): android.view.InputDevice? {
        android.view.InputDevice.getDevice(lastDeviceId)?.let { if (hasMotor(it)) return it }
        return android.view.InputDevice.getDeviceIds().asSequence()
            .mapNotNull { android.view.InputDevice.getDevice(it) }
            .firstOrNull { isGamepad(it) && hasMotor(it) }
    }

    /** Что найдено — показывается в настройках, чтобы было понятно, почему вибрации нет. */
    fun target(): HapticTarget = when {
        gamepad() != null -> HapticTarget.GAMEPAD
        system != null -> HapticTarget.SYSTEM
        else -> HapticTarget.NONE
    }

    override fun play(s: Sfx) {
        if (level == HapticLevel.OFF) return
        val now = System.currentTimeMillis()
        if (s == Sfx.MOVE) {
            if (now - lastMove < 60) return
            lastMove = now
        }
        val repeating = now - lastPulse < 160
        lastPulse = now
        val ms = if (repeating && s == Sfx.MOVE) repeatMs else (durationFor(s) * level.scale).toLong().coerceAtLeast(minMs)
        runCatching { vibrate(ms, kick = !repeating) }
    }

    /**
     * Импульс с «толчком»: первые мс на полной силе, чтобы мотор сразу раскрутился,
     * затем выбранная сила. Без управления силой — просто импульс нужной длины.
     */
    private fun effect(ms: Long, amplitudeControl: Boolean, kick: Boolean): android.os.VibrationEffect {
        if (!amplitudeControl) return android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
        if (!kick || level.amplitude >= 255) return android.os.VibrationEffect.createOneShot(ms, level.amplitude)
        val boost = minOf(18L, ms / 2)
        return android.os.VibrationEffect.createWaveform(longArrayOf(boost, ms - boost), intArrayOf(255, level.amplitude), -1)
    }

    private fun vibrate(ms: Long, kick: Boolean) {
        val pad = gamepad()
        if (pad != null) {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val vm = pad.vibratorManager
                val amp = vm.vibratorIds.any { vm.getVibrator(it).hasAmplitudeControl() }
                vm.vibrate(android.os.CombinedVibration.createParallel(effect(ms, amp, kick)))
            } else {
                @Suppress("DEPRECATION") val v = pad.vibrator
                v.vibrate(effect(ms, v.hasAmplitudeControl(), kick))
            }
            return
        }
        val v = system ?: return
        val e = effect(ms, v.hasAmplitudeControl(), kick)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // «Медиа», а не «касания»: отклик касаний часто выключен в настройках Android
            v.vibrate(e, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_MEDIA))
        } else {
            v.vibrate(e)
        }
    }
}

/** Звук и вибрация вместе — то, что получает интерфейс. */
class Feedback(private val parts: List<SfxPlayer>) : SfxPlayer {
    override fun play(s: Sfx) = parts.forEach { it.play(s) }
}
