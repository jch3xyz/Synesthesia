// Synesthesia sculpt / wire / bridge verbs on NodeProxy.
// fx and feeds occupy proxy slots 10+ (source stays at 0, slots 1-9 are yours).
// Numeric params are lifted to NamedControls (design rule 2), so the future
// Board / MIDI-learn / presets can reach everything.
//
// Naming notes: NodeProxy:send already exists in JITLib, so send-to-return is
// `.route`; `.exp` is a math operator, so mappings are `.mapExp` / `.mapLin`.

+ NodeProxy {

	// ---- sculpt ---------------------------------------------------------

	// insert an effect in place: ~a.fx(\lpf, 800); ~a.fx(\verb, 0.3);
	// val may be a number (lifted to a control), a proxy, or a function
	fx { |name, val|
		var recipe = Synesthesia.fxRecipes[name];
		var slot;
		recipe ?? {
			Error("Synesthesia: unknown fx \"" ++ name ++ "\" — see Synesthesia.fxRecipes").throw
		};
		slot = Synesthesia.slotFor(this, name);
		this[slot] = \filterIn -> { |in|
			recipe[\func].value(
				in,
				Synesthesia.controlOf(recipe[\ctl] ? name, val ? recipe[\default])
			)
		};
		^this
	}

	unfx { |name|
		var slot = Synesthesia.freeSlot(this, name);
		slot !? { this[slot] = nil };
		^this
	}

	wide { |width = 1| ^this.fx(\wide, width) }

	dc { ^this.fx(\dc, 1) }

	fade { |seconds = 4|
		this.fadeTime = seconds;
		^this
	}

	// ---- wire -----------------------------------------------------------

	// mix this proxy into another's canvas (processed by the target's fx chain)
	feeds { |target, amt = 0.2|
		var space = Synesthesia.space;
		var slot = Synesthesia.slotFor(target, this);
		var myKey = (space !? { space.envir.findKeyForValue(this) }) ? \feed;
		target[slot] = {
			this.ar(2) * Synesthesia.controlOf((myKey.asString ++ "_feed").asSymbol, amt)
		};
		^this
	}

	// send to a standing return built in Setup: ~a.route(\verb, 0.3) feeds ~verbIn
	route { |returnName = \verb, amt = 0.3|
		var space = Synesthesia.space;
		var retKey = (returnName.asString ++ "In").asSymbol;
		var retIn, slot, myKey;
		space ?? { ^this };
		retIn = space[retKey];
		if(retIn.source.isNil) {
			("Synesthesia.route: no standing return ~" ++ retKey
				++ " — is it created in Setup?").warn;
		};
		slot = Synesthesia.slotFor(retIn, this);
		myKey = space.envir.findKeyForValue(this) ? \src;
		retIn[slot] = {
			this.ar(2) * Synesthesia.controlOf(
				(myKey.asString ++ "_" ++ returnName.asString ++ "_amt").asSymbol, amt)
		};
		^this
	}

	// sidechain: dip this proxy by another's amplitude envelope
	duck { |source, amt = 0.5, attack = 0.01, release = 0.25|
		var slot = Synesthesia.slotFor(this, \duck);
		this[slot] = \filterIn -> { |in|
			var env = Amplitude.kr(Mix.new(source.ar(2)), attack, release);
			in * (1 - (env * Synesthesia.controlOf(\duck_amt, amt))).clip(0, 1)
		};
		^this
	}

	// ---- bridge ---------------------------------------------------------

	// this proxy's amplitude envelope, as a control proxy (pattern → signal)
	follow { |lagTime = 0.1|
		var space = Synesthesia.space;
		var key, envKey;
		space ?? { ^nil };
		key = space.envir.findKeyForValue(this) ? \sig;
		envKey = (key.asString ++ "_env").asSymbol;
		if(space[envKey].source.isNil) {
			space[envKey] = { Amplitude.kr(Mix.new(this.ar(2))).lag(\lag.kr(lagTime)) };
		};
		^space[envKey]
	}

	// this proxy as pattern material (signal → pattern), via BenoitLib's Pkr
	pat { ^Pkr(this) }

	// map a 0..1 control proxy into a range, as a new proxy
	mapExp { |lo = 20, hi = 20000|
		var space = Synesthesia.space;
		var key;
		space ?? { ^this };
		key = Synesthesia.nextDerivedKey;
		space[key] = { this.kr(1).linexp(0, 1, lo, hi) };
		^space[key]
	}

	mapLin { |lo = 0, hi = 1|
		var space = Synesthesia.space;
		var key;
		space ?? { ^this };
		key = Synesthesia.nextDerivedKey;
		space[key] = { this.kr(1).linlin(0, 1, lo, hi) };
		^space[key]
	}
}
