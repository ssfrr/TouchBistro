s.boot
s.quit

(


OSCdef(\button, {
    | msg |
    msg.postln;
}, '/manta/continuous/button', recvPort: ~defaultRecvPort);
)

/*
Touch Bistro

This script is heavily based on "Press Cafe", a Max/MSP Patch by Matthew Davidson (AKA stretta) for the monome. It also has some of my own tweaks and is adapted for the Snyderphonics Manta controller.

It's intended to use with the MantaOSC command-line application that comes as an example with the libmanta library.

Touch Bistro allows you to perform different repeating rhythmic patterns with different notes. These notes could be mapped to different pitches or they could be different samples like a drum machine. For the purposes of this script you can think of the Manta as an 8x6 grid. Each of the 8 columns represents a different note, and each of the 6 rows represents a different pattern. When you press a pad, it will play that note in that pattern. You can modulate the volume of the note events with the pad pressure.

The top two buttons allow you to switch between the pattern editor page and the performance page. The performance page is the default starting page and is where you actually control the patterns. The pattern editor page allows you to edit the pattern by adding or removing steps with a quick tap, or change the length by holding the last pad in the pattern for more than half a second (so patterns can be from 1-8 steps long). The length of each pattern is indicated with a red LED after the last pad, so if you don't see an LED then the pattern is 8 steps long. Note that changing the length of a pattern is non-destructive, i.e. notes past the end are retained.
*/

~defaultRecvPort = 31416;
~oscSender = NetAddr("localhost", 31417);
~oscSender.sendMsg('/manta/ledcontrol', "padandbutton", 1);

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

// some default patterns
(
~patterns = [
    (len: 1, steps: [1, 0, 0, 0, 0, 0, 0, 0]),
    (len: 2, steps: [1, 0, 0, 0, 0, 0, 0, 0]),
    (len: 3, steps: [1, 0, 0, 0, 0, 0, 0, 0]),
    (len: 4, steps: [1, 1, 0, 1, 0, 0, 0, 0]),
    (len: 3, steps: [1, 0, 1, 0, 0, 0, 0, 0]),
    (len: 8, steps: [1, 0, 0, 1, 1, 1, 0, 1])
];

// default the notes to a major scale
~notes = [0, 2, 4, 5, 7, 9, 11, 12] + 65;

// keep track of actively playing patterns
~active = nil!48;

// this is what happens whenever a step is triggered
~eventHandler = {
    | note, idx |
    Synth(\impulse, [freq: note.midicps]);
};

OSCdef(\pad, {
    | msg |
    // latency is only converted to beats when a pattern is launched, so the actual
    // latency will vary with tempo for any active patterns. This should only affect
    // the look-ahead time though, not the actual scheduled note time. If your
    // event sends MIDI instead of triggering SC synths, set the latency to 0
    var latency = 0.05; // how far in advance to schedule step events, in seconds
    var stepdur = 0.25; // beats
    var padnum = msg[1];
    // map pad row and column to pattern and note selection, respectively
    var pattern = ~patterns[padnum.div(8)];
    var noteidx = padnum.mod(8);
    var note = ~notes[noteidx];
    var value = msg[2];
    case { value == 0 && ~active[padnum].notNil}  {
        ~active[padnum].stop;
        ~active[padnum] = nil;
    } { value > 0 && ~active[padnum].isNil} {
        ~active[padnum] = {
            // note that we're explicitly using a step index here rather than `do` iteration
            // so that we can check if the length changes at every step.
            // initialize step to be the 2nd step of the sequence, which is 0 if we have a 1-length
            // pattern
            var step = if(pattern.len == 1, 0, 1);
            // play the first step immediately so there's no perceptual latency
            if(pattern.steps[0] != 0, {
                ~eventHandler.value(note, noteidx);
            });
            (stepdur-(latency/thisThread.clock.beatDur)).wait;
            {
                if(pattern.steps[step] != 0, {
                    // we're executing slightly before the time we want the step to run, so
                    // bundle all the OSC messages and schedule them into the future
                    s.makeBundle(latency, {
                        ~eventHandler.value(note, noteidx);
                    });
                });
                stepdur.wait;
                step = step + 1;
                if(step >= pattern.len, {
                    step = 0;
                });
            }.loop;
        }.fork;
    };
}, '/manta/continuous/pad', recvPort: ~defaultRecvPort);

~lastSliderValue = [nil, nil];
OSCdef(\slider, {
    | msg |
    var id = msg[1];
    var value = if(msg[2] == 65535, nil, msg[2]);
    if(~lastSliderValue[id].notNil && value.notNil, {
        if(id == 0, {
            var diff = value - ~lastSliderValue[id];
            var mult = diff.linexp(-4096, 4096, 0.5, 2);
            TempoClock.default.tempo = TempoClock.default.tempo * mult;
            TempoClock.default.tempo.postln;
        })
    });

    ~lastSliderValue[id] = value;
}, '/manta/continuous/slider', recvPort: ~defaultRecvPort);
)

TempoClock.default.tempo = 2
thisThread