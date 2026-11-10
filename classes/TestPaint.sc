// Unit tests for the painting register (pure: no server).
// Run in the IDE with: TestPaint.run

TestPaint : UnitTest {

	var def;

	setUp {
		// a voice with the contract's default colour, for moves on bare patterns
		def = SynthDef(\synPaintTest, { |out = 0, freq = 220, hue = 0.05, sat = 0.65, val = 0.5|
			Out.ar(out, Silent.ar)
		}).add;
	}

	// pull n events from a pattern
	events { |pattern, n = 8| ^pattern.asStream.nextN(n, Event.default) }

	// ---- colours ----------------------------------------------------------

	test_palette_tubesAreInRange {
		this.assert(Paint.palette.size >= 8, "a handful of tubes");
		Paint.palette.keysValuesDo { |name, c|
			this.assert(c[\hue].inclusivelyBetween(0, 1) and: { c[\sat].inclusivelyBetween(0, 1) }
				and: { c[\val].inclusivelyBetween(0, 1) } and: { c[\spread] > 0 },
				name.asString ++ " is a colour in range with a spread");
			this.assertEquals(c[\name], name, name.asString ++ " knows its name");
		};
	}

	test_colors_sortedNames {
		var names = Paint.colors;
		this.assertEquals(names, names.copy.sort, "sorted");
		this.assert(names.includes(\orange) and: { names.includes(\grey) }, "orange and grey are tubes");
	}

	test_color_fromName {
		var c = Paint.color(\orange);
		this.assertFloatEquals(c[\hue], 0.08, "orange sits at the wheel's orange", 1e-6);
		this.assertEquals(Paint.color("orange"), c, "a String reads the same tube");
	}

	test_color_unknownNameThrows {
		this.assertException({ Paint.color(\mauve) }, Error, "an unknown tube is an error");
		try { Paint.color(\mauve) } { |err|
			this.assert(err.errorString.contains("mauve") and: { err.errorString.contains("orange") },
				"the error names the tube and lists the palette");
		};
	}

	test_color_fromNumbersArrayEventAndColor {
		var a = Paint.color(0.3, 0.7, 0.4);
		var b = Paint.color([0.3, 0.7, 0.4]);
		var e = Paint.color((hue: 0.3, sat: 0.7, val: 0.4));
		var k = Paint.color(Color.new(1, 0.5, 0));
		this.assertEquals([a[\hue], a[\sat], a[\val]], [0.3, 0.7, 0.4], "numbers");
		this.assertEquals([b[\hue], b[\sat], b[\val]], [0.3, 0.7, 0.4], "array");
		this.assertEquals([e[\hue], e[\sat], e[\val]], [0.3, 0.7, 0.4], "event");
		this.assertEquals(a[\spread], 0.03, "default spread");
		this.assertFloatEquals(k[\hue], 1/12, "a Color arrives through HSV", 1e-6);
		this.assertEquals(Paint.color(1.25)[\hue], 0.25, "hue wraps");
		this.assertEquals(Paint.color(0, 1.4, -1)[\sat], 1, "sat clips");
		this.assertEquals(Paint.color(0, 1.4, -1)[\val], 0, "val clips");
		this.assertException({ Paint.color(nil) }, Error, "nothing is not a colour");
	}

	test_mix_midpointAndEnds {
		var m = Paint.mix(\orange, \green, 0.5);   // 0.08 and 0.33: a quarter turn apart
		var o = Paint.color(\orange), g = Paint.color(\green);
		this.assertFloatEquals(m[\hue], (o[\hue] + g[\hue]) / 2, "hue midway", 1e-6);
		this.assertFloatEquals(m[\sat], (o[\sat] + g[\sat]) / 2, "sat midway", 1e-6);
		this.assertFloatEquals(m[\val], (o[\val] + g[\val]) / 2, "val midway", 1e-6);
		this.assertEquals(m[\name], \orange_green, "a mix of tubes is named after both");
		this.assertFloatEquals(Paint.mix(\orange, \green, 0)[\hue], o[\hue], "x = 0 is the first", 1e-6);
		this.assertFloatEquals(Paint.mix(\orange, \green, 1)[\hue], g[\hue], "x = 1 is the second", 1e-6);
	}

	test_mix_hueTakesTheShortWayRound {
		var m = Paint.mix(\red, \magenta, 0.5);   // 0.0 and 0.85: through the purples
		this.assertFloatEquals(m[\hue], 0.925, "red and magenta meet at 0.925, not 0.425", 1e-6);
		this.assertFloatEquals(Paint.mix([0.9], [0.1], 0.5)[\hue], 0, "0.9 and 0.1 meet at 0", 1e-6);
		// orange (0.08) and blue (0.62) are 0.54 apart the green way, 0.46 the red way
		this.assertFloatEquals(Paint.mix(\orange, \blue, 0.5)[\hue], 0.85, "orange and blue meet through red", 1e-6);
		this.assertFloatEquals(Paint.mix(\orange, \blue, 1)[\hue], 0.62, "and still arrive", 1e-6);
	}

	test_colorKeys_sampleWithinTheSpread {
		var c = Paint.color(0.5, 0.5, 0.5, 0.1);
		var evs = this.events(Pbind(*Paint.colorKeys(c)), 60);
		this.assert(evs.every { |e| (e[\hue] - 0.5).abs <= (0.1 + 1e-9) }, "hue within ± spread");
		this.assert(evs.every { |e| (e[\sat] - 0.5).abs <= (0.05 + 1e-9) }, "sat within ± spread/2");
		this.assert(evs.every { |e| (e[\val] - 0.5).abs <= (0.05 + 1e-9) }, "val within ± spread/2");
		this.assert(evs.collect(_[\hue]).asSet.size > 1, "and it varies");
		this.assertEquals(evs.first[\syn_color], \custom, "an ad hoc colour is tagged custom");
	}

	test_colorKeys_hueWrapsAtTheSeam {
		var evs = this.events(Pbind(*Paint.colorKeys(Paint.color(0, 0.5, 0.5, 0.05))), 60);
		this.assert(evs.every { |e| e[\hue].inclusivelyBetween(0, 1) }, "red's spread stays on the wheel");
		this.assert(evs.any { |e| e[\hue] > 0.9 }, "some samples cross the seam");
	}

	// ---- tools ------------------------------------------------------------

	test_strokeKind {
		this.assertEquals(Paint.strokeKind("Cm7 G7"), \chords, "chord names");
		this.assertEquals(Paint.strokeKind("C/E ."), \chords, "a bass-note chord");
		this.assertEquals(Paint.strokeKind("x..X 0.2."), \steps, "a step string, digits and all");
		this.assertEquals(Paint.strokeKind("0 3 5 [0 2 4]"), \degs, "degrees");
		this.assertEquals(Paint.strokeKind("-2 ."), \degs, "a negative degree and a rest");
		this.assertEquals(Paint.strokeKind([0, 3, 5]), \degs, "an array of degrees");
		this.assertEquals(Paint.strokeKind([\Cm7, \G7]), \chords, "an array of chord symbols");
	}

	test_brush_isATaggedPbindWithColour {
		var p = \orange.brush("0 3", 2), evs;
		this.assert(p.isKindOf(Pbind), "a plain Pbind");
		evs = this.events(p, 4);
		this.assertEquals(evs.first[\instrument], \brush, "the brush voice");
		this.assertEquals(evs.first[\syn_brush], \brush, "tagged with the tool");
		this.assertEquals(evs.first[\syn_material], \brush, "and the voice");
		this.assertEquals(evs.first[\syn_color], \orange, "and the tube");
		this.assertEquals(evs.collect(_[\degree]), [0, 3, 0, 3], "degrees ride unchanged");
		this.assertEquals(evs.first[\dur], 2, "dur");
		this.assertEquals(evs.first[\legato], 0.95, "legato");
		this.assert((evs.first[\hue] - 0.08).abs <= 0.03, "hue near orange");
		this.assert(evs.first[\sat].notNil and: { evs.first[\val].notNil }, "sat and val set");
	}

	test_brush_chordNamesAndSteps {
		var evs = this.events(\blue.brush("Cm7 G7"), 2);
		this.assertEquals(evs[0][\note], [0, 3, 7, 10], "Cm7 as notes");
		this.assertEquals(evs[1][\note], [7, 11, 14, 17], "G7 as notes");
		evs = this.events(\blue.brush("x.x.", 0.5), 2);
		this.assertEquals(evs.collect(_[\dur]), [1, 1], "a step string sets the rhythm");
		this.assertEquals(evs.first[\degree], 0, "on one note");
	}

	test_tools_ownTheirVoices {
		this.assertEquals(this.events(\red.mark("0")).first[\instrument], \mark, "mark");
		this.assertEquals(this.events(\red.dab).first[\instrument], \mark, "dab is the mark voice");
		this.assertEquals(this.events(\red.drip).first[\instrument], \mark, "drip is the mark voice");
		this.assertEquals(this.events(\red.spray(55)).first[\instrument], \spray, "spray");
		this.assertEquals(this.events(\red.wash(55)).first[\instrument], \wash, "wash");
		this.assertEquals(this.events(\red.dab).first[\syn_brush], \dab, "dab is tagged as dab");
		this.assertEquals(this.events(\red.wash(55)).first[\syn_brush], \wash, "wash is tagged as wash");
	}

	test_dab_defaultsToATouchPerBar {
		var evs = this.events(\red.dab, 2);
		this.assertEquals(evs.collect(_[\dur]), [4, 4], "one touch every four beats");
		this.assertEquals(this.events(\red.dab("x.x.", 0.25), 2).collect(_[\dur]), [0.5, 0.5], "or any step string");
	}

	test_drip_timingIsIrregular {
		var evs = this.events(\violet.drip(2, "0 3 5"), 200);
		var durs = evs.collect(_[\dur]);
		this.assert(durs.asSet.size > 10, "durations vary");
		this.assert(durs.mean.inclusivelyBetween(0.3, 0.8), "about two touches a beat: mean " ++ durs.mean);
		this.assert(evs.collect(_[\degree]).asSet.asArray.sort == [0, 3, 5], "notes drawn from the set");
	}

	test_spray_rootForms {
		this.assertEquals(this.events(\grey.spray(110)).first[\freq], 110, "a number is the root");
		this.assertEquals(this.events(\grey.spray(Pseq([110, 220], inf)), 2).collect(_[\freq]), [110, 220],
			"a pattern is the root");
		this.assertEquals(this.events(\grey.spray).first[\freq], 55, "no root and no canvas: 55 Hz");
		this.assertEquals(this.events(\grey.spray(55, dens: 30)).first[\dens], 30, "density");
	}

	test_mixWord {
		var m = \orange.mix(\blue, 0.25);
		this.assertEquals(m, Paint.mix(\orange, \blue, 0.25), "the word is the class method");
		this.assertEquals(this.events(m.brush("0")).first[\syn_color], \orange_blue, "a mix paints a stroke");
	}

	test_voiceFor_unknownToolThrows {
		this.assertException({ Paint.voiceFor(\knife) }, Error, "no knife yet");
	}

	// ---- moves ------------------------------------------------------------

	test_moved_clipsAndWraps {
		this.assertEquals(Paint.moved(0.9, \val, 0.2), 1, "val clips at 1");
		this.assertEquals(Paint.moved(0.1, \sat, -0.2), 0, "sat clips at 0");
		this.assertFloatEquals(Paint.moved(0.95, \hue, 0.1), 0.05, "hue wraps", 1e-6);
		this.assertFloatEquals(Paint.moved(0.62, \hue, 0.1, 0.08), 0.72, "blue goes warmer through magenta", 1e-6);
		this.assertFloatEquals(Paint.moved(0.2, \hue, 0.1, 0.08), 0.1, "yellow goes warmer toward orange", 1e-6);
		this.assertFloatEquals(Paint.moved(0.1, \hue, 0.1, 0.08), 0.08, "and never past the pole", 1e-6);
		this.assertFloatEquals(Paint.moved(0.08, \hue, 0.1, 0.08), 0.08, "at the pole it stays", 1e-6);
	}

	test_move_stepsFromTheCarriedValue {
		var evs = this.events(\orange.brush("0").darker(0.2), 20);
		var val = Paint.color(\orange)[\val];
		this.assert(evs.every { |e| (e[\val] - (val - 0.2)).abs <= 0.016 }, "val is the tube's minus 0.2, within the spread");
		evs = this.events(\orange.brush("0").darker(0.2).darker(0.2), 5);
		this.assert(evs.every { |e| (e[\val] - (val - 0.4)).abs <= 0.016 }, "moves compound");
	}

	test_move_usesTheVoiceDefaultOnABarePattern {
		var p = Pbind(\instrument, \synPaintTest, \degree, 0, \dur, 1);
		this.assertFloatEquals(this.events(p.darker(0.2)).first[\val], 0.3, "0.5 from the SynthDesc, minus 0.2", 1e-6);
		this.assertFloatEquals(this.events(p.saturate(0.1)).first[\sat], 0.75, "0.65 plus 0.1", 1e-6);
		this.assertFloatEquals(this.events(p.cooler(0.1)).first[\hue], 0.95, "0.05 toward 0.62 the short way: through red", 1e-6);
		this.assertFloatEquals(this.events(Pbind(\instrument, \nothere, \dur, 1).lighter(0.1)).first[\val], 0.6,
			"an unknown voice falls back to the contract's 0.5", 1e-6);
	}

	test_paint_recoloursAPattern {
		var evs = this.events(\orange.brush("0").paint(\blue), 10);
		this.assert(evs.every { |e| (e[\hue] - 0.62).abs <= 0.03 }, "blue now");
		this.assertEquals(evs.first[\syn_color], \blue, "retagged");
	}

	test_moves_keepThePatternPlain {
		var p = \orange.brush("0 3").darker.fast(2);
		this.assert(p.isKindOf(Pattern), "still a pattern after a move and a transform");
		this.assertEquals(this.events(p).first[\dur], 2, "fast(2) halved the brush's 4 beats");
	}

	test_proxy_movesASynthSourceControl {
		var space = ProxySpace.new(Server(\synPaintA, NetAddr("127.0.0.1", 57155)));
		var px;
		space.put(\x, { |hue = 0.3, sat = 0.6, val = 0.5| Silent.ar });
		px = space.envir[\x];
		px.darker(0.2);
		this.assertFloatEquals(px.get(\val), 0.3, "val moved from the control's default", 1e-6);
		px.darker(0.2);
		this.assertFloatEquals(px.get(\val), 0.1, "and again from the moved value", 1e-6);
		px.warmer(0.1);
		this.assertFloatEquals(px.get(\hue), 0.2, "hue toward the warm pole", 1e-6);
		px.paint(\blue);
		this.assertFloatEquals(px.get(\hue), 0.62, "paint sets the tube's hue", 1e-6);
		this.assertFloatEquals(px.get(\val), 0.4, "and val", 1e-6);
		space.clear;
	}

	test_proxy_movesAPatternSource {
		var space = ProxySpace.new(Server(\synPaintB, NetAddr("127.0.0.1", 57156)));
		var px, evs;
		space.put(\p, \orange.brush("0"));
		px = space.envir[\p];
		px.darker(0.2);
		this.assert(px.source.isKindOf(Pattern), "the source is still a pattern");
		evs = this.events(px.source, 5);
		this.assert(evs.every { |e| (e[\val] - 0.4).abs <= 0.016 }, "and it is darker");
		space.clear;
	}
}
