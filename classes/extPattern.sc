// Synesthesia transform verbs on event patterns — each returns a new Pattern,
// so they chain and still compose with raw pattern classes.

+ Pattern {

	fast { |factor = 2| ^Pmul(\dur, factor.reciprocal, this) }

	slow { |factor = 2| ^Pmul(\dur, factor, this) }

	// each `beats` window, play a transformed variant with probability prob:
	// ~mel.sometimes(0.2, _.fast(4))
	sometimes { |prob = 0.25, func, beats = 4|
		^Pn(Plazy {
			Pfindur(beats, if(prob.coin) { func.value(this) } { this })
		}, inf)
	}

	// straight copy left, transformed copy right: ~sn.jux(_.fast(2))
	jux { |func, spread = 0.7|
		^Ppar([
			Pbindf(this, \pan, spread.neg),
			Pbindf(func.value(this), \pan, spread)
		])
	}
}
