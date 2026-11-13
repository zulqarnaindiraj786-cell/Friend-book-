"""Generate original short chat-only send/receive tones, not WhatsApp recordings."""
import math, struct, wave
from pathlib import Path
OUT=Path(__file__).resolve().parents[2]/'app/src/main/res/raw'
SR=22050
def save(name,duration,fn):
    values=[fn(i/SR) for i in range(int(duration*SR))]
    peak=max(abs(v) for v in values) or 1
    with wave.open(str(OUT/(name+'.wav')),'wb') as w:
        w.setparams((1,2,SR,0,'NONE','not compressed'))
        w.writeframes(b''.join(struct.pack('<h',int(v/peak*.8*32767)) for v in values))
def tap(t,freq,duration):
    if t<0 or t>=duration: return 0
    envelope=min(1,t/.002)*min(1,(duration-t)/.006)*math.exp(-t*38)
    return envelope*(math.sin(2*math.pi*freq*t)+.12*math.sin(4*math.pi*freq*t))
# Subtle percussive two-tone taps; no long ringtone or slow attack.
save('sfx_message_sent',.095,lambda t:tap(t,840,.095)+.4*tap(t-.022,1120,.073))
save('message_pop',.14,lambda t:tap(t,1120,.07)+.65*tap(t-.06,840,.08))
print('Generated two original chat-only PCM tones')
