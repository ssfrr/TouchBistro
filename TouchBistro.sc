/*
Touch Bistro

This script is heavily based on "Press Cafe", a Max/MSP Patch by Matthew Davidson (AKA stretta) for the monome. It also has some of my own tweaks and is adapted for the Snyderphonics Manta controller.

It's intended to use with the MantaOSC command-line application that comes as an example with the libmanta library, and the MantaOSC quark.

Touch Bistro allows you to perform different repeating rhythmic patterns with different notes. These notes could be mapped to different pitches or they could be different samples like a drum machine. For the purposes of this script you can think of the Manta as an 8x6 grid. Each of the 8 columns represents a different note, and each of the 6 rows represents a different pattern. When you press a pad, it will play that note in that pattern. You can modulate the volume of the note events with the pad pressure.

The top two buttons allow you to switch between the pattern editor page and the performance page. The performance page is the default starting page and is where you actually control the patterns. The pattern editor page allows you to edit the pattern by adding or removing steps with a quick tap, or change the length by holding the last pad in the pattern for more than half a second (so patterns can be from 1-8 steps long). The length of each pattern is indicated with a red LED after the last pad, so if you don't see an LED then the pattern is 8 steps long. Note that changing the length of a pattern is non-destructive, i.e. notes past the end are retained.
*/

TouchBistro {
    var manta;
    var server;
    var name;
    var >eventHandler;
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
    // this is used to track double-taps
    var lastStepToggled;
    var lastStepToggledTime;
    classvar instances;

    *initClass {
        instances = IdentityDictionary.new;
    }

    // the name is used as a key for the background task that updates the LEDs on the manta.
    // This way you can re-create the TouchBistro instance multiple times without leaving
    // dangling tasks. if multiple TouchBistro instances exist simultaneously they should
    // be given different names.
    *new {
        | manta, server, name=\default |
        server.isNil.if { server = Server.default };
        manta.isNil.if { manta = MantaCLI(\osc) };
        ^super.newCopyArgs(manta, server, name).init;
    }

    init {
        // default to major scale
        noteIntervals = [0, 2, 2, 1, 2, 2, 2, 1];
        // some default patterns
        patternData = [
            (len: 1, steps: [1, 0, 0, 0, 0, 0, 0, 0]),
            (len: 2, steps: [1, 0, 0, 0, 0, 0, 0, 0]),
            (len: 3, steps: [1, 0, 0, 0, 0, 0, 0, 0]),
            (len: 4, steps: [1, 0, 0, 0, 0, 0, 0, 0]),
            (len: 3, steps: [1, 0, 1, 0, 0, 0, 0, 0]),
            (len: 8, steps: [1, 0, 0, 1, 1, 1, 0, 1])
        ];
        // noteOffset is a reference (backtick) because it's shared between the active
        // patterns, and we can update it on the fly
        noteOffset = `65;
        manta.debug = false;
        performancePage = manta.newPage;
        patternPage = manta.newPage;
        notePage = manta.newPage;
        manta.enableLedControl;

        // setup the pattern page
        patternData.do {
            | pattern, patIdx |
            if(pattern.len < 8, { patternPage.setPadByRowCol(patIdx, pattern.len, \red); });
            pattern.steps.do {
                | step, stepIdx |
                if(step > 0, { patternPage.setPadByRowCol(patIdx, stepIdx, \amber); });
            }
        };

        patternPage.onPadVelocity = {
            | index, value, row, column |
            if(value > 0, {
                var pattern = patternData[row];
                var thisTime = thisThread.clock.seconds;
                if(pattern.steps[column] > 0, {
                    // turn the step off
                    patternPage.clearPadByRowCol(row, column, \amber);
                    pattern.steps[column] = 0;
                }, {
                    // turn the step on
                    patternPage.setPadByRowCol(row, column, \amber);
                    pattern.steps[column] = 1;
                });
                if(lastStepToggled == [row, column], {
                    if((thisTime - lastStepToggledTime) < 0.2, {
                        // we have a double-tap, set the length.
                        if(pattern.len < 8, { patternPage.clearPadByRowCol(row, pattern.len, \red); });
                        pattern.len = column+1;
                        if(pattern.len < 8, { patternPage.setPadByRowCol(row, pattern.len, \red); });
                    });
                });
                lastStepToggled = [row, column];
                lastStepToggledTime = thisTime;
            });
        };

        // setup the notes page

        noteIntervals[1..].do {
            | interval, idx |
            notePage.setPadByRowCol(interval-1, idx, \amber);
        };
        notePage.onPadVelocity = {
            | index, value, row, column |
            if(value > 0 && (column <= 6), {
                notePage.clearPadByRowCol(noteIntervals[column+1]-1, column);
                noteIntervals[column+1] = row+1;
                notePage.setPadByRowCol(row, column, \amber);
            })
        };
        // keep track of actively playing patterns
        activepatterns = nil!48;

        performancePage.onPadVelocity = {
            | index, value, row, column |
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
                    activepatterns[idx] = PatternInstance(server, patternData, row, noteIntervals, noteOffset, column, performancePage, eventHandler, latency, value);
                },
                \destroy, {
                    activepatterns[idx].stop;
                    activepatterns[idx] = nil;
                }
            )
        };

        performancePage.onPadValue = {
            | index, value, row, column |
            var idx = row*8+column;
            if(activepatterns[idx].notNil, {
                activepatterns[idx].velocity = value;
            });
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
        };

        // launch the manta draw routine if we haven't already launched one with this name
        instances[name].isNil.if {
            {
                {
                    // note that we pull from the instances dict every time so that if the
                    // instance gets replaced with a new one in the future this loop will
                    // still send the draw message.
                    instances[name].draw;
                    (1/30/thisThread.clock.beatDur).wait;
                }.loop;
            }.fork;
        };

        instances[name] = this;
    }

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
    var >velocity = 0.0;
    var activeLeds;
    var routine;
    const stepdur=0.25; // in beats, i.e. quarter notes

    *new {
        | server, patternData, patternIdx, noteIntervals, noteOffset, noteIdx, page, eventHandler, latency, velocity |
        // NOTE: very well could be ordering problems here as this hasn't been tested. In progress of adding
        // note page support here
        var instance = super.newCopyArgs(server, patternData, patternIdx, noteIntervals, noteOffset, noteIdx, page, eventHandler, latency, velocity);
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
            eventHandler.value(this.note, noteIdx, velocity);
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
                        eventHandler.value(this.note, noteIdx, velocity);
                    });
                }, {
                    // just run the event handler without bundling
                    eventHandler.value(this.note, noteIdx, velocity);
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
            page.setPadByRowCol(patternIdx, noteIdx, \red);
            activeLeds[\red] = activeLeds[\red].add([patternIdx, noteIdx]);
        };
        6.do {
            | i |
            if(steps.wrapAt(step+(i-patternIdx)) != 0, {
                //var ledidx = self.padnum + ((i-row)*8);
                page.setPadByRowCol(i, noteIdx, \amber);
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
                page.clearPadByRowCol(rowcol[0], rowcol[1], color);
            };
            activeLeds[color] = [];
        }
    }
}
