// Synesthesia bare control words — bpm(120), grid(4), hush(8), rec(8), pad(\x).
// sclang's call syntax foo(x) dispatches to the first argument (x.foo), so these
// live on SimpleNumber / Symbol, not on Interpreter.

+ SimpleNumber {

	// tempo in beats per minute on the performance clock: bpm(120)
	bpm {
		var space = Synesthesia.space;
		space !? {
			space.clock ?? { space.makeTempoClock };
			space.clock.tempo = this / 60;
		};
		^this
	}

	// rebinds land on the next `this` beats boundary (0 disables): grid(4)
	grid {
		var space = Synesthesia.space;
		space !? { space.quant = this };
		^this
	}

	// fade the whole canvas to silence over `this` seconds: hush(8)
	hush {
		var space = Synesthesia.space;
		space !? {
			// Broadcast's own proxies (the stem taps, the kr tap) are not music, so
			// they are left running: a stem falls silent with the proxy it follows,
			// over the same `this` seconds — hush tells Broadcast that fade time.
			space.envir.keysValuesDo { |key, proxy|
				if(Broadcast.isHidden(key).not) { proxy.stop(this) };
			};
			Broadcast.prFadeHint(this);
			("hush: fading all proxies over " ++ this ++ "s").postln;
		};
		^this
	}

	// live sampler: rec(8) captures 8 beats of SoundIn into d["live"],
	// playable via \live.hits(..., inst: \bplaym) (mono buffer)
	rec { |key = "live"|
		var space = Synesthesia.space;
		var server = Server.default;
		var dict = Synesthesia.samples;
		var tempo, dur, buf;
		if(server.serverRunning.not) { "rec: server not running".warn; ^nil };
		if(dict.isNil) { "rec: no sample dictionary d — run Setup first".warn; ^nil };
		tempo = space.tryPerform(\clock).tryPerform(\tempo) ? 2;
		dur = this / tempo;
		buf = Buffer.alloc(server, (dur * server.sampleRate).asInteger, 1);
		dict[key.asString] = (dict[key.asString] ?? { Array.new }).add(buf);
		Routine {
			server.sync;
			{
				RecordBuf.ar(SoundIn.ar(0), buf, loop: 0, doneAction: 2);
				Silent.ar(1)
			}.play(server);
			("rec: capturing " ++ this ++ " beats (" ++ dur.round(0.01)
				++ "s) into d[\"" ++ key ++ "\"][" ++ (dict[key.asString].size - 1) ++ "]").postln;
		}.play;
		^buf
	}
}

+ Symbol {

	// trackpad as signal: pad(\x) / pad(\y) → control proxy in 0..1
	pad {
		var space = Synesthesia.space;
		var key = ("syn_pad" ++ this).asSymbol;
		space ?? { ^nil };
		if(space[key].source.isNil) {
			space[key] = if(this == \y) { { MouseY.kr(0, 1) } } { { MouseX.kr(0, 1) } };
			// a Spec under the proxy's key is what Broadcast's normalised stream reads
			Spec.add(key, [0, 1]);
		};
		^space[key]
	}
}
