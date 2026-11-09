// Synesthesia — a live-coding vocabulary for SuperCollider.
// The patch is the canvas; patterns are brushes.
//
// Design docs live in the Live-Coding-With-SuperCollider repo:
//   Design Notes.md   — language thesis, registers, vocabulary buckets
//   Roadmap.md        — phased build pipeline (this is Phase 1: MVP audio language)
//
// Design rules every addition must keep:
//   1. Every terse form evaluates to a plain SC object (Pattern/Function/NodeProxy).
//   2. All parameters are named controls with registered Specs — never bare literals.
//   3. Nothing auxiliary (GUI, visuals, camera) gets typed performance commands.

Synesthesia {
	classvar <version = "0.1.0";
	classvar <droneRecipes, <fxRecipes;
	classvar slots, derivedCount = 0, guarded;

	*initClass {
		Class.initClassTree(Spec);
		slots = IdentityDictionary.new;
		guarded = IdentitySet.new;

		// perceptual/utility specs — the named-controls substrate (design rule 2)
		Spec.add(\wob, [0.01, 10, \exp]);
		Spec.add(\lag, [0.0, 1.0, \lin]);
		Spec.add(\lpf_freq, [40, 12000, \exp, 0, 800]);
		Spec.add(\hpf_freq, [20, 8000, \exp, 0, 200]);
		Spec.add(\verb_mix, [0, 1, \lin, 0, 0.3]);
		Spec.add(\delay_mix, [0, 1, \lin, 0, 0.3]);
		Spec.add(\delay_time, [0.01, 2, \exp, 0, 0.375]);
		Spec.add(\dist_drive, [0, 1, \lin, 0, 0.3]);
		Spec.add(\crush_amt, [0, 1, \lin, 0, 0.3]);
		Spec.add(\wide_amt, [0, 2, \lin, 0, 1]);
		Spec.add(\duck_amt, [0, 2, \lin, 0, 0.5]);

		droneRecipes = IdentityDictionary[
			// self-oscillating DFM1 fed by a detuned sine pair at the root.
			// DFM1 starts from zero filter state and won't ring up from a whisper
			// (co34pt's "evaluate twice" quirk) — so keep the input hot, seed it
			// with a little noise, and keep resonance near/above self-oscillation.
			\dfm -> { |root, mult = 1, wob = 0.1, amp = 0.3|
				var f = root * mult;
				var res = SinOsc.kr(\wob.kr(wob), Rand(0, 6.28)).range(0.98, 1.15);
				var in = SinOsc.ar([f, f * 1.01], 0, 0.25) + PinkNoise.ar(0.003);
				DFM1.ar(in, f * 2, res, 1, 0, 0.002, \amp.kr(amp))
			},
			// near-pure partial with slow stochastic detune beating
			\tone -> { |root, mult = 1, wob = 0.1, amp = 0.3|
				var f = root * mult;
				var det = LFNoise1.kr(\wob.kr(wob)).range(0.996, 1.004);
				SinOsc.ar([f, f * det], 0, \amp.kr(amp))
			},
			// detuned saw pair, gently filtered
			\saw -> { |root, mult = 1, wob = 0.1, amp = 0.3|
				var f = root * mult;
				var cutoff = f * LFNoise1.kr(\wob.kr(wob)).range(3, 9);
				RLPF.ar(LFSaw.ar([f, f * 1.004], 0, 0.15), cutoff.clip(40, 16000), 0.3) * \amp.kr(amp)
			}
		];

		// fx recipes: { |in, val| ... } where val is already a signal or NamedControl
		fxRecipes = IdentityDictionary[
			\lpf -> (default: 800, ctl: \lpf_freq,
				func: { |in, val| RLPF.ar(in, val.clip(20, 16000), 0.4) }),
			\hpf -> (default: 200, ctl: \hpf_freq,
				func: { |in, val| RHPF.ar(in, val.clip(20, 12000), 0.4) }),
			\verb -> (default: 0.3, ctl: \verb_mix,
				func: { |in, val| XFade2.ar(in, FreeVerb.ar(in, 1, 0.8, 0.5), (val.clip(0, 1) * 2) - 1) }),
			\delay -> (default: 0.3, ctl: \delay_mix,
				func: { |in, val|
					var wet = CombC.ar(in, 2, \delay_time.kr(0.375), 3);
					XFade2.ar(in, wet, (val.clip(0, 1) * 2) - 1)
				}),
			\dist -> (default: 0.3, ctl: \dist_drive,
				func: { |in, val| (in * (1 + (val * 20))).tanh * (1 + val).reciprocal }),
			\crush -> (default: 0.3, ctl: \crush_amt,
				func: { |in, val| Decimator.ar(in, 48000 / (1 + (val * 40)), 16 - (val * 10)) }),
			\wide -> (default: 1, ctl: \wide_amt,
				func: { |in, val|
					var mid, side;
					if(in.isArray and: { in.size == 2 }) {
						mid = (in[0] + in[1]) * 0.5;
						side = (in[0] - in[1]) * 0.5 * val;
						[mid + side, mid - side]
					} { in }
				}),
			\dc -> (default: 1, ctl: nil, func: { |in, val| LeakDC.ar(in) })
		];
	}

	// ----- context ------------------------------------------------------

	// the ProxySpace this session performs in (Setup pushes it into `p`)
	*space {
		var cand = thisProcess.interpreter.p;
		if(cand.isKindOf(ProxySpace)) { ^this.guard(cand) };
		if(currentEnvironment.isKindOf(ProxySpace)) { ^this.guard(currentEnvironment) };
		"Synesthesia: no ProxySpace found — run Setup first".warn;
		^nil
	}

	// Protect a ProxySpace from a JITLib fault (SuperCollider 3.13) that silences a
	// sound for good. Assigning a proxy on a running server sends its SynthDef but
	// never marks the proxy as loaded. The first proxy that reads it therefore loads
	// it again, into the reader's own bundle, and that bundle becomes the one the
	// proxy will cancel when it is next reassigned. Reassign it before the bar line
	// and the reader's start is cancelled instead: the reader never plays, and the
	// server reports "/s_new duplicate node ID". Three lines do it:
	//   ~r = { 55 };  ~a = { SinOsc.ar(~r.kr * 4) ! 2 };  ~r = { 49 };
	// The guard marks each proxy loaded as it is assigned, which is what JITLib
	// meant. Setup calls it; every Synesthesia word that needs the space also does.
	// Idempotent, and any dispatch already on the space keeps running.
	*guard { |space|
		var prev;
		if(space.isNil or: { guarded.includes(space) }) { ^space };
		guarded.add(space);
		prev = space.dispatch;
		space.dispatch = { |key, obj|
			prev.value(key, obj);
			if(obj.notNil) { space.envir[key] !? { |px| px.synSettle } };
		};
		^space
	}

	*guarded { |space| ^guarded.includes(space) }

	// the sample dictionary (Setup builds it into `d`)
	*samples {
		var cand = thisProcess.interpreter.d;
		^cand.isKindOf(Dictionary).if({ cand }, { nil })
	}

	*folder { |name|
		var dict = this.samples;
		var arr = dict !? { dict[name.asString] };
		arr ?? {
			Error("Synesthesia: no sample folder \"" ++ name ++ "\" in d").throw
		};
		^arr
	}

	// scale resolution: n.scale convention, then d[\scale], then minor
	*scale {
		var n = thisProcess.interpreter.n;
		var dict = this.samples;
		^(n.tryPerform(\at, \scale)) ?? { dict !? { dict[\scale] } } ?? { Scale.minor }
	}

	// ----- signal / control lifting (design rule 2) ----------------------

	// resolve a root/param that may be a proxy, function, or number — inside a UGen graph
	*signalOf { |thing, default = 55|
		^case
		{ thing.isKindOf(NodeProxy) } { thing.kr(1) }
		{ thing.isKindOf(Function) } { thing.value }
		{ thing.isNumber } { thing }
		{ thing.isNil } { default }
		{ thing }
	}

	// lift a number into a NamedControl so GUI/set/presets can reach it later;
	// pass proxies/functions through as live signals
	*controlOf { |name, val|
		^case
		{ val.isKindOf(NodeProxy) } { val.kr(1) }
		{ val.isKindOf(Function) } { val.value }
		{ val.isNumber } { NamedControl.kr(name, val) }
		{ val }
	}

	// ----- proxy slot bookkeeping (fx/feeds/routes live at index 10+) -----

	*slotFor { |proxy, key|
		var entry = slots[proxy] ?? {
			var e = (map: IdentityDictionary.new, next: 10);
			slots[proxy] = e;
			e
		};
		^entry[\map][key] ?? {
			var idx = entry[\next];
			entry[\next] = idx + 1;
			entry[\map][key] = idx;
			idx
		}
	}

	*freeSlot { |proxy, key|
		var entry = slots[proxy];
		^entry !? { entry[\map].removeAt(key) }
	}

	*nextDerivedKey {
		derivedCount = derivedCount + 1;
		^("syn_sig" ++ derivedCount).asSymbol
	}

	// ----- parsers (pure functions — unit tested) -------------------------

	// step notation: x = hit, X = accent, digit = hit picking sample variant,
	// . = rest, space/| ignored. Returns events (dur:, amp:, index:, rest:)
	// with plain numbers; rests are folded into onset-to-onset durations.
	*parseHits { |string, step = 0.25, amp = 1, accent = 1.4|
		var chars, len, onsets, events;
		chars = string.asString.reject { |c| c.isSpace or: { c == $| } };
		len = chars.size;
		events = List.new;
		if(len == 0) { ^events };
		onsets = List.new;
		chars.do { |c, i| if(c != $.) { onsets.add(i) } };
		if(onsets.isEmpty) {
			events.add((dur: len * step, amp: 0, index: nil, rest: true));
			^events
		};
		if(onsets.first > 0) {
			events.add((dur: onsets.first * step, amp: 0, index: nil, rest: true));
		};
		onsets.do { |pos, k|
			var c = chars[pos];
			var next = if(k == (onsets.size - 1)) { len } { onsets[k + 1] };
			var ev = (dur: (next - pos) * step, amp: amp, index: nil, rest: false);
			if(c == $X) { ev[\amp] = amp * accent };
			if(c.isDecDigit) { ev[\index] = c.digit };
			events.add(ev);
		};
		^events
	}

	// degree notation: integers (negatives ok), [0 2 4] chords, . or ~ rests.
	// Returns array of Integer | Array | \rest.
	*parseDegs { |string|
		var out = List.new, chord = nil;
		var tokens = string.asString
			.replace("[", " [ ").replace("]", " ] ")
			.split($ ).reject(_.isEmpty);
		tokens.do { |tok|
			case
			{ tok == "[" } { chord = List.new }
			{ tok == "]" } {
				chord !? { out.add(chord.asArray) };
				chord = nil;
			}
			{ (tok == ".") or: { tok == "~" } } {
				if(chord.isNil) { out.add(\rest) };
			}
			{ this.prIsIntToken(tok) } {
				if(chord.isNil) { out.add(tok.asInteger) } { chord.add(tok.asInteger) };
			}
			{ ("Synesthesia.parseDegs: skipping token \"" ++ tok ++ "\"").warn };
		};
		^out.asArray
	}

	// chord notation: names the way they are written on a chart — "Cm7 Fm9 Abmaj7 G7
	// F#m7b5 C/E" — with . or ~ for a rest, and _4 / _6 to drop or lift one chord an
	// octave. Returns an array of note Arrays | \rest.
	//
	// The names are resolved here, chord-first, through ChordSymbol2's own table.
	// Left as Symbols in a \note stream they are resolved note-first instead, and a
	// root letter followed by a digit is then a single note with an octave: \G7 is
	// the note G seven octaves up (100 kHz at the default octave), not a dominant
	// seventh. A name nothing can read is an error naming it, never a silent guess.
	*parseChords { |input|
		var toks = if(input.isKindOf(String)) {
			input.split($ ).reject(_.isEmpty)
		} { input.asArray };
		^toks.collect { |tok| this.prChord(tok) }
	}

	// chart spelling -> the spelling ChordSymbol2's table uses:
	//   "Abmaj7" -> "AbM7"   "F#m7b5" -> "Fsm7flat5"   "Bbmin" -> "Bbm"
	//   "C6/9" -> "C6by9"    "C/E" -> "C_E" (bass note)
	// A name already in that spelling (Cm7, Cm_eb, Cs7sharp5) comes back unchanged.
	*chordName { |name|
		var parts = name.asString.split($_);
		var head = parts[0], rest = parts.drop(1);
		var slash, bass, rootLen = 1, root, q, out = "";
		if(head.isEmpty) { ^name.asString };
		// a slash is a bass note (C/E) or a six-nine (C6/9)
		slash = head.indexOf($/);
		slash !? {
			bass = head.drop(slash + 1);
			head = head.keep(slash);
			if(bass.notEmpty and: { bass.first.isDecDigit }) {
				head = head ++ "by" ++ bass; bass = nil;
			};
		};
		// the root: a letter, then sharps and flats. s is ChordSymbol2's sharp —
		// unless it opens "sus"
		while {
			var c = head[rootLen];
			c.notNil and: { rootLen < 3 } and: {
				(c == $#) or: { c == $b }
				or: { (c == $s) and: { head[rootLen + 1] != $u } }
			}
		} { rootLen = rootLen + 1 };
		root = head.keep(rootLen).replace("#", "s");
		q = head.drop(rootLen);
		// the quality: # and b before a number are spelled out
		q.do { |c, i|
			out = out ++ case
			{ c == $# } { "sharp" }
			{ (c == $b) and: { q[i + 1].notNil } and: { q[i + 1].isDecDigit } } { "flat" }
			{ c.asString };
		};
		q = case
		{ out == "+" } { "aug" }
		{ out == "dom7" } { "7" }
		{ out.beginsWith("min") } { "m" ++ out.drop(3) }
		{ out.beginsWith("maj") or: { out.beginsWith("Maj") } } {
			// the table has maj9 and maj11, but major and major seventh are M and M7
			switch(out.drop(3), "", { "M" }, "7", { "M7" }, { "maj" ++ out.drop(3) })
		}
		{ out };
		bass !? { rest = [bass.replace("#", "s")] ++ rest };
		^([root ++ q] ++ rest).join("_")
	}

	*prChord { |tok|
		var lib, name, parts, octs, shift = 0, notes;
		if(tok.isKindOf(String).not and: { tok.isKindOf(Symbol).not }) { ^tok };   // notes already
		name = tok.asString;
		if([".", "~", "", "r", "rest"].any { |x| x == name }) { ^\rest };
		lib = \ChordSymbol.asClass ?? {
			Error("chord: the ChordSymbol2 quark is not installed — chord names need it").throw
		};
		// a digit after an underscore is that chord's octave, counted the way the
		// \octave key counts: 5 is the pattern's own register, 4 one octave under it
		parts = this.chordName(name).split($_);
		octs = parts.drop(1).select { |x| x.size == 1 and: { x[0].isDecDigit } };
		if(octs.notEmpty) { shift = (octs.last.asInteger - 5) * 12 };
		parts = [parts[0]] ++ parts.drop(1).reject { |x| x.size == 1 and: { x[0].isDecDigit } };
		notes = lib.asNotes(parts.join("_").asSymbol);
		if(notes.isKindOf(Array).not or: { notes.isEmpty } or: { notes.every(_.isNumber).not }) {
			Error("chord: cannot read \"" ++ name ++ "\" — names look like Cm7, G7, Abmaj7,"
				" F#m7b5, Csus4, C/E, Cm7_4 (every quality: ChordSymbol.shapes.keys)").throw
		};
		^notes + shift
	}

	*prIsIntToken { |tok|
		var body = if(tok.first == $-) { tok.drop(1) } { tok };
		^(body.size > 0) and: { body.every(_.isDecDigit) }
	}
}
