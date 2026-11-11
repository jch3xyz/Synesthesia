// Unit tests for Broadcast's pure parts: address formatting, the payload each brush
// produces, the stem table's index stability, and kr-set discovery and diffing.
// No server, no receiver and no ProxySpace required.
// Run in the IDE with: TestBroadcast.run

TestBroadcast : UnitTest {

	// ----- addresses -------------------------------------------------------

	test_address {
		this.assertEquals(Broadcast.address("/syn", "kr", \k), "/syn/kr/k", "root, family, key");
		this.assertEquals(Broadcast.address("/syn", "tempo"), "/syn/tempo", "a bare family");
		this.assertEquals(Broadcast.address("/two", "hit", \bplay), "/two/hit/bplay",
			"the root is a setting, so two machines can coexist");
		this.assertEquals(Broadcast.hitAddress(\k, "/syn"), "/syn/hit/k", "hit");
		this.assertEquals(Broadcast.noteAddress(\ping, "/syn"), "/syn/note/ping", "note");
		this.assertEquals(Broadcast.krAddress(\k_env, "/syn"), "/syn/kr/k_env", "kr");
		this.assertEquals(Broadcast.krnAddress(\syn_sig1, "/syn"), "/syn/krn/syn_sig1", "krn");
		this.assertEquals(Broadcast.goneAddress(\a, "/syn"), "/syn/gone/a", "gone");
		this.assertEquals(Broadcast.paintAddress(\brush, "/syn"), "/syn/paint/brush", "paint");
	}

	test_eventBase {
		var hit = Event.default.putAll((syn_brush: \hits, syn_material: \k));
		var deg = Event.default.putAll((syn_brush: \deg, syn_material: \ping));
		var paint = Event.default.putAll((syn_brush: \dab, syn_material: \mark));
		this.assertEquals(Broadcast.eventBase(hit, "/syn"), "/syn/hit/k", "a hit hangs under /hit/<folder>");
		this.assertEquals(Broadcast.eventBase(deg, "/syn"), "/syn/note/ping", "a note under /note/<synthdef>");
		this.assertEquals(Broadcast.eventBase(paint, "/syn"), "/syn/paint/dab", "a stroke under /paint/<tool>");
		this.assert(Broadcast.eventBase(Event.default, "/syn").isNil, "an event no brush made has none");
	}

	test_sanitize {
		this.assertEquals(Broadcast.sanitize("Essential Kicks"), "Essential_Kicks",
			"a space would break the OSC address");
		this.assertEquals(Broadcast.sanitize("gold_rush-2.wav"), "gold_rush-2.wav",
			"underscore, dash and dot survive");
		this.assertEquals(Broadcast.sanitize("a/b*c"), "a_b_c", "pattern characters become _");
		this.assertEquals(Broadcast.address("/syn", "hit", "my kit"), "/syn/hit/my_kit",
			"applied inside address");
	}

	test_hiddenPrefix {
		this.assert(Broadcast.isHidden(\syn_bc_kr), "the kr tap is hidden");
		this.assert(Broadcast.isHidden(\syn_bc_stem3), "a stem tap is hidden");
		this.assert(Broadcast.isHidden(\k).not, "a performer's key is not");
		this.assert(Broadcast.isHidden(\syn_sig1).not,
			"other syn_ keys (mapExp, pad) are real signals and stay visible");
	}

	// ----- payload shapes: one value per address ----------------------------

	// the messages as a dictionary address -> args, for assertions by name
	byAddress { |msgs| ^Dictionary.newFrom(msgs.collect { |m| [m[0], m.drop(1)] }.flatten(1)) }

	test_payload_hits {
		var ev = Event.default.putAll((syn_brush: \hits, syn_material: \k, amp: 1.4,
			syn_index: 2, dur: 0.5));
		var msgs = Broadcast.eventMessages(ev, "/syn", 7);
		this.assertEquals(msgs, [
			["/syn/hit/k/amp", 1.4],
			["/syn/hit/k/index", 2],
			["/syn/hit/k/dur", 0.5],
			["/syn/hit/k/count", 7]
		], "hits: amp (post-accent), the sample variant, dur, then the count");
		this.assertEquals(Broadcast.eventMessages(ev, "/syn").size, 3, "no count when none is given");
	}

	test_payload_chop {
		var ev = Event.default.putAll((syn_brush: \chop, syn_material: \amen, amp: 0.5,
			slice: 3, syn_index: 3, dur: 0.25));
		var m = this.byAddress(Broadcast.eventMessages(ev, "/syn", 1));
		this.assertEquals(m["/syn/hit/amen/index"], [3], "chop: the break is the material, the slice is the index");
		this.assertEquals(m["/syn/hit/amen/count"], [1], "counted like a hit");
	}

	test_payload_deg {
		var ev = Event.default.putAll((syn_brush: \deg, syn_material: \ping, amp: 0.3,
			degree: 3, dur: 0.5));
		var msgs = Broadcast.eventMessages(ev, "/syn", 2);
		var m = this.byAddress(msgs);
		this.assertEquals(m["/syn/note/ping/amp"], [0.3], "amp");
		this.assertEquals(m["/syn/note/ping/pitch"], [65], "pitch is the MIDI note (degree 3 of the default major scale)");
		this.assertEquals(m["/syn/note/ping/notes"], [65], "notes: every note, here one");
		this.assertEquals(m["/syn/note/ping/size"], [1], "size");
		this.assertEquals(m["/syn/note/ping/degree"], [3], "degree: what was written");
		this.assertEquals(m["/syn/note/ping/degrees"], [3], "degrees");
		this.assertEquals(m["/syn/note/ping/dur"], [0.5], "dur in beats");
		this.assertEquals(msgs.last, ["/syn/note/ping/count", 2], "count comes last");
	}

	test_payload_degChord {
		var ev = Event.default.putAll((syn_brush: \deg, syn_material: \ping, amp: 0.3,
			degree: [0, 2, 4], dur: 1));
		var m = this.byAddress(Broadcast.eventMessages(ev, "/syn", 1));
		this.assertEquals(m["/syn/note/ping/notes"], [60, 64, 67], "a bracket chord: all its MIDI notes in one message");
		this.assertEquals(m["/syn/note/ping/pitch"], [60], "pitch is the lowest");
		this.assertEquals(m["/syn/note/ping/size"], [3], "three notes");
		this.assertEquals(m["/syn/note/ping/degrees"], [0, 2, 4], "and the degrees as written");
		this.assertEquals(m["/syn/note/ping/degree"], [0], "the lowest degree");
	}

	test_payload_chord {
		// what ChordSymbol2 leaves in \note once a chord symbol has streamed: note numbers
		var ev = Event.default.putAll((syn_brush: \chord, syn_material: \pad, amp: 0.2,
			note: [0, 3, 7], octave: 5, dur: 4));
		var m = this.byAddress(Broadcast.eventMessages(ev, "/syn", 1));
		this.assertEquals(m["/syn/note/pad/notes"], [60.0, 63.0, 67.0], "chord: the MIDI notes, by the Event's own pitch chain");
		this.assertEquals(m["/syn/note/pad/pitch"], [60.0], "pitch is the lowest");
		this.assertEquals(m["/syn/note/pad/size"], [3], "size");
		this.assert(m["/syn/note/pad/degree"].isNil, "a chord has no degree message");
	}

	test_payload_restsAndStrangers {
		var rest = Event.default.putAll((syn_brush: \hits, syn_material: \k, amp: 1, dur: Rest(1)));
		var plain = Event.default.putAll((instrument: \bplay, amp: 1, dur: 1));
		this.assert(Broadcast.eventMessages(rest, "/syn").isNil, "a rest produces nothing");
		this.assert(Broadcast.eventMessages(plain, "/syn").isNil,
			"an event that is not from a brush produces nothing");
	}

	test_payload_customRoot {
		var ev = Event.default.putAll((syn_brush: \hits, syn_material: \k, amp: 1, syn_index: 0, dur: 1));
		this.assertEquals(Broadcast.eventMessages(ev, "/stage2")[0][0], "/stage2/hit/k/amp",
			"the root travels into every event address");
	}

	test_counts {
		var a = Broadcast.nextCount("/syn/hit/test_a");
		var b = Broadcast.nextCount("/syn/hit/test_a");
		var c = Broadcast.nextCount("/syn/hit/test_b");
		this.assertEquals(b, a + 1, "each event of a material counts up by one");
		this.assertEquals(c, 1, "another material counts on its own");
	}

	// ----- the phase streams -----------------------------------------------

	test_phaseValues {
		var v = Broadcast.phaseValues(1.5, 4);
		this.assertEquals(Broadcast.phases.collect(_.key), [\half, \quarter, \eighth, \sixteenth], "the note values");
		this.assertFloatEquals(v[0], 0.375, "bar: 1.5 of 4 beats", 1e-9);
		this.assertFloatEquals(v[1], 0.75, "half: 1.5 of 2 beats", 1e-9);
		this.assertFloatEquals(v[2], 0.5, "quarter: the fraction of the beat", 1e-9);
		this.assertFloatEquals(v[3], 0, "eighth: 1.5 beats is exactly three eighths", 1e-9);
		this.assertFloatEquals(v[4], 0, "sixteenth: and six sixteenths", 1e-9);
		v = Broadcast.phaseValues(3.9, 4);
		this.assert(v.every { |x| x.inclusivelyBetween(0, 1) }, "all phases are 0..1");
		this.assertFloatEquals(v[4], 0.6, "sixteenth at beat 3.9: 15.6 sixteenths in", 1e-9);
	}

	// ----- the brushes carry the tags --------------------------------------

	test_brushesAreTagged {
		var interp = thisProcess.interpreter;
		var saved = interp.d;
		var hits, euclid, deg, chord, ev, stream;
		// plain integers stand in for Buffers: hits only clipAt()s them
		interp.d = Dictionary["k" -> [100, 101, 102]];
		protect {
			hits = \k.hits("x.2.");
			this.assert(hits.isKindOf(Pbind), "hits is still a plain Pbind");
			stream = hits.asStream;
			ev = stream.next(Event.default);
			this.assertEquals(ev[\syn_brush], \hits, "hits tags its word");
			this.assertEquals(ev[\syn_material], \k, "hits tags the folder");
			this.assertEquals(ev[\syn_index], 0, "an x hit is variant 0 (the buf default)");
			ev = stream.next(Event.default);
			this.assertEquals(ev[\syn_index], 2, "a digit hit tags its variant");
			this.assertEquals(ev[\buf], 102, "...and plays it");

			euclid = \k.hits(3, 8, buf: 1);
			ev = euclid.asStream.next(Event.default);
			this.assertEquals([ev[\syn_brush], ev[\syn_material], ev[\syn_index]], [\hits, \k, 1],
				"the euclid form is tagged the same way");
		} {
			interp.d = saved;
		};

		deg = \ping.deg("0 3", 0.5);
		ev = deg.asStream.next(Event.default);
		this.assertEquals([ev[\syn_brush], ev[\syn_material]], [\deg, \ping], "deg tags the SynthDef");
		this.assertEquals(Broadcast.eventMessages(ev, "/syn")[0], ["/syn/note/ping/amp", 0.3],
			"and the tagged event yields note messages end to end");

		chord = \pad.chord("Cm7", 4);
		ev = chord.asStream.next(Event.default);
		this.assertEquals([ev[\syn_brush], ev[\syn_material]], [\chord, \pad], "chord tags the SynthDef");
		this.assert(ev[\note].isArray, "the chord name became note numbers");
		this.assertEquals(this.byAddress(Broadcast.eventMessages(ev, "/syn"))["/syn/note/pad/notes"].size, 4,
			"a four-note chord is one notes message with four notes");
	}

	// ----- stem index stability --------------------------------------------

	test_stemTable_assignAndKeep {
		var t = BroadcastStemTable(3);
		var a = Object.new, b = Object.new;
		this.assertEquals(t.keys[0], \main, "stem 0 is always the main mix");
		this.assertEquals(t.assign(a, \k), 1, "first proxy takes stem 1");
		this.assertEquals(t.assign(b, \a), 2, "second takes stem 2");
		this.assertEquals(t.assign(a, \k), 1, "asking again returns the same index");
		this.assertEquals(t.indexOf(b), 2, "lookup by proxy");
		this.assertEquals(t.keyOf(b), \a, "key by proxy");
		this.assertEquals(t.keys, [\main, \k, \a, nil], "the table as published");
	}

	test_stemTable_releaseAndReuse {
		var t = BroadcastStemTable(2);
		var a = Object.new, b = Object.new, c = Object.new;
		t.assign(a, \k); t.assign(b, \a);
		this.assert(t.assign(c, \m).isNil, "a full table assigns nothing");
		this.assertEquals(t.release(a), 1, "release hands the index back");
		this.assert(t.indexOf(a).isNil, "...and forgets the proxy");
		this.assertEquals(t.assign(c, \m), 1, "a new proxy may reuse a released index");
		this.assertEquals(t.keys, [\main, \m, \a], "table reflects the reuse");
	}

	test_stemDecision {
		// hasIndex, hasSource, isAudio, monitoring
		this.assertEquals(Broadcast.stemDecision(false, true, true, true), \assign,
			"an audible audio proxy with no stem gets one");
		this.assertEquals(Broadcast.stemDecision(true, true, true, false), \keep,
			".stop (monitor off, source kept): the stem index survives");
		this.assertEquals(Broadcast.stemDecision(true, true, true, true), \keep,
			".play again: same index");
		this.assertEquals(Broadcast.stemDecision(true, true, true, false), \keep,
			"a source swap keeps the source non-nil, so it keeps the index");
		this.assertEquals(Broadcast.stemDecision(true, false, false, false), \release,
			".clear (source nil) releases the index");
		this.assertEquals(Broadcast.stemDecision(false, true, true, false), \ignore,
			"an audio proxy nobody has played yet gets no stem");
		this.assertEquals(Broadcast.stemDecision(false, true, false, false), \ignore,
			"a control proxy never gets a stem");
		this.assertEquals(Broadcast.stemDecision(false, false, false, false), \ignore,
			"an empty proxy with no stem is nothing to do");
	}

	// ----- kr-set discovery and diffing ------------------------------------

	test_krKeysOf {
		var envir = Environment.new;
		var server = Server.default;
		var mk = { |fn| var pr = NodeProxy.new(server); pr.source = fn; pr };
		envir[\r] = mk.({ DC.kr(55) });
		envir[\k_env] = mk.({ DC.kr(0) });
		envir[\a] = mk.({ DC.ar(0) });                 // audio: not continuous
		envir[\syn_bc_kr] = mk.({ DC.kr(0) });          // hidden: never reported
		envir[\empty] = NodeProxy.new(server);          // no source: nothing to tap
		this.assertEquals(Broadcast.krKeysOf(envir), [\k_env, \r],
			"control-rate proxies with sources, sorted, hidden and audio ones left out");
		envir.do { |pr| pr.clear };
	}

	test_krDiff {
		var d = Broadcast.krDiff([\k_env, \r], [\r, \syn_sig1]);
		this.assertEquals(d[\added], [\syn_sig1], "new keys");
		this.assertEquals(d[\gone], [\k_env], "keys whose proxy went away");
		d = Broadcast.krDiff([\r], [\r]);
		this.assert(d[\added].isEmpty and: { d[\gone].isEmpty }, "same set: nothing to do");
	}

	// ----- the receiver address --------------------------------------------

	test_resolveAddr {
		var a = Broadcast.resolveAddr("127.0.0.1:9100");
		this.assertEquals([a.ip, a.port], ["127.0.0.1", 9100], "host:port string");
		a = Broadcast.resolveAddr("127.0.0.1");
		this.assertEquals(a.port, 9000, "the port defaults to 9000");
		a = Broadcast.resolveAddr(["127.0.0.1", 9200]);
		this.assertEquals(a.port, 9200, "an array works too");
		a = Broadcast.resolveAddr(NetAddr("127.0.0.1", 9300));
		this.assertEquals(a.port, 9300, "a NetAddr passes through");
		this.assert(Broadcast.resolveAddr("no.such.host.invalid").isNil,
			"an unresolvable host is nil, not an error — start warns once and does nothing");
	}

	// ----- the painting register on the wire -------------------------------

	test_payload_paint {
		var ev = \orange.brush("Cm7").asStream.next(Event.default);
		var msgs = Broadcast.eventMessages(ev, "/syn", 3);
		var m = this.byAddress(msgs);
		this.assertEquals(msgs.collect(_[0]), [
			"/syn/paint/brush/amp", "/syn/paint/brush/pitch", "/syn/paint/brush/notes",
			"/syn/paint/brush/size", "/syn/paint/brush/dur", "/syn/paint/brush/hue",
			"/syn/paint/brush/sat", "/syn/paint/brush/val", "/syn/paint/brush/count"
		], "a stroke: every value under its own name, count last");
		this.assertEquals(m["/syn/paint/brush/notes"].size, 4, "all four notes of the chord in one message");
		this.assertFloatEquals(m["/syn/paint/brush/pitch"][0], 60, "pitch is the lowest MIDI note", 1e-6);
		this.assertEquals(m["/syn/paint/brush/size"], [4], "size");
		this.assertEquals(m["/syn/paint/brush/dur"], [4], "dur");
		this.assert((m["/syn/paint/brush/hue"][0] - 0.08).abs <= 0.03, "hue is the tube's, within its spread");
		this.assert(m["/syn/paint/brush/sat"][0].inclusivelyBetween(0, 1)
			and: { m["/syn/paint/brush/val"][0].inclusivelyBetween(0, 1) }, "sat and val ride along");
		ev = \grey.wash(55).asStream.next(Event.default);
		m = this.byAddress(Broadcast.eventMessages(ev, "/syn", 1));
		this.assertFloatEquals(m["/syn/paint/wash/pitch"][0], 55.cpsmidi, "a cloud's pitch is its root, as a MIDI note", 1e-6);
		this.assertEquals(m["/syn/paint/wash/dur"], [16], "dur");
		ev = \red.dab.asStream.next(Event.default);
		this.assertEquals(Broadcast.eventMessages(ev, "/syn")[0][0], "/syn/paint/dab/amp", "dab is its own tool on the wire");
	}
}
