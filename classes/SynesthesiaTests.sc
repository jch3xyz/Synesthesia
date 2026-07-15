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
}
