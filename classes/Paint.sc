// Paint — the painting register: colours and tools over the engineering words.
//
// A colour is a coordinate in the HSV space every voice of the palette already
// speaks (hue = angle on the voice's timbre wheel, sat = hue-identity strength,
// val = light; CONVENTIONS.md rule 3 in the companion repo), plus a spread: pigment
// is never flat, so every event draws its own hue/sat/val from a small
// neighbourhood of the tube. A tool is one voice and one way of applying it in
// time: brush = sustained stroke, mark = discrete pitched events, dab = rhythmic
// touches, drip = sparse stochastic touches, spray = grain cloud, wash = bed.
// Pitch and time stay music words: degrees, chord names, step strings and durs
// ride in the tool call unchanged. Placement (at:) is the stroke's, not the pigment's.
//
// Register-dropping: every colour word expands to the descriptive layer. A tool
// returns an ordinary Pbind over an ordinary SynthDef with \hue, \sat, \val keys;
// a move such as .darker is a Pbindf on \val. Nothing here is a wall (design rule 1).
//
// The lexicon is discovered, not specified: the palette is a first set of tubes to
// play and rename by ear —  Paint.palette[\rust] = Paint.color(0.05, 0.8, 0.35);

Paint {
	classvar <palette, <tools;
	// the poles .warmer and .cooler move toward, on the wheel
	classvar <warmPole = 0.08, <coolPole = 0.62;

	*initClass {
		Class.initClassTree(Spec);
		// the colour axes (the companion repo's 00_specs.scd registers the same)
		Spec.add(\hue, [0, 1]);
		Spec.add(\sat, [0, 1]);
		Spec.add(\val, [0, 1]);
		palette = IdentityDictionary.new;
		[
			// name      hue   sat   val   spread  — hue follows the screen colour wheel,
			// so a colour's timbre coordinates are its screen colour (Design Notes)
			[\red,     0.00, 0.75, 0.50, 0.02],
			[\orange,  0.08, 0.70, 0.60, 0.03],
			[\yellow,  0.17, 0.60, 0.75, 0.03],
			[\green,   0.33, 0.60, 0.50, 0.03],
			[\cyan,    0.50, 0.55, 0.65, 0.03],
			[\blue,    0.62, 0.70, 0.40, 0.03],
			[\violet,  0.75, 0.65, 0.35, 0.03],
			[\magenta, 0.85, 0.80, 0.55, 0.03],
			[\grey,    0.50, 0.05, 0.50, 0.05],
			[\white,   0.50, 0.10, 0.95, 0.03],
			[\black,   0.50, 0.50, 0.05, 0.02]
		].do { |row| palette[row[0]] = this.color(row[1], row[2], row[3], row[4], row[0]) };
		// tool -> the one voice it owns (the "Families" table in CONVENTIONS.md).
		// Rebindable: Paint.tools[\dab] = \kick
		tools = IdentityDictionary[
			\brush -> \brush, \mark -> \mark, \dab -> \mark, \drip -> \mark,
			\spray -> \spray, \sprayb -> \sprayb, \wash -> \wash
		];
	}

	// ----- colours -----------------------------------------------------------

	// A colour, from numbers (hue, sat, val, spread) or from any colour form: a
	// palette name, [hue, sat, val, spread], an Event (hue: sat: val:), a Color.
	// Returns an Event (hue: sat: val: spread: name:) — a plain object.
	*color { |hue, sat, val, spread, name|
		var c;
		case
		{ hue.isNumber } {
			^(hue: hue.wrap(0, 1), sat: (sat ? 0.6).clip(0, 1), val: (val ? 0.5).clip(0, 1),
				spread: (spread ? 0.03).clip(0, 0.5), name: name)
		}
		{ hue.isKindOf(Symbol) or: { hue.isKindOf(String) } } {
			c = palette[hue.asSymbol];
			c ?? {
				Error("Paint: no colour \"" ++ hue ++ "\" in the palette — the tubes are "
					++ this.colors.join(" ")).throw
			};
			^c
		}
		{ hue.isKindOf(Color) } { c = hue.asHSV; ^this.color(c[0], c[1], c[2], spread, name) }
		{ hue.isKindOf(Dictionary) } {
			^this.color(hue[\hue] ? 0, hue[\sat], hue[\val], hue[\spread], hue[\name])
		}
		{ hue.isKindOf(SequenceableCollection) and: { hue.notEmpty } and: { hue[0].isNumber } } {
			^this.color(hue[0], hue[1], hue[2], hue[3], name)
		}
		{
			Error("Paint: cannot read a colour from " ++ hue.asString
				++ " — use a palette name (" ++ this.colors.join(" ")
				++ "), [hue, sat, val], (hue: sat: val:) or a Color").throw
		}
	}

	*colors { ^palette.keys.asArray.sort }

	// mix two pigments: sat, val and spread interpolate; hue takes the shorter way
	// round the wheel, so red and magenta mix through the purples, not through green
	*mix { |a, b, x = 0.5|
		var ca = this.color(a), cb = this.color(b);
		var d = (cb[\hue] - ca[\hue]).wrap(-0.5, 0.5);
		var name = if(ca[\name].notNil and: { cb[\name].notNil }) {
			(ca[\name].asString ++ "_" ++ cb[\name].asString).asSymbol
		};
		x = x.clip(0, 1);
		^this.color((ca[\hue] + (d * x)).wrap(0, 1),
			ca[\sat].blend(cb[\sat], x), ca[\val].blend(cb[\val], x),
			ca[\spread].blend(cb[\spread], x), name)
	}

	// Pbind pairs that sample the pigment per event: hue within ± spread (wrapping),
	// sat and val within ± spread/2. \syn_color is an inert tag for Broadcast.
	*colorKeys { |color|
		var c = this.color(color), sp = c[\spread], half = sp * 0.5;
		^[
			\hue, Pwhite(c[\hue] - sp, c[\hue] + sp).wrap(0, 1),
			\sat, Pwhite(c[\sat] - half, c[\sat] + half).clip(0, 1),
			\val, Pwhite(c[\val] - half, c[\val] + half).clip(0, 1),
			\syn_color, c[\name] ? \custom
		]
	}

	// ----- tools -------------------------------------------------------------

	*voiceFor { |tool|
		^tools[tool] ?? {
			Error("Paint: no voice for the tool \"" ++ tool ++ "\" — Paint.tools knows "
				++ tools.keys.asArray.sort.join(" ")).throw
		}
	}

	// the stroke every tool word returns: one voice, the tool's own pairs, placement,
	// the pigment, and the tags Broadcast reads (\syn_brush is the tool)
	*stroke { |tool, color, pairs, at = 0|
		var voice = this.voiceFor(tool);
		^Pbind(*(
			[\instrument, voice] ++ (pairs ? []) ++ [\pan, at]
			++ this.colorKeys(color)
			++ [\syn_brush, tool, \syn_material, voice]
		))
	}

	// what a notes argument is: chord names ("Cm7 G7"), a step string ("x..x"),
	// or scale degrees ("0 3 5 [0 2 4]", or an array of numbers)
	*strokeKind { |notes|
		var toks;
		if(notes.isKindOf(String).not) {
			if(notes.isKindOf(SequenceableCollection) and: { notes.notEmpty }
				and: { notes.every { |x| x.isKindOf(Symbol) } }) { ^\chords };
			^\degs
		};
		toks = notes.split($ ).reject(_.isEmpty);
		if(toks.any { |tok| "ABCDEFG".includes(tok[0]) }) { ^\chords };
		if(notes.any { |c| (c == $x) or: { c == $X } }) { ^\steps };
		^\degs
	}

	// the pitch/time pairs of a pitched stroke. dur is beats per note (or per step
	// character for a step string); shuffle draws notes at random (drip)
	*notePairs { |notes = "0", dur = 0.5, amp = 0.3, oct = 5, shuffle = false|
		var kind = this.strokeKind(notes), seq, items, events;
		if(kind == \steps and: { dur.isNumber.not }) { kind = \degs; notes = "0" };
		seq = { |xs| if(shuffle) { Prand(xs, inf) } { Pseq(xs, inf) } };
		^switch(kind,
			\chords, {
				items = Synesthesia.parseChords(notes).collect { |x| if(x == \rest) { Rest() } { x } };
				[\note, seq.(items), \octave, oct, \dur, dur, \amp, amp]
			},
			\steps, {
				events = Synesthesia.parseHits(notes, dur, amp);
				[\degree, 0, \octave, oct, \scale, Synesthesia.scale,
					\dur, Pseq(events.collect { |e| if(e[\rest]) { Rest(e[\dur]) } { e[\dur] } }, inf),
					\amp, Pseq(events.collect(_[\amp]), inf)]
			},
			{
				items = if(notes.isKindOf(String)) { Synesthesia.parseDegs(notes) } { notes.asArray };
				items = items.collect { |x| if(x == \rest) { Rest() } { x } };
				[\degree, seq.(items), \octave, oct, \scale, Synesthesia.scale, \dur, dur, \amp, amp]
			}
		)
	}

	// a root for the clouds: a number, a control proxy (followed live, via Pkr), a
	// pattern, or nothing — then the canvas's ~r if it has a source, else 55 Hz
	*rootPattern { |root|
		var space;
		^case
		{ root.isNumber or: { root.isKindOf(Pattern) } } { root }
		{ root.isKindOf(NodeProxy) } { Pkr(root) }
		{ root.isKindOf(Function) } { root.value }
		{ root.isNil } {
			space = Synesthesia.space;
			if(space.notNil and: { space.envir[\r].notNil } and: { space.envir[\r].source.notNil }) {
				Pkr(space.envir[\r])
			} { 55 }
		}
		{ root }
	}

	// a buffer for sprayb: a Buffer, or a folder name in d (its latest capture)
	*bufferOf { |buf|
		^case
		{ buf.isKindOf(Buffer) } { buf }
		{ buf.isKindOf(Symbol) or: { buf.isKindOf(String) } } { Synesthesia.folder(buf).last }
		{ Error("Paint: spray needs a Buffer or a folder name in d, not " ++ buf.asString).throw }
	}

	// ----- the tool words, colour first ---------------------------------------
	// Symbol (a tube's name) and Event (a colour, such as a mix) forward here, so
	// \orange.brush(...), \orange.mix(\blue).brush(...) and Paint.brush(\orange, ...)
	// are one word.

	*brush { |color, notes = "0", dur = 4, amp = 0.25, at = 0, legato = 0.95, oct = 5|
		^this.stroke(\brush, color, this.notePairs(notes, dur, amp, oct) ++ [\legato, legato], at)
	}

	*mark { |color, notes = "0", dur = 0.25, amp = 0.3, at = 0, oct = 5|
		^this.stroke(\mark, color, this.notePairs(notes, dur, amp, oct), at)
	}

	// a touch per bar by default: "x..." at one beat per character
	*dab { |color, steps = "x...", step = 1, amp = 0.4, at = 0, oct = 5|
		^this.stroke(\dab, color, this.notePairs(steps, step, amp, oct), at)
	}

	// rate = touches per beat on average; notes are drawn at random
	*drip { |color, rate = 1, notes = "0", amp = 0.3, at = 0, oct = 5|
		^this.stroke(\drip, color,
			this.notePairs(notes, Pexprand(0.1, 4) / rate, amp, oct, shuffle: true), at)
	}

	// a synthetic cloud at the root, or a buffer cloud over a capture: spray(buf: \live)
	*spray { |color, root, dens = 18, dur = 4, amp = 0.3, at = 0, buf, size|
		var pairs = [\dur, dur, \legato, 1.1, \amp, amp, \dens, dens];
		size !? { pairs = pairs ++ [\size, size] };
		^if(buf.notNil) {
			this.stroke(\sprayb, color, pairs ++ [\buf, this.bufferOf(buf)], at)
		} {
			this.stroke(\spray, color, pairs ++ [\freq, this.rootPattern(root)], at)
		}
	}

	*wash { |color, root, dur = 16, amp = 0.25, at = 0|
		^this.stroke(\wash, color,
			[\dur, dur, \legato, 1.1, \amp, amp, \freq, this.rootPattern(root)], at)
	}

	// ----- moves (perceptual sculpt verbs) -----------------------------------

	// the voice's own natural colour, read from its SynthDesc, for a stroke that
	// never set the axis
	*axisDefault { |instrument, axis|
		var desc = instrument !? { SynthDescLib.global[instrument.asSymbol] };
		var ctl = desc !? { desc.controlDict[axis] };
		^ctl !? { ctl.defaultValue } ?? { (hue: 0.05, sat: 0.6, val: 0.5)[axis] ? 0.5 }
	}

	// one step of a move. sat and val clip; hue wraps, and with a pole it steps
	// toward the pole the shorter way round, never past it
	*moved { |value, axis, delta, toward|
		var d;
		if(axis == \hue) {
			toward ?? { ^(value + delta).wrap(0, 1) };
			d = (toward - value).wrap(-0.5, 0.5);
			^(value + (d.sign * min(d.abs, delta))).wrap(0, 1)
		};
		^(value + delta).clip(0, 1)
	}

	// the verb table the Pattern and NodeProxy words share: axis, delta, pole
	*verbMove { |verb, x = 0.2|
		^switch(verb,
			\darker,   { [\val, x.neg] },
			\lighter,  { [\val, x] },
			\paler,    { [\sat, x.neg] },
			\saturate, { [\sat, x] },
			\warmer,   { [\hue, x, warmPole] },
			\cooler,   { [\hue, x, coolPole] },
			{ Error("Paint: no move called \"" ++ verb ++ "\"").throw }
		)
	}

	// a move on a pattern: a Pbindf that steps the axis from the value each event
	// already carries (or the voice's default), so moves chain and compound
	*move { |pattern, axis, delta, toward|
		^Pbindf(pattern, axis, Pfunc { |ev|
			this.moved(ev[axis] ?? { this.axisDefault(ev[\instrument], axis) }, axis, delta, toward)
		})
	}
}
