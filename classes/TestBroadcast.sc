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

	// ----- payload shapes --------------------------------------------------

	test_payload_hits {
		var ev = Event.default.putAll((syn_brush: \hits, syn_material: \k, amp: 1.4,
			syn_index: 2, dur: 0.5));
		this.assertEquals(Broadcast.eventMessages(ev, "/syn"), [["/syn/hit/k", 1.4, 2]],
			"hits: amp (post-accent) and the sample variant");
	}

	test_payload_chop {
		var ev = Event.default.putAll((syn_brush: \chop, syn_material: \amen, amp: 0.5,
			slice: 3, syn_index: 3, dur: 0.25));
		this.assertEquals(Broadcast.eventMessages(ev, "/syn"), [["/syn/hit/amen", 0.5, 3]],
			"chop: the break is the material, the slice is the variant");
	}

	test_payload_deg {
		var ev = Event.default.putAll((syn_brush: \deg, syn_material: \ping, amp: 0.3,
			degree: 3, dur: 0.5));
		this.assertEquals(Broadcast.eventMessages(ev, "/syn"), [["/syn/note/ping", 0.3, 3, 0.5]],
			"deg: amp, scale degree, duration in beats");
	}

	test_payload_degChord {
		var ev = Event.default.putAll((syn_brush: \deg, syn_material: \ping, amp: 0.3,
			degree: [0, 2, 4], dur: 1));
		this.assertEquals(Broadcast.eventMessages(ev, "/syn"), [
			["/syn/note/ping", 0.3, 0, 1],
			["/syn/note/ping", 0.3, 2, 1],
			["/syn/note/ping", 0.3, 4, 1]
		], "a bracket chord is one message per degree — one per sounding note");
	}

	test_payload_chord {
		// what ChordSymbol2 leaves in \note once a chord symbol has streamed: note numbers
		var ev = Event.default.putAll((syn_brush: \chord, syn_material: \pad, amp: 0.2,
			note: [0, 3, 7], octave: 5, dur: 4));
		this.assertEquals(Broadcast.eventMessages(ev, "/syn"), [
			["/syn/note/pad", 0.2, 60.0, 4],
			["/syn/note/pad", 0.2, 63.0, 4],
			["/syn/note/pad", 0.2, 67.0, 4]
		], "chord: midinote per note, computed by the Event's own pitch chain");
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
		this.assertEquals(Broadcast.eventMessages(ev, "/stage2")[0][0], "/stage2/hit/k",
			"the root travels into every event address");
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
		this.assertEquals(Broadcast.eventMessages(ev, "/syn"), [["/syn/note/ping", 0.3, 0, 0.5]],
			"and the tagged event yields a note message end to end");

		chord = \pad.chord("Cm7", 4);
		ev = chord.asStream.next(Event.default);
		this.assertEquals([ev[\syn_brush], ev[\syn_material]], [\chord, \pad], "chord tags the SynthDef");
		this.assert(ev[\note].isArray, "ChordSymbol2 streamed the symbol into note numbers");
		this.assertEquals(Broadcast.eventMessages(ev, "/syn").size, 4,
			"a four-note chord is four note messages");
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
}
