// Synesthesia breakbeat vocabulary — the words that drive the \break engine and the
// \brk1 chop player. Kept in one file (rather than split across extNodeProxy.sc and
// extSymbol.sc) because breaks are a feature you read, review and remove as a unit.
//
// Canvas words live on NodeProxy — a break is a persistent voice you sculpt:
//   ~d.brk(\amen)     put a break in this proxy (or swap it, gapless, on the bar)
//   ~d.brk            cycle to the next break in the library
//   ~d.grind(0.7)     THE macro: 0 intact loop / 0.5 clean chops / 1 dust
//   ~d.seq("0.325")   reorder the slices; "." is a rest
//   ~d.scr(0.5)       fade from the sequence into random slice choice
//
// Brush words live on Symbol — one synth per chop, so per-hit work is pattern work:
//   \amen.chop("0325")
//
// Naming notes: `speed` not `rate` (BusPlug:rate exists), `len_` not `beats_`
// (Object:beats_ is a real no-op for PauseStream and Symbols do flow through
// stream machinery in this system, via ChordSymbol2).

+ NodeProxy {

	// ---- the break itself -----------------------------------------------

	// Put a break in this proxy. The first call installs the \break engine and
	// allocates this voice's slice-sequence buffer; later calls swap the sample
	// on the next bar with no gap, keeping every control where you left it.
	// No argument cycles to the next break in the library.
	brk { |name|
		var key, entry, voice, isFirst, apply;
		if(Break.size == 0) {
			"brk: no breaks loaded — Break.scan(<samples dir>). See Guides/Breaks.".warn;
			^this
		};
		key = Break.resolveKey(name, Break.keyOf(this));
		key ?? {
			("brk: no break named \"" ++ name ++ "\" — have: "
				++ Break.order.join(" ")).warn;
			^this
		};
		entry = Break.at(key);
		isFirst = Break.voiceOf(this).isNil;
		voice = Break.register(this, key);
		apply = {
			this.set(
				\buf, entry[\buf].bufnum,
				\beats, entry[\beats],
				\steps, voice[\steps]
			);
			// A longer loop means more slices, and the sequence has to grow with them
			// or only the first part of the new break is ever reached. Only a sequence
			// still in source order is safe to regenerate — a hand-written one is the
			// performer's, and gets left alone.
			if(voice[\ordered] == true and: { voice[\slen] != voice[\steps] }) {
				Break.writeSeq(this, Array.series(voice[\steps], 0, 1), true);
			};
			// a new loop length repositions the loop boundary, so realign to the bar
			Break.trig(this, \resync);
		};
		// on the first call the proxy's own quant already lands .play on a bar
		if(isFirst) { apply.value } { Break.schedBar(apply) };
		^this
	}

	// ---- the continuous gestures (immediate) ----------------------------

	// 0 = one grain per loop (intact), 0.5 = one grain per slice (clean chops),
	// 1 = 64 grains per beat (dust)
	grind { |x = 0.5| ^this.prBrkSet(\grind, x) }

	// how far each grain overruns its own slot: <1 leaves gaps, >1 smears
	over { |x = 1| ^this.prBrkSet(\over, x) }

	// probability per slice of ignoring the sequence and picking at random
	scr { |x = 0.5| ^this.prBrkSet(\scram, x) }

	// grain position scatter — the ingredient that turns chops into dust
	jit { |x = 0.3| ^this.prBrkSet(\jit, x) }

	// 1 = repitch the break to fit the tempo (classic sampler), 0 = keep the
	// original pitch and let the grains stretch it
	vari { |x = 1| ^this.prBrkSet(\vari, x) }

	// musical transpose on top of vari (named speed because BusPlug:rate exists)
	speed { |x = 1| ^this.prBrkSet(\rate, x) }

	// static offset into the loop
	pos { |x = 0| ^this.prBrkSet(\pos, x) }

	// probability a grain plays backwards
	flip { |x = 0.5| ^this.prBrkSet(\flip, x) }

	// stutter: replay the first 1/n of each slice, n times. roll(1) releases it
	roll { |n = 2| ^this.prBrkSet(\roll, n.max(1)) }

	// grain envelope: \punch (default, keeps transients), \gate (hardest),
	// \hann (smooth, for granular work), \swell (reverse)
	win { |name = \punch| ^this.prBrkSet(\envbuf, Break.window(name)) }

	// ---- structure (lands on the bar) ----------------------------------

	// Slice order. "0.325" -> slice 0, rest, 3, 2, 5. Digits then letters index
	// slices; "." and "~" are rests; spaces and "|" are visual only. An Array
	// works too, with negatives or nils as rests. Longer than one loop is fine —
	// the sequence keeps counting across loops, which gives you polymeter.
	seq { |spec|
		var vals = Break.parseSeq(spec);
		if(Break.voiceOf(this).isNil) { "seq: call brk first".warn; ^this };
		if(vals.isEmpty) { "seq: nothing to play".warn; ^this };
		Break.schedBar({ Break.writeSeq(this, vals, false) });
		^this
	}

	// back to source order (and stop scrambling)
	ord {
		var voice = Break.voiceOf(this);
		voice ?? { "ord: call brk first".warn; ^this };
		Break.schedBar({
			Break.writeSeq(this, Array.series(voice[\steps], 0, 1), true);
			this.set(\scram, 0);
		});
		^this
	}

	// play the slices backwards (the loop reversed by slice, not by sample)
	rev {
		var voice = Break.voiceOf(this);
		voice ?? { "rev: call brk first".warn; ^this };
		Break.schedBar({
			Break.writeSeq(this, Array.series(voice[\steps], 0, 1).reverse, true);
			this.set(\scram, 0);
		});
		^this
	}

	// euclidean gate: keep `hits` slices of `n`, rest the others
	mask { |hits = 3, n|
		var voice = Break.voiceOf(this);
		var pattern, vals;
		voice ?? { "mask: call brk first".warn; ^this };
		n = (n ? voice[\steps]).asInteger.clip(1, Break.seqSize);
		pattern = Bjorklund(hits.asInteger.clip(0, n), n);
		vals = Array.fill(n, { |i| if(pattern[i] > 0) { i } { -1 } });
		Break.schedBar({ Break.writeSeq(this, vals, false) });
		^this
	}

	// how many slices the loop is cut into (16 = sixteenths of a 4-beat loop).
	// If the current order is untouched it is regenerated at the new length.
	steps { |n = 16|
		var voice = Break.voiceOf(this);
		voice ?? { "steps: call brk first".warn; ^this };
		n = n.asInteger.clip(1, Break.seqSize);
		voice[\steps] = n;
		Break.schedBar({
			this.set(\steps, n);
			if(voice[\ordered] == true) {
				Break.writeSeq(this, Array.series(n, 0, 1), true);
			};
			Break.trig(this, \resync);
		});
		^this
	}

	// realign the loop, the slice grid and the sequence to the bar
	resync { ^Break.trig(this, \resync) }

	// ---- private --------------------------------------------------------

	prBrkSet { |key, val|
		if(Break.voiceOf(this).isNil) {
			(key.asString ++ ": this proxy is not a break voice — call brk first").warn;
			^this
		};
		this.set(key, val);
		^this
	}
}

+ Symbol {

	// ---- break metadata -------------------------------------------------

	// the mono Buffer holding this break
	buf { ^Break.at(this) !? { |e| e[\buf] } }

	// the break's own tempo (175.bpm sets the SESSION tempo — different receiver)
	bpm { ^Break.at(this) !? { |e| e[\bpm] } }

	// the break's length in beats
	len { ^Break.at(this) !? { |e| e[\beats] } }

	// Correct a wrong guess, permanently: writes the folder's _breaks.scd sidecar.
	// These return the Symbol, not Break.set's entry — the entry is an Event, and an
	// Event turns any unknown foo_(x) into a key write via doesNotUnderstand, so
	// `\amen.bpm_(172).len_(8)` would have silently written a junk `len` key to the
	// registry instead of setting the beat count. Returning the receiver also matches
	// every other word in the vocabulary.
	bpm_ { |value| Break.set(this, value); ^this }
	len_ { |value| Break.set(this, nil, value); ^this }

	// the playback rate that makes this break fit the session tempo
	stretch { ^Break.stretchOf(this) }

	// ---- the brush ------------------------------------------------------

	// A chop pattern: one \brk1 synth per slice, so per-hit rate, pan, amp and
	// effects are ordinary pattern work. Returns a plain Pbind.
	//
	// \amen.chop("0325")           slices 0,3,2,5 as sixteenths
	// \amen.chop("0.32 0123")      "." is a rest
	// \amen.chop(nil, steps: 8)    the whole loop in eighths
	//
	// Defaults to vari 1 (fitted to the tempo) because a single one-shot has no
	// pointer to stretch with — pass vari: 0 for original pitch.
	chop { |spec, step, steps, amp = 0.5, vari = 1, flip = 0, hold = 8|
		var entry = Break.at(this);
		var key = this;
		var vals;
		entry ?? {
			Error("chop: no break \"" ++ this ++ "\" — Break.list").throw
		};
		// Pbind ends the moment any key stream yields nil, so a missing buffer would
		// quietly produce a pattern that plays nothing at all. Say so instead.
		entry[\buf] ?? {
			Error("chop: break \"" ++ this ++ "\" has no buffer — was the server"
				" running when Break.scan ran?").throw
		};
		steps = (steps ? (entry[\beats] * 4)).asInteger.max(1);
		step = step ? (entry[\beats] / steps);
		vals = Break.parseSeq(spec ?? { Array.series(steps, 0, 1) });
		if(vals.isEmpty) { vals = [0] };
		^Pbind(
			\instrument, \brk1,
			\buf, entry[\buf],
			// the event's \sustain is in beats, so the def needs the tempo to turn it
			// into a window length; read per event, so it tracks a live tempo change
			\tempo, Pfunc { Break.tempo },
			// \slice and \rev are scratch keys the later keys read
			\slice, Pseq(vals, inf),
			\rev, Pfunc { flip.coin.binaryValue },
			\fitted, Pfunc { Break.stretchOf(key) ** vari },
			\rate, Pkey(\fitted) * (1 - (2 * Pkey(\rev))),
			// a reversed chop starts at the far end of its slice and plays back into it
			\pos, (Pkey(\slice).max(0) + Pkey(\rev)) / steps,
			\dur, Pseq(vals.collect { |v| if(v < 0) { Rest(step) } { step } }, inf),
			\legato, 1,
			\amp, amp,
			\hold, hold
		)
	}
}
