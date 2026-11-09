// Unit tests for the Synesthesia parsers (pure functions).
// Run in the IDE with: TestSynesthesiaParsers.run

TestSynesthesiaParsers : UnitTest {

	test_hits_basic {
		var ev = Synesthesia.parseHits("x.x.", 0.25, 1);
		this.assertEquals(ev.size, 2, "two onsets");
		this.assertEquals(ev.collect(_[\dur]).asArray, [0.5, 0.5], "rests fold into durs");
		this.assertEquals(ev.collect(_[\amp]).asArray, [1, 1], "amps");
		this.assert(ev.every { |e| e[\rest].not }, "no rest events");
	}

	test_hits_leadingRest {
		var ev = Synesthesia.parseHits(".x", 0.25, 1);
		this.assertEquals(ev.size, 2, "leading rest + onset");
		this.assert(ev[0][\rest], "first event is rest");
		this.assertEquals(ev[0][\dur], 0.25, "rest dur");
		this.assertEquals(ev[1][\dur], 0.25, "onset dur");
	}

	test_hits_accent {
		var ev = Synesthesia.parseHits("xX", 0.25, 1, 1.4);
		this.assertEquals(ev[0][\amp], 1, "plain hit amp");
		this.assertEquals(ev[1][\amp], 1.4, "accented amp");
	}

	test_hits_digits {
		var ev = Synesthesia.parseHits("0.2.", 0.25, 1);
		this.assertEquals(ev.collect(_[\index]).asArray, [0, 2], "digit variant indices");
		this.assertEquals(ev.collect(_[\dur]).asArray, [0.5, 0.5], "digit durs");
	}

	test_hits_spacesIgnored {
		var a = Synesthesia.parseHits("x. x.", 0.25, 1);
		var b = Synesthesia.parseHits("x.x.", 0.25, 1);
		this.assertEquals(a.collect(_[\dur]).asArray, b.collect(_[\dur]).asArray,
			"spaces are visual only");
	}

	test_hits_allRests {
		var ev = Synesthesia.parseHits("....", 0.25, 1);
		this.assertEquals(ev.size, 1, "single rest event");
		this.assert(ev[0][\rest], "is rest");
		this.assertEquals(ev[0][\dur], 1.0, "full-length rest");
	}

	test_degs_basic {
		this.assertEquals(Synesthesia.parseDegs("0 3 5"), [0, 3, 5], "degrees");
	}

	test_degs_chord {
		this.assertEquals(Synesthesia.parseDegs("[0 2 4] 7"), [[0, 2, 4], 7], "bracket chord");
	}

	test_degs_rest {
		this.assertEquals(Synesthesia.parseDegs("0 . 3"), [0, \rest, 3], "dot rest");
		this.assertEquals(Synesthesia.parseDegs("0 ~ 3"), [0, \rest, 3], "tilde rest");
	}

	test_degs_negative {
		this.assertEquals(Synesthesia.parseDegs("-3 12"), [-3, 12], "negative degrees");
	}

	// ---- chord names ----------------------------------------------------

	test_chords_basic {
		this.assertEquals(Synesthesia.parseChords("Cm7 Fm9"),
			[[0, 3, 7, 10], [5, 8, 12, 15, 19]], "chord names become note arrays");
	}

	// the fault this parser exists for: \G7 in a \note stream is the note G in
	// octave 7, far above hearing, and it overloads whatever plays it
	test_chords_seventhIsNotAnOctave {
		this.assertEquals(Synesthesia.parseChords("G7"), [[7, 11, 14, 17]], "G7 is a dominant seventh");
		this.assertEquals(Synesthesia.parseChords("Bb7"), [[10, 14, 17, 20]], "Bb7 is a dominant seventh");
		this.assertEquals(Synesthesia.parseChords("D9"), [[2, 6, 9, 12, 16]], "D9 is a ninth chord");
	}

	test_chords_bareRoot {
		this.assertEquals(Synesthesia.parseChords("Ab C"), [[8, 12, 15], [0, 4, 7]], "a bare root is major");
	}

	test_chords_chartSpellings {
		this.assertEquals(Synesthesia.parseChords("Abmaj7"), [[8, 12, 15, 19]], "maj7");
		this.assertEquals(Synesthesia.parseChords("F#m7b5"), [[6, 9, 12, 16]], "sharp root, flat five");
		this.assertEquals(Synesthesia.parseChords("Bbmin7"), [[10, 13, 17, 20]], "min");
		this.assertEquals(Synesthesia.parseChords("C6/9"), [[0, 4, 7, 9, 14]], "six-nine");
		this.assertEquals(Synesthesia.parseChords("C+"), [[0, 4, 8]], "plus is augmented");
		this.assertEquals(Synesthesia.parseChords("E7#5b9"), [[4, 8, 12, 14, 17]], "altered dominant");
	}

	test_chords_sus {
		this.assertEquals(Synesthesia.parseChords("Csus4"), [[0, 5, 7]], "the s of sus is not a sharp");
		this.assertEquals(Synesthesia.parseChords("C#sus4"), [[1, 6, 8]], "sharp root with sus");
	}

	test_chords_slashBass {
		this.assertEquals(Synesthesia.parseChords("C/E"), [[4, 7, 12]], "slash chord puts the bass note lowest");
	}

	test_chords_octave {
		this.assertEquals(Synesthesia.parseChords("Cm7_5"), [[0, 3, 7, 10]], "octave 5 is the pattern's own");
		this.assertEquals(Synesthesia.parseChords("Cm7_4"), [[-12, -9, -5, -2]], "octave 4 is one below");
		this.assertEquals(Synesthesia.parseChords("C/E_6"), [[16, 19, 24]], "octave after a slash bass");
	}

	test_chords_rests {
		this.assertEquals(Synesthesia.parseChords("Cm7 . G7 ~"),
			[[0, 3, 7, 10], \rest, [7, 11, 14, 17], \rest], "dot and tilde rest");
	}

	test_chords_arrayForm {
		this.assertEquals(Synesthesia.parseChords([\Cm7, [0, 4, 7], \rest]),
			[[0, 3, 7, 10], [0, 4, 7], \rest], "symbols resolve, note arrays pass through");
	}

	test_chords_unknownThrows {
		this.assertException({ Synesthesia.parseChords("Cm7 Xyz") }, Error, "an unreadable name is an error");
		this.assertException({ Synesthesia.parseChords("H7") }, Error, "so is a root that is not a note");
	}

	test_chordName {
		this.assertEquals(Synesthesia.chordName("Abmaj7"), "AbM7", "maj7 -> M7");
		this.assertEquals(Synesthesia.chordName("F#m7b5"), "Fsm7flat5", "# and b spelled out");
		this.assertEquals(Synesthesia.chordName("Cmaj9"), "Cmaj9", "maj9 is already in the table");
		this.assertEquals(Synesthesia.chordName("Cm7"), "Cm7", "table spelling is untouched");
	}

	test_chord_patternCarriesNotes {
		var evs = \pad.chord("G7 . Cm7", 4).asStream.nextN(3, Event.default);
		this.assertEquals(evs[0][\note], [7, 11, 14, 17], "the Pbind holds note numbers, not a symbol");
		this.assert(evs[1].isRest, "a rest in the string is a rest event");
		this.assertEquals(evs[2].use { ~midinote.value }, [60, 63, 67, 70], "middle register by default");
	}
}


// The ProxySpace guard (Synesthesia.guard). The first three tests need no server.
// The last boots a small private one, because the fault it covers only exists on a
// running server: see the comment on Synesthesia.guard.
TestSynesthesiaGuard : UnitTest {

	test_guard_installsOnce {
		var space = ProxySpace.new(Server(\synGuardA, NetAddr("127.0.0.1", 57151)));
		var first;
		this.assert(Synesthesia.guarded(space).not, "a new space is unguarded");
		Synesthesia.guard(space);
		first = space.dispatch;
		this.assert(Synesthesia.guarded(space), "guard marks the space");
		Synesthesia.guard(space);
		this.assert(space.dispatch === first, "guarding twice changes nothing");
		space.clear;
	}

	test_guard_keepsExistingDispatch {
		var space = ProxySpace.new(Server(\synGuardB, NetAddr("127.0.0.1", 57152)));
		var seen;
		space.dispatch = { |key| seen = key };
		Synesthesia.guard(space);
		space.put(\x, { 1 });
		this.assertEquals(seen, \x, "a dispatch that was already there still runs");
		space.clear;
	}

	test_guard_leavesAStoppedServerAlone {
		var space = ProxySpace.new(Server(\synGuardC, NetAddr("127.0.0.1", 57153)));
		Synesthesia.guard(space);
		space.put(\x, { 1 });
		this.assert(space.envir[\x].loaded.not, "nothing is marked loaded while the server is down");
		space.clear;
	}

	// ~r = 55; ~a reads ~r; ~r = 49 — all inside one bar. Unguarded, ~a never starts.
	test_guard_readerSurvivesReassignment {
		var server = Server(\synGuardD, NetAddr("127.0.0.1", 57154));
		var space, value, booted = false;
		server.options.numInputBusChannels = 0;
		server.waitForBoot { booted = true };
		block { |break| 100.do { if(booted) { break.value }; 0.1.wait } };
		this.assert(booted, "private server booted");
		if(booted) {
			space = ProxySpace.new(server);
			space.makeTempoClock(2);
			space.quant = 4;
			Synesthesia.guard(space);
			(space.clock.timeToNextBeat(4) / space.clock.tempo + 0.2).wait;   // start of a bar
			space.put(\r, { 55 });
			space.put(\a, { space[\r].kr(1) * 4 });
			space.put(\r, { 49 });
			this.assert(space.envir[\r].loaded, "an assigned proxy is marked loaded");
			(space.clock.timeToNextBeat(4) / space.clock.tempo + 0.5).wait;   // past the bar line
			space[\a].bus.get { |v| value = v };
			0.3.wait;
			this.assertFloatEquals(value ? 0, 196, "the reader is running and follows the new root", 1);
			space.clear;
			server.quit;
			0.3.wait;
		};
	}
}
