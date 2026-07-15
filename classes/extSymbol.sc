// Synesthesia sound spawners — Symbols name material; methods return plain objects.
//   \k.hits("x..x")      step-string drum Pbind from sample folder d["k"]
//   \k.hits(3, 8)        euclidean overload
//   \sinfb.deg("0 3 5")  scale-degree melody Pbind on a SynthDef
//   \bpfsaw.chord("Cm7 Fm9")  ChordSymbol2 harmony Pbind
//   \dfm.drone(~r)       drone Function (assign to a proxy)
//   \dfm.stack(~r, [1,2,4])  summed harmonic stack Function

+ Symbol {

	// step-string or euclid drum pattern from sample folder d[this]
	hits { |a, b, amp = 1, inst = \bplay, buf = 0, rot = 0, accent = 1.4|
		var folder = Synesthesia.folder(this);
		if(a.isKindOf(String)) {
			var events = Synesthesia.parseHits(a, b ? 0.25, amp, accent);
			var durs = Pseq(events.collect { |e|
				if(e[\rest]) { Rest(e[\dur]) } { e[\dur] }
			}, inf);
			var amps = Pseq(events.collect(_[\amp]), inf);
			var bufs = Pseq(events.collect { |e| folder.clipAt(e[\index] ? buf) }, inf);
			^Pbind(\instrument, inst, \buf, bufs, \dur, durs, \amp, amps)
		} {
			^Pbind(
				\instrument, inst,
				\buf, folder.clipAt(buf),
				\dur, Pbjorklund2(a, b ? 8, inf, rot) / 4,
				\amp, amp
			)
		}
	}

	// scale-degree melody on SynthDef named by this symbol (pitch arg must be freq)
	deg { |degrees, dur = 0.5, oct = 5, amp = 0.3, scale, root = 0|
		var degs = if(degrees.isKindOf(String)) {
			Synesthesia.parseDegs(degrees)
		} {
			degrees.asArray
		};
		var stream = Pseq(degs.collect { |x| if(x == \rest) { Rest() } { x } }, inf);
		^Pbind(
			\instrument, this,
			\scale, scale ?? { Synesthesia.scale },
			\root, root,
			\octave, oct,
			\degree, stream,
			\dur, dur,
			\amp, amp
		)
	}

	// named harmony via ChordSymbol2 — symbols embed directly in the \note stream
	chord { |symbols, dur = 4, amp = 0.3, oct = 0|
		var toks = if(symbols.isKindOf(String)) {
			symbols.split($ ).reject(_.isEmpty).collect(_.asSymbol)
		} {
			symbols.asArray
		};
		^Pbind(
			\instrument, this,
			\note, Pseq(toks, inf),
			\octave, 5 + oct,
			\dur, dur,
			\amp, amp
		)
	}

	// drone Function from a recipe (see Synesthesia.droneRecipes); root may be
	// a proxy (~r), function, or number
	drone { |root = 55, mult = 1, wob = 0.1, amp = 0.3|
		var recipe = Synesthesia.droneRecipes[this] ?? { Synesthesia.droneRecipes[\tone] };
		^{ recipe.value(Synesthesia.signalOf(root), mult, wob, amp) }
	}

	// one Function summing the recipe across harmonic multiples
	stack { |root = 55, mults, wob = 0.1, amp = 0.3|
		var recipe = Synesthesia.droneRecipes[this] ?? { Synesthesia.droneRecipes[\tone] };
		var ms = (mults ? [1, 2, 4]).asArray;
		^{
			var sig = Synesthesia.signalOf(root);
			ms.collect { |m| recipe.value(sig, m, wob, amp / ms.size) }.sum
		}
	}
}
