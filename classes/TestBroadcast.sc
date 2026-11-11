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
		var msgs = Broadcast.eventMessages(ev, "/syn", 0.5);   // beatDur 0.5: 120 bpm
		this.assertEquals(msgs, [
			["/syn/hit/k/amp", 1.4],
			["/syn/hit/k/index", 2],
			["/syn/hit/k/dur", 0.25],
			["/syn/hit/k/trig", 1]
		], "hits: amp (post-accent), the sample variant, dur in seconds, then the trig's 1");
	}

	test_payload_chop {
		var ev = Event.default.putAll((syn_brush: \chop, syn_material: \amen, amp: 0.5,
			slice: 3, syn_index: 3, dur: 0.25));
		var m = this.byAddress(Broadcast.eventMessages(ev, "/syn"));
		this.assertEquals(m["/syn/hit/amen/index"], [3], "chop: the break is the material, the slice is the index");
		this.assertEquals(m["/syn/hit/amen/trig"], [1], "pulsed like a hit");
	}

	test_payload_deg {
		var ev = Event.default.putAll((syn_brush: \deg, syn_material: \ping, amp: 0.3,
			degree: 3, dur: 0.5));
		var msgs = Broadcast.eventMessages(ev, "/syn", 0.5);
		var m = this.byAddress(msgs);
		this.assertEquals(msgs.collect(_[0]), ["/syn/note/ping/amp", "/syn/note/ping/notes",
			"/syn/note/ping/dur", "/syn/note/ping/trig"], "a note: amp, notes, dur, trig — nothing else");
		this.assertEquals(m["/syn/note/ping/amp"], [0.3], "amp");
		this.assertEquals(m["/syn/note/ping/notes"], [65, 0, 0, 0, 0, 0, 0, 0],
			"notes: the MIDI note (degree 3 of the default major scale) in slot 1, the other slots 0");
		this.assertEquals(m["/syn/note/ping/dur"], [0.25], "dur in seconds");
		this.assertEquals(msgs.last, ["/syn/note/ping/trig", 1], "the trig's 1 comes last");
	}

	test_payload_degChord {
		var ev = Event.default.putAll((syn_brush: \deg, syn_material: \ping, amp: 0.3,
			degree: [4, 0, 2], dur: 1));
		var m = this.byAddress(Broadcast.eventMessages(ev, "/syn"));
		this.assertEquals(m["/syn/note/ping/notes"], [60, 64, 67, 0, 0, 0, 0, 0],
			"a bracket chord: its MIDI notes lowest first, whatever order it was written in");
	}

	test_payload_chord {
		// what the chord name became in \note: note numbers
		var ev = Event.default.putAll((syn_brush: \chord, syn_material: \pad, amp: 0.2,
			note: [0, 3, 7], octave: 5, dur: 4));
		var m = this.byAddress(Broadcast.eventMessages(ev, "/syn"));
		this.assertEquals(m["/syn/note/pad/notes"], [60.0, 63.0, 67.0, 0, 0, 0, 0, 0],
			"chord: the MIDI notes, by the Event's own pitch chain, lowest first");
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

	test_noteSlotsFor {
		this.assertEquals(Broadcast.noteSlotsFor([67, 60, 64]), [60, 64, 67, 0, 0, 0, 0, 0], "sorted, padded with 0");
		this.assertEquals(Broadcast.noteSlotsFor(72), [72, 0, 0, 0, 0, 0, 0, 0], "a single note");
		this.assertEquals(Broadcast.noteSlotsFor((60..70)), (60..67), "a chord wider than the slots keeps its lowest");
	}

	// ----- pulses ----------------------------------------------------------

	test_pulseWidth {
		this.assertEquals(Broadcast.pulseWidth(nil, 0.05), 0.05, "no gap known: the pulse width");
		this.assertEquals(Broadcast.pulseWidth(1, 0.05), 0.05, "a slow voice: the pulse width");
		this.assertEquals(Broadcast.pulseWidth(0.04, 0.05), 0.02, "a fast voice: half the gap, so each event still falls");
		this.assertEquals(Broadcast.pulseWidth(0.001, 0.05), 0.002, "never under 2 ms");
	}

	test_barPhase {
		this.assertFloatEquals(Broadcast.barPhase(1, 4), 0.25, "beat 2 of 4", 1e-9);
		this.assertFloatEquals(Broadcast.barPhase(0.2, 4, 0.4), 0.95, "the clock is 0.4 beats ahead of the sound: still the end of the last bar", 1e-9);
		this.assertFloatEquals(Broadcast.barPhase(3.9, 4, 0.4), 0.875, "and 3.9 is heard as 3.5", 1e-9);
	}

	test_pulsesAt {
		this.assertEquals(Broadcast.phases.collect(_.key), [\half, \quarter, \eighth, \sixteenth], "the note values");
		this.assertEquals(Broadcast.pulsesAt(0), [\half, \quarter, \eighth, \sixteenth], "the downbeat fires all four");
		this.assertEquals(Broadcast.pulsesAt(2), [\half, \quarter, \eighth, \sixteenth], "so does beat 3 of 4");
		this.assertEquals(Broadcast.pulsesAt(1), [\quarter, \eighth, \sixteenth], "beat 2: not the half");
		this.assertEquals(Broadcast.pulsesAt(1.5), [\eighth, \sixteenth], "an off-beat eighth");
		this.assertEquals(Broadcast.pulsesAt(0.25), [\sixteenth], "a sixteenth alone");
		this.assertEquals(Broadcast.pulsesAt(2.75), [\sixteenth], "the last sixteenth of beat 3");
		this.assertEquals(Broadcast.pulsesAt(0.1), [], "between the grid: nothing");
		this.assertEquals(Broadcast.pulsesAt(0.9999999), [\quarter, \eighth, \sixteenth], "a hair early still counts as the beat");
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
		this.assertEquals(this.byAddress(Broadcast.eventMessages(ev, "/syn"))["/syn/note/pad/notes"].count(_ > 0), 4,
			"a four-note chord is one notes message with four notes in its slots");
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
		var msgs = Broadcast.eventMessages(ev, "/syn", 0.5);
		var m = this.byAddress(msgs);
		this.assertEquals(msgs.collect(_[0]), [
			"/syn/paint/brush/amp", "/syn/paint/brush/notes", "/syn/paint/brush/dur",
			"/syn/paint/brush/hue", "/syn/paint/brush/sat", "/syn/paint/brush/val", "/syn/paint/brush/trig"
		], "a stroke: every value under its own name, the trig last");
		this.assertEquals(m["/syn/paint/brush/notes"].count(_ > 0), 4, "all four notes of the chord in the slots");
		this.assertFloatEquals(m["/syn/paint/brush/notes"][0], 60, "slot 1 is the lowest MIDI note", 1e-6);
		this.assertEquals(m["/syn/paint/brush/dur"], [2], "dur: 4 beats at 120 bpm is 2 seconds");
		this.assert((m["/syn/paint/brush/hue"][0] - 0.08).abs <= 0.03, "hue is the tube's, within its spread");
		this.assert(m["/syn/paint/brush/sat"][0].inclusivelyBetween(0, 1)
			and: { m["/syn/paint/brush/val"][0].inclusivelyBetween(0, 1) }, "sat and val ride along");
		ev = \grey.wash(55).asStream.next(Event.default);
		m = this.byAddress(Broadcast.eventMessages(ev, "/syn"));
		this.assertFloatEquals(m["/syn/paint/wash/notes"][0], 55.cpsmidi, "a cloud's note is its root, as a MIDI note", 1e-6);
		this.assertEquals(m["/syn/paint/wash/dur"], [16], "dur, at one second a beat");
		ev = \red.dab.asStream.next(Event.default);
		this.assertEquals(Broadcast.eventMessages(ev, "/syn")[0][0], "/syn/paint/dab/amp", "dab is its own tool on the wire");
	}
}
