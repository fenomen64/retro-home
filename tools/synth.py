"""
Синтезатор звуков интерфейса и фоновой музыки для Retro Home.
Всё генерируется с нуля (никаких сэмплов) — запуск: python3 tools/synth.py <папка raw>
"""
import os
import subprocess
import sys
import numpy as np
from scipy.signal import butter, sosfilt

SR = 44100
rng = np.random.default_rng(7)


# ---------- базовые кирпичики ----------

def t_axis(dur):
    return np.arange(int(dur * SR)) / SR


def env(n, a=0.005, d=0.05, s=0.0, r=0.05, sustain_time=None):
    """ADSR-огибающая длиной n сэмплов."""
    a_n, d_n, r_n = int(a * SR), int(d * SR), int(r * SR)
    hold = n - a_n - d_n - r_n
    if hold < 0:
        hold = 0
    e = np.concatenate([
        np.linspace(0, 1, max(a_n, 1), endpoint=False),
        np.linspace(1, s, max(d_n, 1), endpoint=False),
        np.full(hold, s),
        np.linspace(s, 0, max(r_n, 1)),
    ])
    out = np.zeros(n)
    out[: min(n, len(e))] = e[:n]
    return out


def expdecay(n, tau):
    return np.exp(-np.arange(n) / (tau * SR))


def note_hz(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)


def sine(f, dur, phase=0.0):
    t = t_axis(dur)
    f = np.broadcast_to(f, t.shape) if np.ndim(f) else np.full(t.shape, f)
    return np.sin(2 * np.pi * np.cumsum(f) / SR + phase)


def square(f, dur, duty=0.5):
    t = t_axis(dur)
    f = np.broadcast_to(f, t.shape) if np.ndim(f) else np.full(t.shape, f)
    ph = (np.cumsum(f) / SR) % 1.0
    return np.where(ph < duty, 1.0, -1.0)


def triangle(f, dur):
    t = t_axis(dur)
    f = np.broadcast_to(f, t.shape) if np.ndim(f) else np.full(t.shape, f)
    ph = (np.cumsum(f) / SR) % 1.0
    return 4 * np.abs(ph - 0.5) - 1


def fm(f, dur, ratio=3.5, index=2.0, index_tau=0.15):
    """FM-колокольчик / электропиано."""
    t = t_axis(dur)
    idx = index * np.exp(-t / index_tau)
    mod = np.sin(2 * np.pi * f * ratio * t)
    return np.sin(2 * np.pi * f * t + idx * mod)


def noise(dur):
    return rng.standard_normal(int(dur * SR))


def lowpass(x, cutoff, order=2):
    sos = butter(order, min(cutoff, SR / 2 - 100), btype="low", fs=SR, output="sos")
    return sosfilt(sos, x)


def highpass(x, cutoff, order=2):
    sos = butter(order, cutoff, btype="high", fs=SR, output="sos")
    return sosfilt(sos, x)


def bandpass(x, lo, hi, order=2):
    sos = butter(order, [lo, hi], btype="band", fs=SR, output="sos")
    return sosfilt(sos, x)


def reverb(x, mix=0.25, decay=0.5, pre=0.012):
    """Простой ревербератор: несколько затухающих гребёнчатых задержек."""
    out = np.copy(x)
    wet = np.zeros(len(x) + int(SR * 2.0))
    for i, d in enumerate([0.0297, 0.0371, 0.0411, 0.0437, 0.053, 0.067]):
        delay = int((d + pre) * SR)
        g = decay ** (1 + i * 0.15)
        buf = np.zeros(len(wet))
        buf[: len(x)] = x
        # повторы через каждые delay сэмплов
        acc = np.zeros(len(wet))
        k = 1
        gain = g
        while k * delay < len(wet) and gain > 0.001:
            acc[k * delay:] += buf[: len(wet) - k * delay] * gain
            gain *= g
            k += 1
        wet += acc / 6
    wet = lowpass(wet, 5000)
    out = np.concatenate([out, np.zeros(len(wet) - len(x))])
    return out * (1 - mix) + wet * mix


def mix(*sigs):
    """Сложить сигналы разной длины."""
    n = max(len(s) for s in sigs)
    out = np.zeros(n)
    for s in sigs:
        out[: len(s)] += s
    return out


def place(buf, sig, at):
    s = int(at * SR)
    e = min(len(buf), s + len(sig))
    if s < len(buf):
        buf[s:e] += sig[: e - s]


def fade_edges(x, ms=3):
    n = int(SR * ms / 1000)
    x = x.copy()
    if len(x) > 2 * n:
        x[:n] *= np.linspace(0, 1, n)
        x[-n:] *= np.linspace(1, 0, n)
    return x


def trim_tail(x, thresh=1e-4):
    idx = np.where(np.abs(x) > thresh)[0]
    return x[: idx[-1] + 1] if len(idx) else x


def norm_peak(x, peak_db=-3.0):
    p = np.max(np.abs(x)) + 1e-9
    return x / p * 10 ** (peak_db / 20)


# ---------- звуки интерфейса ----------

EVENTS = ["move", "tab", "select", "launch", "back", "menu", "favorite"]


def pack_soft():
    out = {}
    d = 0.09
    s = sine(np.linspace(1046, 980, int(d * SR)), d) * expdecay(int(d * SR), 0.025)
    out["move"] = s * 0.8
    d = 0.16
    a = sine(784, d) * expdecay(int(d * SR), 0.04)
    b = np.zeros(int(0.2 * SR)); place(b, a, 0); place(b, sine(1046, d) * expdecay(int(d * SR), 0.05) * 0.8, 0.045)
    out["tab"] = b
    d = 0.18
    out["select"] = (sine(1318, d) + 0.4 * sine(2637, d)) * expdecay(int(d * SR), 0.05) * 0.7
    buf = np.zeros(int(0.7 * SR))
    for i, m in enumerate([72, 76, 79, 84]):
        place(buf, (sine(note_hz(m), 0.45) + 0.25 * sine(note_hz(m + 12), 0.45)) * expdecay(int(0.45 * SR), 0.12) * 0.55, i * 0.06)
    out["launch"] = reverb(buf, 0.3, 0.45)
    buf = np.zeros(int(0.3 * SR))
    place(buf, sine(880, 0.15) * expdecay(int(0.15 * SR), 0.04), 0)
    place(buf, sine(660, 0.18) * expdecay(int(0.18 * SR), 0.05), 0.06)
    out["back"] = buf * 0.8
    d = 0.35
    w = lowpass(noise(d), 1800) * env(int(d * SR), 0.12, 0.1, 0.2, 0.12) * 0.25
    chord = sum(sine(note_hz(m), d) for m in [67, 71, 74]) * env(int(d * SR), 0.06, 0.1, 0.4, 0.15) * 0.25
    out["menu"] = reverb(w + chord, 0.3, 0.4)
    buf = np.zeros(int(0.5 * SR))
    for i, m in enumerate([88, 91, 96]):
        place(buf, sine(note_hz(m), 0.3) * expdecay(int(0.3 * SR), 0.06) * 0.5, i * 0.05)
    out["favorite"] = reverb(buf, 0.35, 0.5)
    return out


def pack_retro():
    out = {}
    d = 0.05
    out["move"] = square(1760, d, 0.25) * env(int(d * SR), 0.001, 0.04, 0.0, 0.005) * 0.35
    buf = np.zeros(int(0.12 * SR))
    place(buf, square(988, 0.05, 0.5) * env(int(0.05 * SR), 0.001, 0.045, 0, 0.004) * 0.35, 0)
    place(buf, square(1319, 0.06, 0.5) * env(int(0.06 * SR), 0.001, 0.055, 0, 0.004) * 0.35, 0.05)
    out["tab"] = buf
    d = 0.1
    out["select"] = square(np.linspace(1319, 1760, int(d * SR)), d, 0.125) * env(int(d * SR), 0.001, 0.09, 0, 0.01) * 0.35
    buf = np.zeros(int(0.5 * SR))
    for i, m in enumerate([72, 76, 79, 84, 88]):
        place(buf, square(note_hz(m), 0.08, 0.25) * env(int(0.08 * SR), 0.001, 0.07, 0.3, 0.01) * 0.32, i * 0.065)
    out["launch"] = buf
    d = 0.14
    out["back"] = square(np.linspace(880, 440, int(d * SR)), d, 0.5) * env(int(d * SR), 0.001, 0.13, 0, 0.01) * 0.32
    buf = np.zeros(int(0.25 * SR))
    for i, m in enumerate([79, 84]):
        place(buf, triangle(note_hz(m), 0.12) * env(int(0.12 * SR), 0.002, 0.1, 0.2, 0.02) * 0.5, i * 0.07)
    out["menu"] = buf
    buf = np.zeros(int(0.35 * SR))
    for i, m in enumerate([91, 95, 98, 103]):
        place(buf, square(note_hz(m), 0.05, 0.125) * env(int(0.05 * SR), 0.001, 0.045, 0, 0.004) * 0.3, i * 0.045)
    out["favorite"] = buf
    return out


def pack_crystal():
    out = {}
    def bell(m, dur=0.5, tau=0.12, amp=0.5):
        return fm(note_hz(m), dur, ratio=3.5, index=1.6, index_tau=0.05) * expdecay(int(dur * SR), tau) * amp
    out["move"] = reverb(bell(96, 0.15, 0.03, 0.45), 0.25, 0.35)
    buf = np.zeros(int(0.4 * SR)); place(buf, bell(91, 0.3, 0.06), 0); place(buf, bell(96, 0.3, 0.06, 0.4), 0.06)
    out["tab"] = reverb(buf, 0.3, 0.4)
    out["select"] = reverb(bell(100, 0.35, 0.07, 0.5), 0.3, 0.45)
    buf = np.zeros(int(1.0 * SR))
    for i, m in enumerate([79, 83, 86, 91, 95]):
        place(buf, bell(m, 0.8, 0.2, 0.4), i * 0.055)
    out["launch"] = reverb(buf, 0.4, 0.55)
    buf = np.zeros(int(0.4 * SR)); place(buf, bell(91, 0.3, 0.05), 0); place(buf, bell(84, 0.3, 0.06), 0.06)
    out["back"] = reverb(buf, 0.3, 0.4)
    d = 0.6
    shimmer = sum(fm(note_hz(m), d, 2.0, 0.8, 0.2) for m in [79, 86, 91]) * env(int(d * SR), 0.08, 0.15, 0.3, 0.3) * 0.2
    out["menu"] = reverb(shimmer, 0.45, 0.55)
    buf = np.zeros(int(0.8 * SR))
    for i, m in enumerate([96, 100, 103, 108]):
        place(buf, bell(m, 0.4, 0.08, 0.35), i * 0.04)
    out["favorite"] = reverb(buf, 0.45, 0.5)
    return out


def pack_click():
    out = {}
    def click(cut_lo, cut_hi, dur=0.03, tau=0.004, amp=0.6):
        n = int(dur * SR)
        return bandpass(noise(dur), cut_lo, cut_hi) * expdecay(n, tau) * amp
    out["move"] = click(2500, 7000, 0.03, 0.003, 0.5)
    buf = np.zeros(int(0.08 * SR)); place(buf, click(1500, 5000), 0); place(buf, click(2500, 7000, amp=0.4), 0.035)
    out["tab"] = buf
    out["select"] = mix(click(1200, 4000, 0.05, 0.006, 0.7), sine(1200, 0.05) * expdecay(int(0.05 * SR), 0.01) * 0.15)
    buf = np.zeros(int(0.25 * SR))
    place(buf, click(800, 3000, 0.06, 0.008, 0.7), 0)
    place(buf, sine(660, 0.2) * expdecay(int(0.2 * SR), 0.05) * 0.25, 0.01)
    place(buf, sine(990, 0.2) * expdecay(int(0.2 * SR), 0.05) * 0.2, 0.07)
    out["launch"] = buf
    out["back"] = click(600, 2500, 0.05, 0.007, 0.6)
    buf = np.zeros(int(0.12 * SR)); place(buf, click(900, 3000, 0.05, 0.006), 0); place(buf, click(1800, 5000, amp=0.35), 0.05)
    out["menu"] = buf
    out["favorite"] = mix(click(3000, 9000, 0.04, 0.005, 0.5), sine(1760, 0.08) * expdecay(int(0.08 * SR), 0.02) * 0.15)
    return out


PACKS = {"soft": pack_soft, "retro": pack_retro, "crystal": pack_crystal, "click": pack_click}


# ---------- фоновая музыка ----------

def seamless(buf, loop_len):
    """Хвост после конца петли переносим в начало — стык не слышен."""
    L = int(loop_len * SR)
    out = buf[..., :L].copy()
    tail = buf[..., L:]
    n = min(tail.shape[-1], L)
    out[..., :n] += tail[..., :n]
    return out


def stereo(mono, pan=0.0):
    l = np.cos((pan + 1) * np.pi / 4)
    r = np.sin((pan + 1) * np.pi / 4)
    return np.stack([mono * l, mono * r])


def place_st(buf, sig, at):
    s = int(at * SR)
    e = min(buf.shape[1], s + sig.shape[1])
    if s < buf.shape[1]:
        buf[:, s:e] += sig[:, : e - s]


def track_ambient():
    """«Тихая гавань»: медленные пэды и колокольчики, 72 BPM."""
    bpm = 72
    beat = 60 / bpm
    bars = 16
    loop = bars * 4 * beat
    total = loop + 6
    buf = np.zeros((2, int(total * SR)))
    # Am – F – C – G (каждый аккорд 2 такта)
    chords = [[57, 60, 64, 69], [53, 57, 60, 65], [48, 55, 60, 64], [55, 59, 62, 67]] * 2
    for i, ch in enumerate(chords):
        start = i * 8 * beat
        dur = 8 * beat + 1.5
        pad = np.zeros(int(dur * SR))
        for m in ch:
            f = note_hz(m)
            detune = sum(sine(f * (1 + d), dur, phase=rng.random() * 6) for d in (-0.003, 0.0, 0.004)) / 3
            pad += detune
        pad = lowpass(pad, 1400) * env(len(pad), 1.5, 0.5, 0.8, 1.5) * 0.09
        place_st(buf, stereo(pad, -0.2), start)
        place_st(buf, stereo(pad * 0.8, 0.25), start + 0.03)
        # бас
        bass = sine(note_hz(ch[0] - 12), dur) * env(int(dur * SR), 0.3, 0.5, 0.7, 1.0) * 0.16
        place_st(buf, stereo(lowpass(bass, 300), 0), start)
    # колокольчики: неторопливая мелодия по аккордам
    melody = [76, 72, 69, 72, 77, 76, 72, 69, 79, 76, 72, 76, 74, 71, 67, 71]
    for i, m in enumerate(melody * 2):
        at = i * 2 * beat + (beat if i % 4 == 3 else 0)
        if at > loop:
            break
        b = fm(note_hz(m), 2.5, 3.5, 1.2, 0.08) * expdecay(int(2.5 * SR), 0.7) * 0.07
        place_st(buf, stereo(b, 0.4 if i % 2 else -0.4), at)
    # мягкий шум ветра
    wind = lowpass(noise(total), 600) * (0.5 + 0.5 * np.sin(2 * np.pi * t_axis(total) / 11)) * 0.012
    buf += stereo(wind, 0)
    wet = np.stack([reverb(buf[0], 0.35, 0.6)[: buf.shape[1]], reverb(buf[1], 0.35, 0.6)[: buf.shape[1]]])
    return seamless(wet, loop)


def track_chiptune():
    """«Пиксельный вечер»: спокойный 8-бит, 100 BPM."""
    bpm = 100
    beat = 60 / bpm
    step = beat / 4
    bars = 16
    loop = bars * 4 * beat
    total = loop + 2
    buf = np.zeros((2, int(total * SR)))
    prog = [[57, 60, 64], [53, 57, 60], [48, 52, 55], [55, 59, 62]]
    lead = [
        76, None, 79, None, 81, None, 79, 76, None, None, 74, None, 76, None, None, None,
        72, None, 74, None, 76, None, 77, 76, None, None, 72, None, 69, None, None, None,
        67, None, 72, None, 76, None, 79, None, 77, 76, 74, None, 72, None, None, None,
        74, None, 76, None, 79, None, 83, None, 81, None, 79, None, 74, None, None, None,
    ]
    for bar in range(bars):
        ch = prog[(bar // 2) % 4]
        bar_t = bar * 4 * beat
        # бас треугольником: корень и квинта
        for q in range(8):
            m = ch[0] - 12 if q % 2 == 0 else ch[0] - 5
            s = triangle(note_hz(m), step * 2 * 0.9) * env(int(step * 2 * 0.9 * SR), 0.002, 0.05, 0.6, 0.03) * 0.22
            place_st(buf, stereo(s, 0), bar_t + q * step * 2)
        # арпеджио тихим квадратом
        for q in range(16):
            m = ch[q % 3] + 12
            s = square(note_hz(m), step * 0.8, 0.125) * env(int(step * 0.8 * SR), 0.001, 0.06, 0.2, 0.02) * 0.04
            place_st(buf, stereo(s, -0.35), bar_t + q * step)
        # хай-хэт шумом и мягкая бочка
        for q in range(8):
            hh = highpass(noise(0.04), 7000) * expdecay(int(0.04 * SR), 0.01) * (0.05 if q % 2 else 0.03)
            place_st(buf, stereo(hh, 0.3), bar_t + q * beat / 2)
        for q in (0, 2):
            k = sine(np.linspace(120, 45, int(0.15 * SR)), 0.15) * expdecay(int(0.15 * SR), 0.05) * 0.3
            place_st(buf, stereo(k, 0), bar_t + q * beat)
        # мелодия: 4 такта на фразу
        phrase = lead[(bar % 4) * 16:(bar % 4 + 1) * 16]
        if bar >= 4:
            for q, m in enumerate(phrase):
                if m is None:
                    continue
                # длительность до следующей ноты
                n = 1
                while q + n < 16 and phrase[q + n] is None and n < 4:
                    n += 1
                d = step * n * 0.92
                vib = note_hz(m) * (1 + 0.004 * np.sin(2 * np.pi * 5.5 * t_axis(d)))
                s = square(vib, d, 0.5 if bar < 12 else 0.25) * env(int(d * SR), 0.003, 0.08, 0.55, 0.04) * 0.075
                place_st(buf, stereo(lowpass(s, 5000), 0.15), bar_t + q * step)
    # лёгкое эхо для глубины
    echo = np.zeros_like(buf)
    dl = int(step * 3 * SR)
    echo[:, dl:] = buf[:, :-dl] * 0.22
    buf = buf + echo[::-1]
    return seamless(buf, loop)


def track_lofi():
    """«Ночной город»: lo-fi с электропиано и ленивым битом, 78 BPM."""
    bpm = 78
    beat = 60 / bpm
    bars = 16
    loop = bars * 4 * beat
    total = loop + 4
    buf = np.zeros((2, int(total * SR)))
    # Dm9 – G13 – Cmaj9 – Am9
    prog = [[50, 57, 60, 64, 65], [43, 53, 57, 59, 64], [48, 55, 59, 62, 64], [45, 55, 59, 60, 64]]
    for bar in range(bars):
        ch = prog[bar % 4]
        bar_t = bar * 4 * beat
        swing = beat * 0.06
        # электропиано: аккорд на 1 и подхват на «и» третьей доли
        for at, vel in ((0.0, 1.0), (2.5 * beat + swing, 0.6)):
            dur = 2.2 * beat
            ep = np.zeros(int(dur * SR))
            for m in ch[1:]:
                ep += fm(note_hz(m), dur, ratio=1.0, index=1.4, index_tau=0.25) * expdecay(len(ep), 0.9)
            ep = lowpass(ep, 2600) * 0.045 * vel
            place_st(buf, stereo(ep, -0.15), bar_t + at)
        # бас
        bass = sine(note_hz(ch[0] - 12 if ch[0] > 47 else ch[0]), 3.5 * beat) * env(int(3.5 * beat * SR), 0.01, 0.3, 0.6, 0.3) * 0.2
        place_st(buf, stereo(lowpass(bass, 250), 0), bar_t)
        # барабаны
        for q in (0, 2.5):
            k = sine(np.linspace(110, 42, int(0.25 * SR)), 0.25) * expdecay(int(0.25 * SR), 0.09) * 0.42
            place_st(buf, stereo(k, 0), bar_t + q * beat)
        for q in (1, 3):
            sn = bandpass(noise(0.2), 900, 4500) * expdecay(int(0.2 * SR), 0.05) * 0.16
            place_st(buf, stereo(sn, 0.05), bar_t + q * beat + swing)
        for q in range(8):
            hh = highpass(noise(0.05), 6000) * expdecay(int(0.05 * SR), 0.015) * (0.045 if q % 2 == 0 else 0.03)
            place_st(buf, stereo(hh, 0.35), bar_t + q * beat / 2 + (swing if q % 2 else 0))
    # винил: тихое шипение и редкие щелчки
    hiss = lowpass(highpass(noise(total), 1500), 6000) * 0.006
    crackle = np.zeros(int(total * SR))
    for _ in range(int(total * 3)):
        crackle[rng.integers(0, len(crackle))] = rng.uniform(0.05, 0.15) * rng.choice([-1, 1])
    crackle = lowpass(crackle, 4000) * 0.4
    buf += stereo(hiss + crackle, 0)
    # «тёплый» срез верхов всего микса
    buf = np.stack([lowpass(buf[0], 7000), lowpass(buf[1], 7000)])
    wet = np.stack([reverb(buf[0], 0.18, 0.45)[: buf.shape[1]], reverb(buf[1], 0.18, 0.45)[: buf.shape[1]]])
    return seamless(wet, loop)


TRACKS = {"music_ambient": track_ambient, "music_chiptune": track_chiptune, "music_lofi": track_lofi}


# ---------- запись ----------

def write_ogg(path, x, quality=4, loop=False):
    x = np.clip(x, -1, 1)
    pcm = (x * 32767).astype("<i2")
    ch = 1 if x.ndim == 1 else x.shape[0]
    raw = pcm.tobytes() if ch == 1 else pcm.T.copy().tobytes()
    cmd = ["ffmpeg", "-y", "-loglevel", "error", "-f", "s16le", "-ar", str(SR), "-ac", str(ch), "-i", "-",
           "-c:a", "libvorbis", "-q:a", str(quality)]
    if loop:
        cmd += ["-metadata", "ANDROID_LOOP=true"]
    cmd.append(path)
    subprocess.run(cmd, input=raw, check=True)


def rms_db(x):
    return 20 * np.log10(np.sqrt(np.mean(np.square(x))) + 1e-12)


def main(outdir):
    os.makedirs(outdir, exist_ok=True)
    report = []
    for name, fn in PACKS.items():
        for ev, sig in fn().items():
            sig = fade_edges(trim_tail(sig))
            # Выравниваем по средней громкости (RMS), чтобы наборы звучали одинаково громко
            target = -18.0 if ev in ("launch", "menu") else -20.0
            sig = sig / (np.sqrt(np.mean(np.square(sig))) + 1e-9) * 10 ** (target / 20)
            peak = np.max(np.abs(sig))
            if peak > 10 ** (-2 / 20):
                sig = sig / peak * 10 ** (-2 / 20)
            write_ogg(os.path.join(outdir, f"sfx_{name}_{ev}.ogg"), sig, quality=5)
            report.append((f"sfx_{name}_{ev}", len(sig) / SR, 20 * np.log10(np.max(np.abs(sig))), rms_db(sig)))
    for name, fn in TRACKS.items():
        x = fn()
        # выравниваем громкость треков (по RMS), запас по пику
        x = x / (np.sqrt(np.mean(np.square(x))) + 1e-9) * 10 ** (-20 / 20)
        peak = np.max(np.abs(x))
        if peak > 0.89:
            x = x / peak * 0.89
        np.save(os.path.join("/tmp", name + ".npy"), x)
        write_ogg(os.path.join(outdir, name + ".ogg"), x, quality=4, loop=True)
        report.append((name, x.shape[1] / SR, 20 * np.log10(np.max(np.abs(x))), rms_db(x)))
    for r in report:
        print("%-24s %6.2fs  peak %6.1f dB  rms %6.1f dB" % r)


if __name__ == "__main__":
    main(sys.argv[1])
