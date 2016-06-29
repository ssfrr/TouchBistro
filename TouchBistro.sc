/*
Touch Bistro

This script is heavily based on "Press Cafe", a Max/MSP Patch by Matthew Davidson (AKA stretta) for the monome. It also has some of my own tweaks and is adapted for the Snyderphonics Manta controller.

It's intended to use with the MantaOSC command-line application that comes as an example with the libmanta library, and the MantaOSC quark.

Touch Bistro allows you to perform different repeating rhythmic patterns with different notes. These notes could be mapped to different pitches or they could be different samples like a drum machine. For the purposes of this script you can think of the Manta as an 8x6 grid. Each of the 8 columns represents a different note, and each of the 6 rows represents a different pattern. When you press a pad, it will play that note in that pattern. You can modulate the volume of the note events with the pad pressure.

The top two buttons allow you to switch between the pattern editor page and the performance page. The performance page is the default starting page and is where you actually control the patterns. The pattern editor page allows you to edit the pattern by adding or removing steps with a quick tap, or change the length by holding the last pad in the pattern for more than half a second (so patterns can be from 1-8 steps long). The length of each pattern is indicated with a red LED after the last pad, so if you don't see an LED then the pattern is 8 steps long. Note that changing the length of a pattern is non-destructive, i.e. notes past the end are retained.
*/

TouchBistro {
    var server;
    var >eventHandler;
    var manta;
    var performancePage;
    var patternPage;
    var notePage;
    var patternData;
    var noteIntervals;
    var noteOffset;
    var activepatterns;
    var activeleds;
    var <>latency = 0.05;
    var toggleMode = false;

    *new {
        | server |
        var instance = super.newCopyArgs(server);
        instance.init;
        ^instance;
    }

    init {
        // default to major scale
        noteIntervals = [0, 2, 2, 1, 2, 2, 2, 1];
        // some default patterns
        patternData = [
            (len: 1, steps: [1, 0, 0, 0, 0, 0, 0, 0]),
            (len: 2, steps: [1, 0, 0, 0, 0, 0, 0, 0]),
            (len: 3, steps: [1, 0, 0, 0, 0, 0, 0, 0]),
            (len: 4, steps: [1, 1, 0, 1, 0, 0, 0, 0]),
            (len: 3, steps: [1, 0, 1, 0, 0, 0, 0, 0]),
            (len: 8, steps: [1, 0, 0, 1, 1, 1, 0, 1])
        ];
        // noteOffset is a reference because it's shared between the active patterns, and we can update
        // it on the fly
        noteOffset = `65;
        manta = MantaOSC();
        performancePage = manta.newPage;
        patternPage = manta.newPage;
        notePage = manta.newPage;
        manta.enableLedControl;

        // setup the pattern page
        patternData.do {
            | pattern, patIdx |
            if(pattern.len < 8, { patternPage.setPad(patIdx, pattern.len, \red); });
            pattern.steps.do {
                | step, stepIdx |
                if(step > 0, { patternPage.setPad(patIdx, stepIdx, \amber); });
            }
        };

        // setup the notes page

        noteIntervals[1..].do {
            | interval, idx |
            notePage.setPad(interval-1, idx);
        };
        notePage.onPadVelocity = {
            | row, column, value |
            if(value > 0 && (column <= 6), {
                notePage.clearPad(noteIntervals[column+1]-1, column);
                noteIntervals[column+1] = row+1;
                notePage.setPad(row, column);
            })
        };
        // keep track of actively playing patterns
        activepatterns = nil!48;

        performancePage.onPadVelocity = {
            | row, column, value |
            var idx = row*8+column;
            var action = nil;
            case {value > 0 && activepatterns[idx].isNil} {
                action = \create;
            } {toggleMode && (value > 0) && activepatterns[idx].notNil} {
                action = \destroy;
            } { toggleMode.not && (value == 0) && activepatterns[idx].notNil} {
                action = \destroy;
            };

            switch(action,
                \create, {
                    activepatterns[idx] = PatternInstance(server, patternData, row, noteIntervals, noteOffset, column, performancePage, eventHandler, latency);
                },
                \destroy, {
                    activepatterns[idx].stop;
                    activepatterns[idx] = nil;
                }
            )
        };

        manta.onSliderAccum = {
            | id, value |
            if(id == 0, { TempoClock.default.tempo = 2**value; });
        };
        manta.onButtonVelocity = {
            | id, value |
            if(id == 1 && (value > 0), {
                toggleMode = toggleMode.not;
                // TODO: set button LED
            });
        }
    }

    // it's up to the user to periodically call this draw method to update the display
    draw {
        manta.draw;
    }
}

// A PatternInstance is an actively-playing pattern.
PatternInstance {
    var server;
    // this is a reference to the shared pattern data, which can change
    // while the pattern is running.
    var patternData;
    var patternIdx;
    var noteIntervals;
    var noteOffset;
    var noteIdx;
    var page;
    var eventHandler;
    // latency is how far in advance to schedule step events, in seconds
    // latency is only converted to beats when a pattern is launched, so the actual
    // latency will vary with tempo for any active patterns. This should only affect
    // the look-ahead time though, not the actual scheduled note time. If your
    // event sends MIDI instead of triggering SC synths, set the latency to 0
    var latency;
    var activeLeds;
    var routine;
    const stepdur=0.25; // in beats, i.e. quarter notes

    *new {
        | server, patternData, patternIdx, noteIntervals, noteOffset, noteIdx, page, eventHandler, latency |
        // NOTE: very well could be ordering problems here as this hasn't been tested. In progress of adding
        // note page support here
        var instance = super.newCopyArgs(server, patternData, patternIdx, noteIntervals, noteOffset, noteIdx, page, eventHandler, latency);
        instance.init;
        ^instance;
    }

    init {
        activeLeds = (amber: [], red: []);
        routine = { this.routineFunc; }.fork;
    }

    // get the correct MIDI note for this pattern
    note {
        ^(noteIntervals.integrate[noteIdx] + noteOffset.value);
    }

    routineFunc {
        // note that we're explicitly using a step index here rather than `do` iteration
        // so that we can check if the length changes at every step.
        // initialize step to be the 2nd step of the sequence, which is 0 if we have a 1-length
        // pattern
        var pattern = patternData[patternIdx];
        var step = if(pattern.len == 1, 0, 1);
        // play the first step immediately so there's no perceptual latency
        // TODO: we probably want to fork the event handler in case the user puts any waits in it
        if(pattern.steps[0] != 0, {
            eventHandler.value(this.note, noteIdx);
        });
        this.setLeds(0);
        // the first time we delay somewhat less than the step size to accomodate the schedule-ahead
        // latency
        (stepdur-(latency/thisThread.clock.beatDur)).wait;
        {
            if(pattern.steps[step] != 0, {
                if(latency > 0, {
                    // we're executing slightly before the time we want the step to run, so
                    // bundle all the OSC messages and schedule them into the future
                    server.makeBundle(latency, {
                        eventHandler.value(this.note, noteIdx);
                    });
                }, {
                    // just run the event handler without bundling
                    eventHandler.value(this.note, noteIdx);
                })
            });
            this.clearLeds();
            this.setLeds(step);
            stepdur.wait;
            step = step + 1;
            if(step >= pattern.len, {
                step = 0;
            });
        }.loop;
    }

    stop {
        routine.stop;
        this.clearLeds();
    }

    // set the LEDs to indicate the pattern state, given the current step
    setLeds {
        | step |
        var pattern = patternData[patternIdx];
        var steps = pattern.steps[0..(pattern.len-1)];
        if(steps.wrapAt(step) != 0) {
            // if the current step is active, make it red
            page.setPad(patternIdx, noteIdx, \red);
            activeLeds[\red] = activeLeds[\red].add([patternIdx, noteIdx]);
        };
        6.do {
            | i |
            if(steps.wrapAt(step+(i-patternIdx)) != 0, {
                //var ledidx = self.padnum + ((i-row)*8);
                page.setPad(i, noteIdx, \amber);
                activeLeds[\amber] = activeLeds[\amber].add([i, noteIdx]);
            });
        }
    }

    // clear any set LEDs for this pattern
    clearLeds {
        [\amber, \red].do {
            | color |
            activeLeds[color].do {
                | rowcol |
                page.clearPad(rowcol[0], rowcol[1], color);
            };
            activeLeds[color] = [];
        }
    }
}