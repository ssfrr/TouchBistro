Quarks.install("/Users/srussell/Dropbox/projects/active/TouchBistro")

s.boot;
// a synth to test with
(
SynthDef(\sin, {
    |freq=220, amp=0.1|
    var mod = ExpRand(freq/1.01, freq*1.01);
    var noise = PinkNoise.ar * Env.perc(0.001, 0.05).ar() * amp*0.5;
    Out.ar(0, SinOsc.ar(mod!2) * Env.perc.ar(doneAction:2) * amp + noise) }
).add;

SynthDef(\impulse, {
    | amp=0.1 |
    Env([0, 0], [0.001]).ar(doneAction:2);
    OffsetOut.ar(0, Impulse.ar(0!2) * amp) }
).add;
)

(
t = TouchBistro(s);
t.eventHandler = {
    | note, idx |
    Synth(\sin, [freq: note.midicps])
}
)