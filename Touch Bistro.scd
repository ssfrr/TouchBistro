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

OSCFunc.trace(true);
// This is just some Manta LED Testing code
(
{
    var addr = NetAddr("localhost", 31417);
    var del = 0.02;
    OSCFunc.trace(true);
    addr.sendMsg('/manta/ledcontrol', "padandbutton", 1);
    {
        addr.sendMsg('/manta/led/pad/frame', "off", Int8Array[0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0, 0]);
        del.wait;
        48.do {
            | pad |
            addr.sendMsg('/manta/led/pad', "amber", pad);
            del.wait;
        }
    }.loop;
}.fork;
)

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
var defaultRecvPort = 31416;
var oscSender = NetAddr("localhost", 31417);
var patterns = [
    (len: 1, steps: [1, 0, 0, 0, 0, 0, 0, 0]),
    (len: 2, steps: [1, 0, 0, 0, 0, 0, 0, 0]),
    (len: 3, steps: [1, 0, 0, 0, 0, 0, 0, 0]),
    (len: 4, steps: [1, 1, 0, 1, 0, 0, 0, 0]),
    (len: 3, steps: [1, 0, 1, 0, 0, 0, 0, 0]),
    (len: 8, steps: [1, 0, 0, 1, 1, 1, 0, 1])
];

// default the notes to a major scale
var notes = [0, 2, 4, 5, 7, 9, 11, 12] + 65;

// keep track of actively playing patterns
var activepatterns = nil!48;

// count of how many active patterns want different pads to be amber and red
var activeleds = [0!48, 0!48];

var clearpadleds = {
    oscSender.sendMsg('/manta/led/pad/frame', "off", Int8Array[0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0, 0]);
};

var drawactive = {
    clearpadleds.value;
    [[activeleds[0], "amber"], [activeleds[1], "red"]].do {
        | colorinfo |
        var pads = colorinfo[0];
        var colorstring = colorinfo[1];
        // we need to zero-pad to a multiple of 4 entries because supercollider doesn't
        // zero-pad properly. Luckily MantaOSC ignores extra data
        var mask = Int8Array[0, 0, 0, 0, 0, 0, 0, 0];
        48.do {
            | padnum |
            if(pads[padnum] > 0, {
                var row = padnum.div(8);
                var column = padnum.mod(8);
                mask[row] = mask[row] | (0x80 >> column);
            })
        };
        oscSender.sendMsg('/manta/led/pad/frame', colorstring, mask);
    }
};

// this is what happens whenever a step is triggered
var eventHandler = {
    | note, idx |
    Synth(\sin, [freq: note.midicps]);
};

var lastSliderValue = [nil, nil];

var launchPattern = {
    | padnum, stepdur=0.25, latency=0.05 |
    // stepdur is in beats
    // latency is how far in advance to schedule step events, in seconds
    // latency is only converted to beats when a pattern is launched, so the actual
    // latency will vary with tempo for any active patterns. This should only affect
    // the look-ahead time though, not the actual scheduled note time. If your
    // event sends MIDI instead of triggering SC synths, set the latency to 0

    // map pad row and column to pattern and note selection, respectively
    var noteidx = padnum.mod(8);
    var instance = (
        activeleds: [[], []],
        pattern: patterns[padnum.div(8)],
        padnum: padnum,
        clearleds: {
            | self |
            self.activeleds.do {
                | colorpads, idx |
                colorpads.do {
                    | padnum |
                    activeleds[idx][padnum] = activeleds[idx][padnum] - 1;
                }
            };
            self.activeleds = [[], []];
        },
        setleds: {
            | self, step |
            var row = padnum.div(8);
            var steps = self.pattern.steps[0..(self.pattern.len-1)];
            if(steps.wrapAt(step) != 0) {
                // if the current step is active, make it red
                activeleds[1][self.padnum] = activeleds[1][self.padnum] + 1;
                self.activeleds[1] = self.activeleds[1].add(self.padnum);
            };
            6.do {
                | i |
                if(steps.wrapAt(step+(i-row)) != 0, {
                    var ledidx = self.padnum + ((i-row)*8);
                    activeleds[0][ledidx] = activeleds[0][ledidx] + 1;
                    self.activeleds[0] = self.activeleds[0].add(ledidx);
                });
            }
        }
    );

    instance.routine = {
        // note that we're explicitly using a step index here rather than `do` iteration
        // so that we can check if the length changes at every step.
        // initialize step to be the 2nd step of the sequence, which is 0 if we have a 1-length
        // pattern
        var step = if(instance.pattern.len == 1, 0, 1);
        // play the first step immediately so there's no perceptual latency
        if(instance.pattern.steps[0] != 0, {
            eventHandler.value(notes[noteidx], noteidx);
        });
        instance.setleds(0);
        drawactive.value;
        (stepdur-(latency/thisThread.clock.beatDur)).wait;
        {
            if(instance.pattern.steps[step] != 0, {
                // we're executing slightly before the time we want the step to run, so
                // bundle all the OSC messages and schedule them into the future
                s.makeBundle(latency, {
                    eventHandler.value(notes[noteidx], noteidx);
                });
            });
            instance.clearleds;
            instance.setleds(step);
            drawactive.value;
            stepdur.wait;
            step = step + 1;
            if(step >= instance.pattern.len, {
                step = 0;
            });
        }.loop;
    }.fork;

    instance
};

oscSender.sendMsg('/manta/ledcontrol', "padandbutton", 1);

OSCdef(\pad, {
    | msg |
    var padnum = msg[1];
    var value = msg[2];
    case { value == 0 && activepatterns[padnum].notNil}  {
        activepatterns[padnum].clearleds;
        drawactive.value;
        activepatterns[padnum].routine.stop;
        activepatterns[padnum] = nil;
    } { value > 0 && activepatterns[padnum].isNil} {
        activepatterns[padnum] = launchPattern.value(padnum);
    };
}, '/manta/continuous/pad', recvPort: defaultRecvPort);

OSCdef(\slider, {
    | msg |
    var id = msg[1];
    var value = if(msg[2] == 65535, nil, msg[2]);
    if(~lastSliderValue[id].notNil && value.notNil, {
        if(id == 0, {
            var diff = value - ~lastSliderValue[id];
            var mult = diff.linexp(-4096, 4096, 0.5, 2);
            TempoClock.default.tempo = TempoClock.default.tempo * mult;
        })
    });

    ~lastSliderValue[id] = value;
}, '/manta/continuous/slider', recvPort: ~defaultRecvPort);
)