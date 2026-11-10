// Synesthesia painting words — the register Paint implements, as words.
//
// Tools, on a colour (a palette name, or a colour such as a mix): pigment × applicator.
//   \orange.brush("Cm7 G7", 4)      sustained stroke — the \brush voice, legato
//   \blue.mark("0 3 5 7", 0.25)      discrete pitched events — the \mark voice
//   \red.dab("x..x")                 rhythmic touches on one note — \mark again
//   \violet.drip(2)                  sparse stochastic touches — \mark, Dust-like timing
//   \grey.spray(~r, dens: 25)        grain cloud at the root — the \spray voice
//   \black.wash(~r)                  texture bed at the root — the \wash voice
//   \orange.mix(\blue, 0.3)          a new pigment between two tubes
// sclang's call syntax makes brush(\orange, "Cm7 G7") the same expression, and a
// colour that is not a tube (a mix, an Event) takes the same words.
// Every tool returns a plain Pbind (assign it to a proxy and .play it), tagged
// \syn_brush (the tool), \syn_material (the voice) and \syn_color (the tube).
// The bodies live on Paint (Paint.brush(colour, ...)); these forward.
//
// Moves, on a pattern or a proxy: .darker .lighter .paler .saturate .warmer .cooler
// (each takes a step size), and .paint(colour) to recolour outright. On a pattern
// they return a new pattern; on a proxy whose source is a pattern they reassign it
// (crossfaded, on the grid); on a proxy whose source is a synth they move its
// hue/sat/val controls, so John's drone recipes join in the moment they name them.

+ Symbol {

	brush { |notes = "0", dur = 4, amp = 0.25, at = 0, legato = 0.95, oct = 5|
		^Paint.brush(this, notes, dur, amp, at, legato, oct)
	}

	mark { |notes = "0", dur = 0.25, amp = 0.3, at = 0, oct = 5|
		^Paint.mark(this, notes, dur, amp, at, oct)
	}

	dab { |steps = "x...", step = 1, amp = 0.4, at = 0, oct = 5|
		^Paint.dab(this, steps, step, amp, at, oct)
	}

	drip { |rate = 1, notes = "0", amp = 0.3, at = 0, oct = 5|
		^Paint.drip(this, rate, notes, amp, at, oct)
	}

	spray { |root, dens = 18, dur = 4, amp = 0.3, at = 0, buf, size|
		^Paint.spray(this, root, dens, dur, amp, at, buf, size)
	}

	wash { |root, dur = 16, amp = 0.25, at = 0|
		^Paint.wash(this, root, dur, amp, at)
	}

	mix { |other, x = 0.5| ^Paint.mix(this, other, x) }
}

// a colour that is not a tube's name — a mix, or (hue: sat: val:) written out
+ Event {

	brush { |notes = "0", dur = 4, amp = 0.25, at = 0, legato = 0.95, oct = 5|
		^Paint.brush(this, notes, dur, amp, at, legato, oct)
	}

	mark { |notes = "0", dur = 0.25, amp = 0.3, at = 0, oct = 5|
		^Paint.mark(this, notes, dur, amp, at, oct)
	}

	dab { |steps = "x...", step = 1, amp = 0.4, at = 0, oct = 5|
		^Paint.dab(this, steps, step, amp, at, oct)
	}

	drip { |rate = 1, notes = "0", amp = 0.3, at = 0, oct = 5|
		^Paint.drip(this, rate, notes, amp, at, oct)
	}

	spray { |root, dens = 18, dur = 4, amp = 0.3, at = 0, buf, size|
		^Paint.spray(this, root, dens, dur, amp, at, buf, size)
	}

	wash { |root, dur = 16, amp = 0.25, at = 0|
		^Paint.wash(this, root, dur, amp, at)
	}

	mix { |other, x = 0.5| ^Paint.mix(this, other, x) }
}

+ Pattern {

	paint { |color| ^Pbindf(this, *Paint.colorKeys(color)) }

	darker   { |x = 0.2| ^Paint.move(this, *Paint.verbMove(\darker, x)) }
	lighter  { |x = 0.2| ^Paint.move(this, *Paint.verbMove(\lighter, x)) }
	paler    { |x = 0.2| ^Paint.move(this, *Paint.verbMove(\paler, x)) }
	saturate { |x = 0.2| ^Paint.move(this, *Paint.verbMove(\saturate, x)) }
	warmer   { |x = 0.1| ^Paint.move(this, *Paint.verbMove(\warmer, x)) }
	cooler   { |x = 0.1| ^Paint.move(this, *Paint.verbMove(\cooler, x)) }
}

+ NodeProxy {

	paint { |color|
		var c, pairs = [];
		if(this.source.isKindOf(Pattern)) { this.source = this.source.paint(color); ^this };
		c = Paint.color(color);
		[\hue, \sat, \val].do { |axis|
			if(this.synHasControl(axis)) { pairs = pairs ++ [axis, c[axis]] };
		};
		if(pairs.isEmpty) { this.synNoAxis(\paint); ^this };
		this.set(*pairs);
		^this
	}

	darker   { |x = 0.2| ^this.synMove(\darker, x) }
	lighter  { |x = 0.2| ^this.synMove(\lighter, x) }
	paler    { |x = 0.2| ^this.synMove(\paler, x) }
	saturate { |x = 0.2| ^this.synMove(\saturate, x) }
	warmer   { |x = 0.1| ^this.synMove(\warmer, x) }
	cooler   { |x = 0.1| ^this.synMove(\cooler, x) }

	synMove { |verb, x|
		var axis, delta, toward, current;
		if(this.source.isKindOf(Pattern)) { this.source = this.source.perform(verb, x); ^this };
		#axis, delta, toward = Paint.verbMove(verb, x);
		if(this.synHasControl(axis).not) { this.synNoAxis(verb); ^this };
		current = this.get(axis) ?? { this.synControlDefault(axis) };
		this.set(axis, Paint.moved(current, axis, delta, toward));
		^this
	}

	synHasControl { |name| ^this.controlNames.any { |cn| cn.name == name } }

	synControlDefault { |name|
		^this.controlNames.detect { |cn| cn.name == name } !? { |cn| cn.defaultValue } ? 0.5
	}

	synNoAxis { |verb|
		var space = Synesthesia.space;
		var key = (space !? { space.envir.findKeyForValue(this) }) ? "this proxy";
		("Paint: ~" ++ key ++ " has no hue/sat/val controls for ." ++ verb
			++ " to move — its source speaks no colour").warn;
	}
}
