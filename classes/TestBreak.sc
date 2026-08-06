// Unit tests for the Break registry's pure functions — tempo inference and the
// slice-sequence parser. No server and no audio files required.
// Run in the IDE with: TestBreak.run

TestBreak : UnitTest {

	// ----- tempo inference -----------------------------------------------

	test_infer_filenameTag {
		var g = Break.inferTempo("probe175bpm.wav", "breaks", 4 * 60 / 175);
		this.assertEquals(g[\bpm], 175, "bpm read from the filename");
		this.assertEquals(g[\beats], 4, "beats derived from bpm and duration");
		this.assertEquals(g[\src], \filename, "source recorded as filename");
	}

	test_infer_ignoresUnrelatedDigits {
		// freesound-style names carry long id numbers that must not read as tempos
		var g = Break.inferTempo("250093__amfe__175bpm-amen-punchy-loop.wav",
			"set1", 4 * 60 / 175);
		this.assertEquals(g[\bpm], 175, "the bpm-tagged number wins over the id");
	}

	test_infer_bpmPrefix {
		var g = Break.inferTempo("amen_bpm140.wav", "x", 8 * 60 / 140);
		this.assertEquals(g[\bpm], 140, "bpm140 prefix form");
		this.assertEquals(g[\beats], 8, "8 beats");
	}

	test_infer_folderTag {
		// John's own older convention: the tempo lives in the folder name
		var g = Break.inferTempo("270313__dee-vigga__dv-amen.wav", "breaks175",
			4 * 60 / 175);
		this.assertEquals(g[\bpm], 175, "bpm read from the folder");
		this.assertEquals(g[\src], \folder, "source recorded as folder");
	}

	test_infer_durationHeuristic {
		var g = Break.inferTempo("untagged_loop.wav", "breaks", 8 * 60 / 140);
		this.assertEquals(g[\bpm].round, 140, "tempo recovered from duration alone");
		this.assertEquals(g[\beats], 8, "8 beats");
		this.assertEquals(g[\src], \duration, "source recorded as duration");
	}

	test_infer_hintBreaksTies {
		var g = Break.inferTempo("loop_90.wav", "breaks", 4 * 60 / 90);
		this.assertEquals(g[\bpm].round, 90, "a bare number disambiguates the heuristic");
		this.assertEquals(g[\beats], 4, "4 beats");
	}

	test_infer_alwaysPlausible {
		// whatever we are handed, the guess has to be usable
		[0.5, 1.0, 2.0, 2.5, 3.7, 6.0].do { |dur|
			var g = Break.inferTempo("mystery.wav", "breaks", dur);
			this.assert((g[\bpm] >= 40) and: { g[\bpm] <= 260 },
				"bpm in a sane range for a " ++ dur ++ "s loop (got " ++ g[\bpm] ++ ")");
			this.assert(g[\beats] >= 1, "at least one beat for a " ++ dur ++ "s loop");
		};
	}

	test_beatsFor {
		this.assertEquals(Break.beatsFor(175, 4 * 60 / 175), 4, "exact fit");
		this.assertEquals(Break.beatsFor(90, 8 * 60 / 90), 8, "exact fit, 8 beats");
		this.assert(Break.beatsFor(175, 0.01) >= 1, "never rounds down to zero beats");
	}

	// ----- bpm tag scanning ----------------------------------------------

	test_bpmTags_tagging {
		var tags = Break.bpmTags("amen175bpm_x");
		this.assertEquals(tags.size, 1, "one digit run");
		this.assert(tags[0][\tagged], "adjacent to bpm, so tagged");
	}

	test_bpmTags_untagged {
		var tags = Break.bpmTags("250093__amfe__loop");
		this.assertEquals(tags.size, 1, "one digit run");
		this.assert(tags[0][\tagged].not, "not adjacent to bpm");
	}

	test_bpmTags_separators {
		this.assert(Break.bpmTags("loop 175 bpm.wav")[0][\tagged],
			"spaces between number and bpm are allowed");
		this.assert(Break.bpmTags("loop-175-BPM.wav")[0][\tagged],
			"dashes and upper case are allowed");
	}

	test_bpmTags_noDigits {
		this.assertEquals(Break.bpmTags("amen.wav"), [], "no digit runs");
	}

	// ----- slice-sequence parser -----------------------------------------

	test_parseSeq_digits {
		this.assertEquals(Break.parseSeq("0325"), [0, 3, 2, 5], "digits are slice indices");
	}

	test_parseSeq_rests {
		this.assertEquals(Break.parseSeq("0.3~"), [0, -1, 3, -1],
			"both . and ~ are rests");
	}

	test_parseSeq_letters {
		this.assertEquals(Break.parseSeq("9abz"), [9, 10, 11, 35],
			"letters carry on past 9, base 36");
	}

	test_parseSeq_visualCharsIgnored {
		this.assertEquals(Break.parseSeq("01 | 23"), [0, 1, 2, 3],
			"spaces and bars are grouping only");
	}

	test_parseSeq_arrayForm {
		this.assertEquals(Break.parseSeq([0, nil, 3, -5]), [0, -1, 3, -1],
			"nil and negatives are rests");
		this.assertEquals(Break.parseSeq([0, Rest(), 2]), [0, -1, 2],
			"a Rest is a rest");
	}

	test_parseSeq_empty {
		this.assertEquals(Break.parseSeq(""), [], "empty string");
		this.assertEquals(Break.parseSeq(nil), [], "nil");
	}

	// ----- keys ----------------------------------------------------------

	test_keyFor {
		this.assertEquals(Break.prKeyFor("amen.wav"), \amen, "simple name");
		this.assertEquals(Break.prKeyFor("Amen Break 2.WAV"), \amen_break_2,
			"spaces collapse to underscores, lower case");
		this.assertEquals(Break.prKeyFor("175bpm.wav"), \b175bpm,
			"a leading digit gets a prefix so the symbol stays usable");
	}

	// ----- file discovery -------------------------------------------------

	test_audioExtensionMatching {
		// Regression: Collection:includes tests IDENTITY, so ["wav"].includes("wav")
		// is false and a String-compared extension filter silently matches nothing.
		var dir = PathName(Platform.resourceDir +/+ "sounds");
		var found = Break.prAudioFiles(dir);
		this.assert(found.size > 0,
			"finds the .wav/.aiff files SuperCollider ships in "
			++ dir.fullPath ++ " (got " ++ found.size ++ ")");
		this.assert(
			found.every { |f| ["wav", "aiff", "aif", "flac"]
				.includesEqual(f.extension.toLower) },
			"and everything it returns really is an audio file");
	}

	// ----- the chop brush -------------------------------------------------

	test_chop_buildsPattern {
		var key = \testChopEntry;
		var pat;
		Break.prAdd((
			key: key, buf: Buffer.new(Server.default, 44100, 1, 0),
			bpm: 175, beats: 4, dur: 4 * 60 / 175,
			file: "test.wav", folder: nil, src: \test
		));
		pat = key.chop("0325");
		this.assert(pat.isKindOf(Pbind), "chop returns a plain Pbind");
		this.assertEquals(key.bpm, 175, "Symbol bpm getter");
		this.assertEquals(key.len, 4, "Symbol len getter");
		Break.all.removeAt(key);
	}
}
