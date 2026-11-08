"""Synthesises the film's soundtrack: a pad + arpeggio bed and UI sounds synced to on-screen events.
Usage: python3 tools/audio.py out/soundtrack.wav
"""
import sys
import numpy as np
from scipy.signal import butter, sosfilt, fftconvolve

SR = 48000
DUR = 34.5
N = int(SR * DUR)
rng = np.random.default_rng(7)
t_all = np.arange(N) / SR


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def lp(x, f, order=2):
    return sosfilt(butter(order, f, 'low', fs=SR, output='sos'), x)


def hp(x, f, order=2):
    return sosfilt(butter(order, f, 'high', fs=SR, output='sos'), x)


def bp(x, lo, hi, order=2):
    return sosfilt(butter(order, [lo, hi], 'band', fs=SR, output='sos'), x)


def place(buf, sig, at, gain=1.0, pan=0.0):
    i = int(at * SR)
    if i >= N:
        return
    sig = sig[: N - i]
    l, r = np.cos((pan + 1) * np.pi / 4), np.sin((pan + 1) * np.pi / 4)
    buf[0, i:i + len(sig)] += sig * gain * l * 1.414
    buf[1, i:i + len(sig)] += sig * gain * r * 1.414


def reverb(x, secs=2.8, damp=3000, wet=0.35):
    n = int(secs * SR)
    tt = np.arange(n) / SR
    out = np.zeros_like(x)
    for ch in range(2):
        ir = rng.standard_normal(n) * np.exp(-tt * 6.9 / secs)
        ir = lp(ir, damp)
        ir[: int(0.012 * SR)] = 0
        ir /= np.sqrt(np.sum(ir ** 2))
        out[ch] = fftconvolve(x[ch], ir)[: len(x[ch])]
    return x * (1 - wet) + out * wet * 1.6


# ---------------------------------------------------------------- music bed
music = np.zeros((2, N))
# D major colour; chords change every 5 s, resolving bright on the result
CHORDS = [
    (0.0, [50, 57, 61, 64, 66]),     # Dmaj9 (D A C# E F#)
    (4.2, [47, 54, 57, 61, 62]),     # Bm9
    (8.2, [43, 50, 54, 57, 59]),     # Gmaj9
    (12.8, [45, 52, 54, 57, 59]),    # A6/9 sus
    (17.6, [47, 54, 57, 62, 64]),    # Bm11
    (22.1, [43, 50, 55, 57, 62]),    # Gadd9
    (26.4, [50, 57, 62, 64, 66, 69]),  # D (open, bright)
    (31.0, [50, 57, 62, 66, 69]),    # D
    (DUR + 2, []),
]


def pad_voice(f, length):
    tt = np.arange(length) / SR
    s = np.zeros(length)
    for det in (-0.07, 0.0, 0.065):
        ff = f * 2 ** (det / 12)
        for h in range(1, 9):
            s += np.sin(2 * np.pi * ff * h * tt + rng.uniform(0, 6.28)) / h ** 1.6
    return s


for (t0, notes), (t1, _) in zip(CHORDS[:-1], CHORDS[1:]):
    a, b = t0 - 0.4, min(t1 + 1.6, DUR)
    length = int((b - max(a, 0)) * SR)
    env = np.ones(length)
    att, rel = int(1.6 * SR), int(1.8 * SR)
    env[:att] = np.linspace(0, 1, att) ** 1.5
    env[-rel:] *= np.linspace(1, 0, rel) ** 1.2
    for k, m in enumerate(notes):
        v = pad_voice(hz(m), length) * env
        v = lp(v, 900 + 250 * k)
        place(music, v, max(a, 0), 0.02, pan=(k / max(1, len(notes) - 1) - 0.5) * 0.7)

# sub bass on chord roots
for (t0, notes), (t1, _) in zip(CHORDS[:-1], CHORDS[1:]):
    if not notes:
        continue
    length = int((min(t1, DUR) - t0) * SR)
    tt = np.arange(length) / SR
    env = np.minimum(1, tt / 0.6) * np.minimum(1, (tt[::-1]) / 0.4)
    s = np.sin(2 * np.pi * hz(notes[0] - 12) * tt) * env
    place(music, s, t0, 0.05)


def pluck(f, dur=1.1, bright=1.0):
    n = int(dur * SR)
    tt = np.arange(n) / SR
    s = (np.sin(2 * np.pi * f * tt) + 0.35 * bright * np.sin(2 * np.pi * 2 * f * tt) * np.exp(-tt * 9)
         + 0.12 * np.sin(2 * np.pi * 3.01 * f * tt) * np.exp(-tt * 14))
    env = np.exp(-tt * 4.2) * np.minimum(1, tt / 0.004)
    return s * env


BEAT = 60 / 96
# arpeggio from the second beat; density grows with the story
for (t0, notes), (t1, _) in zip(CHORDS[:-1], CHORDS[1:]):
    if not notes or t0 < 4.0 or t0 > 30:
        continue
    seq = [notes[i % len(notes)] + 12 for i in (1, 3, 2, 4, 3, 1, 4, 2)]
    step = BEAT / 2
    t = t0
    i = 0
    while t < min(t1, DUR - 3):
        if t0 >= 12.8 or i % 2 == 0:
            vel = 0.8 + 0.2 * rng.random()
            place(music, pluck(hz(seq[i % len(seq)]), bright=0.6 + 0.4 * (t0 > 20)), t, 0.045 * vel, pan=rng.uniform(-0.4, 0.4))
        t += step
        i += 1

# soft pulse (felt kick) through the walkthrough
t = 12.8
while t < 26.2:
    n = int(0.35 * SR)
    tt = np.arange(n) / SR
    f = 52 * (1 + 1.2 * np.exp(-tt * 30))
    s = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt * 11)
    place(music, s, t, 0.11)
    t += BEAT

music = reverb(music, 3.2, 2600, 0.45)

# ---------------------------------------------------------------- sound effects
sfx = np.zeros((2, N))


def tap(f=2200, dur=0.06):
    n = int(dur * SR)
    tt = np.arange(n) / SR
    click = bp(rng.standard_normal(n), 1800, 6000) * np.exp(-tt * 140)
    tone = np.sin(2 * np.pi * f * tt) * np.exp(-tt * 70)
    return click * 0.5 + tone * 0.35


def whoosh(dur=0.6, lo=300, hi=5000, rise=True):
    n = int(dur * SR)
    tt = np.arange(n) / SR
    noise = rng.standard_normal(n)
    env = np.sin(np.pi * tt / dur) ** 2
    out = np.zeros(n)
    blocks = 24
    for k in range(blocks):
        a, b = k * n // blocks, (k + 1) * n // blocks
        frac = k / blocks if rise else 1 - k / blocks
        fc = lo * (hi / lo) ** frac
        out[a:b] = bp(noise, fc * 0.7, min(fc * 1.4, SR / 2 - 100))[a:b]
    return out * env


def chime(notes, gap=0.09, dur=1.6):
    n = int((dur + gap * len(notes)) * SR)
    s = np.zeros(n)
    for i, m in enumerate(notes):
        tt = np.arange(int(dur * SR)) / SR
        v = (np.sin(2 * np.pi * hz(m) * tt) + 0.25 * np.sin(2 * np.pi * hz(m) * 2.76 * tt) * np.exp(-tt * 6)) * np.exp(-tt * 3.2)
        i0 = int(i * gap * SR)
        s[i0:i0 + len(v)] += v
    return s


def boom(dur=1.8):
    n = int(dur * SR)
    tt = np.arange(n) / SR
    f = 46 * (1 + 0.8 * np.exp(-tt * 8))
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt * 2.6) + lp(rng.standard_normal(n), 200) * np.exp(-tt * 6) * 0.3


place(sfx, whoosh(0.55, 400, 7000), 7.95, 0.22, pan=0.3)            # whip into the scan shot
place(sfx, chime([81, 88], 0.07, 0.6), 8.2 + 1.3, 0.05, pan=0.1)     # lock-on
n = int(0.12 * SR)                                                  # shutter
place(sfx, hp(rng.standard_normal(n), 2500) * np.exp(-np.arange(n) / SR * 60), 8.2 + 2.75, 0.18)
place(sfx, tap(1600), 8.2 + 2.78, 0.3)
for at in [12.0 + 3.0, 15.4 + 2.35]:                                # buttons
    place(sfx, tap(1400, 0.08), at, 0.32)
for at in [15.95, 16.32, 16.68, 18.7, 18.96, 19.22, 19.48, 19.74]:  # keypad
    place(sfx, tap(2400 + rng.uniform(-200, 200)), at, 0.28, pan=rng.uniform(-0.2, 0.2))
# hold to pay: a rising tone while the bar fills
hold0, hold1 = 18.2 + 2.0, 18.2 + 3.45
n = int((hold1 - hold0) * SR)
tt = np.arange(n) / SR
f = 220 * 2 ** (tt / (hold1 - hold0) * 1.0)
rise = np.sin(2 * np.pi * np.cumsum(f) / SR) * (tt / tt[-1]) ** 1.5 * 0.5 + bp(rng.standard_normal(n), 800, 3000) * (tt / tt[-1]) ** 2 * 0.15
place(sfx, rise, hold0, 0.12)
place(sfx, chime([74, 81], 0.06, 0.8), hold1, 0.07)
for i, at in enumerate([0.1, 0.85, 1.4, 1.95, 2.45]):              # USSD dialogs fly out
    place(sfx, whoosh(0.35, 900, 4500), 22.1 + at - 0.05, 0.07, pan=-0.4)
for at in [0.35, 0.85, 1.4, 1.95, 2.45, 3.0, 3.5]:                  # step ticks
    place(sfx, tap(3200, 0.04), 22.1 + at, 0.14, pan=0.35)
place(sfx, chime([74, 78, 81, 86], 0.075, 2.2), 26.4 + 0.3, 0.11)    # paid
place(sfx, boom(), 27.25, 0.3)                                      # "0 MB"
place(sfx, boom(2.6), 31.3, 0.22)                                   # lockup
place(sfx, chime([86, 93], 0.12, 2.6), 31.35, 0.05)
for at in [4.2, 12.8, 17.6, 22.1]:                                  # cuts: a breath of air
    place(sfx, whoosh(0.4, 200, 1500, rise=False), at - 0.12, 0.05)
sfx = reverb(sfx, 1.4, 5000, 0.2)

mix = music * 1.0 + sfx * 1.0
fade_in = np.minimum(1, t_all / 1.2)
fade_out = np.minimum(1, (DUR - t_all) / 2.2)
mix *= fade_in * np.clip(fade_out, 0, 1)
# gentle bus compression + limiter
peak = np.max(np.abs(mix))
mix = np.tanh(mix / peak * 1.05) / np.tanh(1.05) * 0.6
out = (mix.T * 32767).astype(np.int16)

import wave
with wave.open(sys.argv[1], 'wb') as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(out.tobytes())
print('wrote', sys.argv[1])
