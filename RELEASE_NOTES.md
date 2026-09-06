## LightVoice v1.3 — the hold hint leaves after you use it, and the circle stops shoving the text

Two small things that only show up once you are actually talking to June.

**The "HOLD" coaching text now goes away after the first use.** The Ask tab used to sit
permanently under "HOLD TO TALK" with "HOLD" in the circle — an instruction you needed exactly
once, on the first open, and never again. The first time the mic actually opens the flag is set,
and from then on the idle circle is just the ring with a dot in it and no caption. A mis-tap that
never started listening does not count: the flag is written at the moment the mic opens, not the
moment the finger goes down.

**The circle's pulse no longer moves the text above it.** While you talk the ring swells and
shrinks to the sound of your voice. It did that by growing its own layout size, which reflowed the
whole screen every level sample — so the status line and the transcript above the circle jumped up
and down in time with the blinking. The ring now stays a fixed size in layout and the pulse is a
visual scale, with the height reserved up front so the grown ring never covers the text either side
of it.

- No schema change. Installs over 1.2.x and keeps everything.
