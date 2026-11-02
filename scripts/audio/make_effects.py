"""Original deterministic synthesized effects; no third-party or platform sound samples."""
import math, random, struct, wave
from pathlib import Path
OUT=Path(__file__).resolve().parents[2]/'app/src/main/res/raw'
SR=22050
rng=random.Random(5818)
def save(name, duration, fn, peak=.72):
    values=[fn(i/SR) for i in range(int(duration*SR))]
    maximum=max(abs(v) for v in values) or 1
    with wave.open(str(OUT/(name+'.wav')),'wb') as w:
        w.setparams((1,2,SR,0,'NONE','not compressed'))
        w.writeframes(b''.join(struct.pack('<h',int(max(-1,min(1,v/maximum*peak))*32767)) for v in values))
def chirp(t,f0,f1,d): return math.sin(2*math.pi*(f0*t+(f1-f0)*t*t/(2*d)))
def ping(t,d,f0,f1):
    return math.sin(math.pi*min(1,t/d))**2*math.exp(-t*8)*(chirp(t,f0,f1,d)+.18*chirp(t,2*f0,2*f1,d))
save('sfx_reaction',.16,lambda t:ping(t,.16,680,1220))
save('sfx_comment',.24,lambda t:ping(t,.24,480,760)+(.30*ping(t-.07,.17,920,710) if t>.07 else 0))
save('sfx_message_sent',.23,lambda t:ping(t,.23,420,1240))
save('sfx_message_received',.32,lambda t:ping(t,.32,1040,680)+(.30*ping(t-.11,.21,1260,960) if t>.11 else 0))
save('sfx_gift',.43,lambda t:ping(t,.43,700,1420)+.25*ping(t,.43,1050,2100))
# Two textured, chesty synthetic growl/roar pulses: low formants + irregular harmonic excitation.
low=0.0
phase=0.0
def roar(t):
    global low,phase
    noise=rng.uniform(-1,1); low=.86*low+.14*noise
    env=0
    for start,length in [(0.08,1.25),(1.52,1.0)]:
        u=(t-start)/length
        if 0<u<1: env+=math.sin(math.pi*u)**.75*(.72+.28*math.sin(2*math.pi*8*t)**2)
    freq=72+24*math.sin(math.pi*t/2.8)+8*low
    phase+=2*math.pi*freq/SR
    source=sum(math.sin(phase*k)/(k**.8) for k in range(1,9))
    return env*(.55*math.tanh(source*1.5)+1.9*low+.16*noise)
save('sfx_lion_roar',2.8,roar,.78)
low=0.0
phase=0.0
def flyby(t):
    global low,phase
    noise=rng.uniform(-1,1); low=.94*low+.06*noise
    env=math.sin(math.pi*t/3.0)**2
    pitch=260-130*(t/3.0)+48*math.exp(-((t-1.25)/.5)**2)
    phase+=2*math.pi*pitch/SR
    return env*(.5*noise+1.7*low+.13*math.sin(phase)+.07*math.sin(2.07*phase))
save('sfx_airplane',3.0,flyby,.72)
print('Generated 7 original mono PCM effects at',SR,'Hz')
