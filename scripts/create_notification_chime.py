"""Generate friendbook's original short two-note notification sound (no external samples)."""
import math
import struct
import wave
from pathlib import Path

RATE = 44100
DURATION = 0.82
path = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/friendbook_chime.wav'
path.parent.mkdir(parents=True, exist_ok=True)
samples = []
for i in range(int(RATE * DURATION)):
    t = i / RATE
    value = 0.0
    for start, frequency, amplitude in [(0.0, 783.9909, .30), (.145, 1174.6591, .24)]:
        age = t - start
        if age >= 0:
            attack = min(1, age / .008)
            tail = min(1, max(0, (DURATION - t) / .09))
            tone = math.sin(2 * math.pi * frequency * age) + .16 * math.sin(2 * math.pi * frequency * 2 * age)
            value += amplitude * attack * math.exp(-age * 7.5) * tail * tone
    samples.append(struct.pack('<h', int(max(-1, min(1, value)) * 32767)))
with wave.open(str(path), 'wb') as audio:
    audio.setnchannels(1)
    audio.setsampwidth(2)
    audio.setframerate(RATE)
    audio.writeframes(b''.join(samples))
print(f'{path.name}: {DURATION}s, mono PCM, {RATE} Hz')
