// Broadcast — everything musically meaningful on the canvas, out to a second machine.
//
// A VJ (Resolume, MadMapper, anything that speaks OSC and takes audio) sits at the
// other end of an Ethernet cable. Broadcast is configured once in Setup and then
// watches the ProxySpace on its own: nothing here is ever typed mid-set (design
// rule 3 — Broadcast is auxiliary, so its whole API is setup-time).
//
//   Broadcast.start(NetAddr("192.168.1.20", 9000));   // all three streams
//   Broadcast.start(addr, stems: false, rate: 20);     // trimmed after a rehearsal
//   Broadcast.status;                                   // what is live, what is tapped
//   Broadcast.stop;
//
// Three streams leave the machine:
//   events      one OSC message per value of every sounding hit or note from every
//               brush (hits, deg, chord, chop, the painting tools), timed to the
//               audible onset
//   continuous  every control-rate proxy's current value at `rate` Hz, each on its
//               own stable address, plus tempo and the bar / half / quarter /
//               eighth / sixteenth phases
//   stems       a stereo pair per audible proxy on hardware outputs from `stemOut`,
//               the main mix always on stem 0 — an aggregate device (interface +
//               BlackHole) hands those channels to SonoBus or JackTrip
//
// The OSC namespace (the root is a setting; "/syn" is the default). Designed for a
// receiver that keeps ONE CHANNEL PER ADDRESS and samples it at frame rate
// (TouchDesigner's OSC In CHOP, Resolume, MadMapper): every value has its own
// address, so every channel is named by what it is, and every event and every grid
// point is a PULSE — a 1 on the onset and a 0 `pulse` seconds later (shorter when
// events come faster) — so a trigger fires on a plain rising edge.
//   /syn/hit/<folder>/trig | amp | index | dur          .hits and .chop
//   /syn/note/<synthdef>/trig | amp | notes… | dur      .deg and .chord
//   /syn/paint/<tool>/trig | amp | notes… | dur | hue | sat | val
//   /syn/kr/<proxyKey>     value              native range
//   /syn/krn/<proxyKey>    value01            Spec-normalised, where a Spec is registered
//   /syn/tempo             bpm
//   /syn/bar               phase01            position in the bar, at the continuous rate
//   /syn/half | quarter | eighth | sixteenth  pulse       on every boundary of that note value
//   /syn/stems             key0 key1 ...      stem index -> proxy key, on every change
//   /syn/gone/<proxyKey>                      proxy cleared or source set to nil
//   /syn/alive             seconds            heartbeat, 1 Hz
// `notes` is the one multi-argument message: the event's MIDI notes, lowest first,
// as wide as the widest chord this voice has sent since start (at most `noteSlots`,
// 8), with 0 in the slots a smaller chord does not use — notes1 is always the
// lowest note, a single-note voice is notes1 alone, and no stale note survives a
// smaller chord. `dur` is seconds to this voice's next event. `trig` is sent last in a burst, so a receiver firing on it finds the
// event's other values already in place.
//
// NEVER BREAKS THE MUSIC. A wrong IP, an absent receiver, a missing aggregate device,
// too few output channels, SonoBus not running: the canvas sounds identical and the
// post window gets at most one warning per condition (prWarnOnce). UDP has no
// connection to lose, so an unreachable address simply swallows packets; the one
// failure that can throw — a hostname that does not resolve — is caught at start.
//
// EVENTS: WHERE THE HOOK IS, AND WHY THE TIMING WORKS. The brushes tag the Pbinds
// they return (\syn_brush, \syn_material, \syn_index) and stay plain Pbinds (rule 1).
// When Broadcast starts it wraps the ONE function every event passes through — the
// default parent event's `play`, which runs the event type and then calls
// ~callback — so a performer's own \callback is still called, untouched, and ours
// runs after it. The server message for an event is sent `~latency` (0.2 s) before
// the sound, so the hook schedules the OSC send on SystemClock by that much (plus
// any lag/timingOffset), landing it on the audible onset for receivers that ignore
// bundle timetags. `timetags: true` sends a timestamped bundle at once instead.
// Like a stem, the event stream follows the Monitor: a brush in a stopped proxy
// keeps running unheard, and sends nothing until it is played again. "Heard" means
// the Monitor has a group — true from the .play call, false from the .stop call —
// not monitor.isPlaying, which waits for the server's reply and would lose the
// first hits of a new brush (isAudible).
//
// STEMS: WHY A GAIN-TRACKING TAP RATHER THAN MIRRORING THE MONITOR. `proxy.stop`
// stops only the Monitor; the proxy keeps running, so a naive tap on proxy.ar(2)
// would keep feeding the VJ a kick the audience can no longer hear — including
// after hush. The exact fix is to mirror the Monitor (playN with an `ins` mapping
// so one Monitor fades the main pair and the stem pair together), but that means
// intercepting .play/.stop/.playN/.vol_ on every proxy — rewriting the one JITLib
// surface the performer touches most, with every JITLib upgrade a risk. The tap
// instead reads proxy.ar(2) post-fx and multiplies by a gain that follows the
// Monitor — its vol while it has a group (isAudible), 0 once .stop has taken the
// group away — lagged by the proxy's fadeTime (hush passes its own fade time
// through prFadeHint). It is approximate — Lag is exponential
// where the Monitor's fade is linear — but it is non-invasive, needs one lightweight
// synth per stem, and a stem falls silent whenever its proxy does. Stem 0 is the
// real main mix: a synth at the head of the root node reading hardware bus 0 with
// InFeedback, which at that position holds the previous cycle's complete, limited
// output.
//
// Everything Broadcast makes in the ProxySpace starts with `prefix` (syn_bc_). hush
// skips that prefix, and so do the two walkers here: the kr walker never reports a
// hidden proxy, the stem walker never taps one.

Broadcast {
	classvar <>prefix = "syn_bc_";      // every proxy Broadcast makes starts with this
	classvar <addr, <root, <rate, <stemOut, <maxStems, <krn, <timetags, <pulse;
	classvar <events = false, <kr = false, <stems = false;
	classvar <running = false, <warnings, space, startedAt;
	// events
	classvar origPlay, hooked = false, pulseTokens, noteWidths;
	// continuous
	classvar <krKeys, krGen = 0, krGens, krAddrs, krnAddrs, krResponder, krWalker;
	classvar tempoAddr, barAddr, pulseAddrs, stemsAddr, aliveAddr, gridPulser;
	// the note values the grid pulses mark, in quarter-note beats, and how many
	// note slots an event's `notes` message always carries
	classvar <phases, <noteSlots = 8;
	// stems
	classvar <stemTable, stemGains, stemWalker, heartbeat, mainSynth, mainDef, mainPending = false;
	classvar hushLag, hushLagUntil;

	*initClass {
		Class.initClassTree(Spec);
		// the stem tap's two controls (design rule 2: no bare literals, even hidden ones)
		Spec.add(\bc_gain, [0, 1]);
		Spec.add(\bc_lag, [0, 60]);
		warnings = IdentityDictionary.new;
		krKeys = [];
		phases = [\half -> 2, \quarter -> 1, \eighth -> 0.5, \sixteenth -> 0.25];
	}

	// ----- the setup-time surface -------------------------------------------

	// addr: a NetAddr, or "host" / "host:port" (port defaults to 9000)
	// pulse: how long a trig or grid pulse stays at 1, in seconds (shortened to half
	// the gap when events of one voice come faster than that)
	*start { |addr, events = true, kr = true, stems = true, rate = 30, stemOut = 2,
		maxStems = 8, root = "/syn", krn = true, timetags = false, pulse = 0.05|
		var target = this.resolveAddr(addr);
		if(running) { this.stop };
		warnings.clear;
		space = Synesthesia.space;
		space ?? { ^this };
		target ?? {
			this.prWarnOnce(\addr, "Broadcast: cannot resolve \"" ++ addr
				++ "\" — not started. Pass a NetAddr or \"host:port\".");
			^this
		};
		// (the arguments shadow the classvars of the same name, hence the hand-off)
		this.prConfigure(target, events, kr, stems, rate, stemOut, maxStems, root, krn, timetags, pulse);
		running = true;
		startedAt = Main.elapsedTime;
		if(this.events) { this.prStartEvents };
		if(this.kr) { this.prStartKr };
		if(this.stems) { this.prStartStems };
		this.prStartHeartbeat;
		("Broadcast: → " ++ target.ip ++ ":" ++ target.port ++ " on " ++ this.root ++ " — "
			++ this.prStreamNames).postln;
		^this
	}

	*prConfigure { |target, ev, cont, st, hz, out, cap, ns, norm, tt, width|
		addr = target;
		root = ns.asString;
		rate = hz.clip(1, 200);
		stemOut = out.asInteger.max(0);
		maxStems = cap.asInteger.max(0);
		krn = norm;
		timetags = tt;
		pulse = width.clip(0.005, 2);
		events = ev;
		kr = cont;
		stems = st;
		tempoAddr = this.address(root, "tempo");
		barAddr = this.address(root, "bar");
		pulseAddrs = IdentityDictionary.new;
		phases.do { |assoc| pulseAddrs[assoc.key] = this.address(root, assoc.key) };
		stemsAddr = this.address(root, "stems");
		aliveAddr = this.address(root, "alive");
		pulseTokens = Dictionary.new;
		noteWidths = Dictionary.new;
		krAddrs = IdentityDictionary.new;
		krnAddrs = IdentityDictionary.new;
	}

	*stop {
		running = false;
		if(hooked) {
			Event.parentEvents[\default][\play] = origPlay;
			hooked = false;
		};
		krWalker !? { krWalker.stop };
		gridPulser !? { gridPulser.stop };
		stemWalker !? { stemWalker.stop };
		heartbeat !? { heartbeat.stop };
		krResponder !? { krResponder.free };
		krWalker = gridPulser = stemWalker = heartbeat = krResponder = nil;
		mainSynth !? { if(mainSynth.server.serverRunning) { mainSynth.free } };
		mainSynth = nil;
		mainPending = false;
		space !? {
			space.envir.keysValuesDo { |key, proxy|
				if(this.isHidden(key)) { proxy.clear };
			};
		};
		krKeys = [];
		krGens = nil;
		stemTable = nil;
		stemGains = nil;
		events = kr = stems = false;
		^this
	}

	*status {
		var server = space !? { space.server };
		if(running.not) { "Broadcast: not running".postln; ^this };
		("Broadcast → " ++ addr.ip ++ ":" ++ addr.port ++ "  root " ++ root).postln;
		("  streams: " ++ this.prStreamNames).postln;
		if(kr) {
			("  continuous: " ++ rate ++ " Hz, " ++ krKeys.size ++ " proxies"
				++ if(krKeys.isEmpty) { "" } { " — " ++ krKeys.join(" ") }
				++ if(krn) { " (+ normalised where a Spec is registered)" } { "" }).postln;
		};
		if(stems) {
			("  stems: outputs from " ++ stemOut ++ ", up to " ++ maxStems
				++ " + main; server has " ++ (server !? { server.options.numOutputBusChannels })
				++ " output channels").postln;
			stemTable !? {
				stemTable.keys.do { |key, i|
					key !? { ("    stem " ++ i ++ "  channels " ++ (stemOut + (2 * i))
						++ "-" ++ (stemOut + (2 * i) + 1) ++ "  ~" ++ key).postln };
				};
			};
		};
		if(warnings.notEmpty) {
			("  warned: " ++ warnings.values.join("; ")).postln;
		};
		^this
	}

	// ----- pure helpers (unit tested) ---------------------------------------

	// "/syn", "kr", \k  ->  "/syn/kr/k"
	*address { |root ... parts|
		^(root.asString ++ "/" ++ parts.collect { |x| this.sanitize(x) }.join("/"))
	}

	// an OSC address part: letters, digits, _ - . survive; anything else becomes _
	*sanitize { |name|
		^name.asString.collect { |c|
			if(c.isAlphaNum or: { "_-.".includes(c) }) { c } { $_ }
		}
	}

	*isHidden { |key| ^key.asString.beginsWith(prefix) }

	// Is the proxy being heard? Its Monitor has a group from the moment .play is
	// called (even before the quant boundary, so a pattern's first hit is not lost
	// waiting for the server to confirm) and loses it the instant .stop is called.
	// monitor.isPlaying would answer true only after the server's /n_go reply — a
	// fifth of a second in which the downbeat of a new brush would go unreported.
	*isAudible { |proxy|
		var monitor = proxy.monitor;
		^monitor.notNil and: { monitor.group.notNil }
	}

	*hitAddress { |material, root| ^this.address(root ? this.root ? "/syn", "hit", material) }
	*noteAddress { |material, root| ^this.address(root ? this.root ? "/syn", "note", material) }
	*krAddress { |key, root| ^this.address(root ? this.root ? "/syn", "kr", key) }
	*krnAddress { |key, root| ^this.address(root ? this.root ? "/syn", "krn", key) }
	*goneAddress { |key, root| ^this.address(root ? this.root ? "/syn", "gone", key) }
	*paintAddress { |tool, root| ^this.address(root ? this.root ? "/syn", "paint", tool) }

	// the address family an event's messages hang under — "/syn/hit/k",
	// "/syn/note/ping", "/syn/paint/brush" — or nil for an event no brush made
	*eventBase { |ev, root|
		var brush = ev[\syn_brush], material = ev[\syn_material];
		if(brush.isNil or: { material.isNil }) { ^nil };
		root = root ? this.root ? "/syn";
		if(Paint.tools.includesKey(brush)) { ^this.paintAddress(brush, root) };
		^switch(brush,
			\hits, { this.hitAddress(material, root) },
			\chop, { this.hitAddress(material, root) },
			\deg, { this.noteAddress(material, root) },
			\chord, { this.noteAddress(material, root) },
			{ nil }
		)
	}

	// The messages one event produces, as [[address, arg, arg...], ...], or nil for a
	// rest or an event that did not come from a brush. Pure: no server, no state. One
	// value per address (see the header), the trig's 1 last; the matching 0 is the
	// sender's job (prOnEvent). beatDur turns the event's dur in beats into seconds;
	// width is how many note slots this voice already shows (prOnEvent keeps it).
	//   hits/chop     amp, index (the sample picked, or the slice), dur, trig
	//   deg / chord   amp, notes (MIDI notes, lowest first — noteSlotsFor), dur, trig
	//   paint tools   as deg/chord, plus hue, sat, val: the stroke's colour
	*eventMessages { |ev, root, beatDur = 1, width = 0|
		var brush = ev[\syn_brush];
		var base = this.eventBase(ev, root);
		var amp, dur, pitches, msgs;
		base ?? { ^nil };
		if(ev.isRest) { ^nil };
		amp = ev.use { ~amp.value } ? 0;
		dur = ev[\dur];
		dur = if(dur.isNumber) { dur } { dur.value ? 0 };
		msgs = [[base ++ "/amp", amp]];
		if((brush == \hits) or: { brush == \chop }) {
			msgs = msgs.add([base ++ "/index", ev[\syn_index] ? ev[\slice] ? 0]);
		} {
			pitches = if(Paint.tools.includesKey(brush)) {
				ev.use { ~freq.value }.asArray.collect(_.cpsmidi)   // a cloud sets freq alone
			} {
				ev.use { ~midinote.value }.asArray
			};
			msgs = msgs.add([base ++ "/notes"] ++ this.noteSlotsFor(pitches, width));
		};
		msgs = msgs.add([base ++ "/dur", dur * beatDur]);
		if(Paint.tools.includesKey(brush)) {
			msgs = msgs ++ [[base ++ "/hue", ev[\hue] ? 0], [base ++ "/sat", ev[\sat] ? 0],
				[base ++ "/val", ev[\val] ? 0]];
		};
		^msgs.add([base ++ "/trig", 1])
	}

	// the notes message's values: the pitches lowest first, padded with 0 to `width`
	// (the widest this voice has sent) so a smaller chord overwrites every slot a
	// bigger one used, and never more than noteSlots wide (a chord wider than that
	// keeps its lowest notes). A receiver that keeps one channel per argument then
	// shows notes1 alone for a single-note voice, notes1-4 for four-note chords.
	*noteSlotsFor { |pitches, width = 0|
		var sorted = pitches.asArray.sort.keep(noteSlots);
		^sorted.extend(max(sorted.size, width ? 0).min(noteSlots), 0)
	}

	// how long a pulse stays up: the pulse width, or half the gap to this voice's
	// next event when that is shorter, so every event still has a falling edge
	// before the next one (and never under 2 ms)
	*pulseWidth { |gapSeconds, width|
		width = width ? pulse ? 0.05;
		gapSeconds ?? { ^width };
		^min(width, gapSeconds * 0.5).max(0.002)
	}

	// the bar phase, 0..1, `aheadBeats` earlier than the clock's position (the
	// clock runs the server's latency ahead of the sound). Pure.
	*barPhase { |beatInBar, beatsPerBar = 4, aheadBeats = 0|
		^((beatInBar - aheadBeats) % beatsPerBar) / beatsPerBar
	}

	// which grid pulses fire at a position in the bar: every note value in `phases`
	// whose boundary falls here (to a 16th of a 16th). Pure, so the alignment is
	// unit-tested: beat 0 -> all four; 1.5 -> eighth and sixteenth; 2 -> half,
	// quarter, eighth, sixteenth; 0.25 -> sixteenth alone.
	*pulsesAt { |beatInBar|
		^phases.select { |assoc| (beatInBar / assoc.value).frac.round(1e-6) % 1 == 0 }
			.collect(_.key)
	}

	// the control-rate proxies a walk of an environment would tap: sources set,
	// control rate, not hidden — sorted so two walks of the same set compare equal
	*krKeysOf { |envir|
		var keys = List.new;
		envir.keysValuesDo { |key, proxy|
			if(this.isHidden(key).not and: { proxy.source.notNil }
				and: { proxy.rate == \control }) { keys.add(key) };
		};
		^keys.asArray.sort { |a, b| a.asString < b.asString }
	}

	// (added: keys new in `now`, gone: keys that left) — the diff a rewalk acts on
	*krDiff { |then, now|
		^(
			added: now.reject { |k| then.includes(k) },
			gone: then.reject { |k| now.includes(k) }
		)
	}

	// What the stem walker does with one proxy. A stem is assigned the first time an
	// audio proxy is heard (monitored), kept through stop/play and source swaps as
	// long as the proxy has a source, and released when the source goes nil (.clear,
	// or `~a = nil`). Pure, so the stability rules are unit-tested.
	*stemDecision { |hasIndex, hasSource, isAudio, monitoring|
		if(hasSource.not) { ^if(hasIndex) { \release } { \ignore } };
		if(hasIndex) { ^\keep };
		if(isAudio and: { monitoring }) { ^\assign };
		^\ignore
	}

	// a NetAddr from a NetAddr, "host", "host:port" or [host, port]; nil if unresolvable
	*resolveAddr { |addr, defaultPort = 9000|
		var host, port, parts;
		if(addr.isKindOf(NetAddr)) { ^addr };
		if(addr.isNil) { ^nil };
		if(addr.isSequenceableCollection and: { addr.isString.not }) {
			host = addr[0]; port = addr[1] ? defaultPort;
		} {
			parts = addr.asString.split($:);
			host = parts[0];
			port = if(parts.size > 1) { parts[1].asInteger } { defaultPort };
		};
		^try { NetAddr(host.asString, port.asInteger) } { nil }
	}

	// ----- events -------------------------------------------------------------

	*prStartEvents {
		var proto = Event.parentEvents[\default];
		if(hooked) { ^this };
		origPlay = proto[\play];
		// `play` runs inside event.use, so currentEnvironment is the event here
		proto[\play] = {
			origPlay.value;
			if(~syn_brush.notNil) { Broadcast.prOnEvent(currentEnvironment) };
		};
		hooked = true;
	}

	// Called from the event's play path, at the event's logical time, right after the
	// server bundle for it was sent `~latency` ahead. Allocates the messages and one
	// scheduling thunk, nothing else.
	//
	// Events follow audibility like stems do: JITLib puts the hosting proxy in ~proxy,
	// and a brush playing into a stopped proxy (after .stop or hush) keeps running
	// unheard — so it sends nothing. A pattern played outside any proxy has no ~proxy
	// and is always heard, so it always sends.
	*prOnEvent { |ev|
		var msgs, delay, clock, proxy, beatDur, base, trigAddr, width;
		if(running.not or: { events.not }) { ^this };
		if(ev.isRest) { ^this };
		proxy = ev[\proxy];
		if(proxy.notNil and: { this.isAudible(proxy).not }) { ^this };
		base = this.eventBase(ev);
		base ?? { ^this };
		clock = thisThread.clock;
		beatDur = clock.tryPerform(\beatDur) ? 1;
		noteWidths ?? { noteWidths = Dictionary.new };
		msgs = this.eventMessages(ev, nil, beatDur, noteWidths[base] ? 0);
		msgs ?? { ^this };
		// remember how wide this voice's notes now are, so a smaller chord later still
		// overwrites every slot (the width only grows, until the next start)
		msgs.detect { |m| m[0].endsWith("/notes") } !? { |m| noteWidths[base] = m.size - 1 };
		delay = (ev[\latency] ? ev[\server].latency ? 0)
			+ (ev[\lag] ? 0)
			+ ((ev[\timingOffset] ? 0) * beatDur);
		// the trig's 0 follows its 1 by the pulse width, unless a newer event of this
		// voice has pulsed since (then that event's own 0 follows). The token is taken
		// when the 1 goes out, not when the event is scheduled: an event is scheduled
		// `delay` before it sounds, and taking it then would cancel the previous
		// pulse's 0 whenever the gap equals delay + width.
		trigAddr = base ++ "/trig";
		width = this.pulseWidth(msgs.detect { |m| m[0].endsWith("/dur") } !? { |m| m[1] });
		if(timetags) {
			this.prSendBundle(delay, msgs);
			SystemClock.sched(delay, { this.prPulseOff(trigAddr, width); nil });
		} {
			SystemClock.sched(delay, { this.prSendAll(msgs); this.prPulseOff(trigAddr, width); nil });
		};
	}

	// schedule a pulse's 0 `width` from now, cancelling any earlier one still
	// pending for the address (the newer pulse is the one that must fall)
	*prPulseOff { |address, width|
		var token = (pulseTokens[address] ? 0) + 1;
		pulseTokens[address] = token;
		SystemClock.sched(width, {
			if(running and: { pulseTokens[address] == token }) { this.prSend(address, 0) };
			nil
		});
	}

	*prSendAll { |msgs|
		try {
			msgs.do { |m| addr.sendMsg(*m) };
		} { |err| this.prSendFailed(err) };
	}

	*prSendBundle { |delay, msgs|
		try {
			addr.sendBundle(delay, *msgs);
		} { |err| this.prSendFailed(err) };
	}

	*prSend { |address ... args|
		try {
			addr.sendMsg(address, *args);
		} { |err| this.prSendFailed(err) };
	}

	*prSendFailed { |err|
		this.prWarnOnce(\send, "Broadcast: cannot send to " ++ addr.ip ++ ":" ++ addr.port
			++ " (" ++ err.errorString ++ ") — the receiver gets nothing; the music is unaffected");
	}

	// ----- continuous ------------------------------------------------------------

	*krCmd { ^("/" ++ prefix ++ "kr").asSymbol }
	*krKey { ^(prefix ++ "kr").asSymbol }

	*prStartKr {
		var clock = space.clock ?? { space.makeTempoClock; space.clock };
		krKeys = [];
		krGens = IdentityDictionary.new;
		krResponder = OSCFunc({ |msg| this.prFanOut(msg) }, this.krCmd, space.server.addr);
		// Half a beat after every beat: a proxy made at any moment is seen within a
		// beat, and the swap it causes is quant-aligned by the tap proxy itself — so a
		// new kr proxy is on the wire within one bar, with no glitch anywhere (the tap
		// has no audio to glitch).
		krWalker = Routine {
			loop {
				if(running and: { space.server.serverRunning }) { this.prWalkKr };
				1.wait;
			}
		}.play(clock, quant: [1, 0.5]);
		// the grid pulses: on every sixteenth of the clock, a 1 for each note value whose
		// boundary this is, and its 0 after the pulse width (or half a sixteenth). Sent
		// the server's latency late, like events, so a pulse lands on the audible beat.
		gridPulser = Routine {
			loop {
				if(running) {
					var fired = this.pulsesAt(clock.beatInBar);
					var width = this.pulseWidth(0.25 * clock.beatDur);
					if(fired.notEmpty) {
						SystemClock.sched(this.prLatency, {
							try {
								fired.do { |name| addr.sendMsg(pulseAddrs[name], 1) };
							} { |err| this.prSendFailed(err) };
							fired.do { |name| this.prPulseOff(pulseAddrs[name], width) };
							nil
						});
					};
				};
				0.25.wait;
			}
		}.play(clock, quant: 0.25);
	}

	// how far ahead of the sound the language runs: the server's latency
	*prLatency { ^(space !? { space.server.latency }) ? 0 }

	*prWalkKr {
		var keys = this.krKeysOf(space.envir);
		var diff;
		if(keys != krKeys) {
			diff = this.krDiff(krKeys, keys);
			this.prBuildKrTap(keys);
			diff[\gone].do { |key| this.prSend(this.goneAddress(key)) };
			krKeys = keys;
		};
	}

	// one hidden kr proxy whose source is a SendReply over every tapped proxy. The
	// reply ID is a generation number, so the handler always knows which key list a
	// message belongs to — even one from the old synth arriving around a swap.
	*prBuildKrTap { |keys|
		var proxies = keys.collect { |k| space.envir[k] };
		var tap = space[this.krKey];
		var gen, hz = rate, cmd = this.krCmd;
		krGen = krGen + 1;
		gen = krGen;
		krGens[gen] = keys;
		krGens.keys.do { |g| if(g < (gen - 1)) { krGens.removeAt(g) } };
		keys.do { |key|
			krAddrs[key] ?? { krAddrs[key] = this.krAddress(key) };
			krnAddrs[key] ?? { krnAddrs[key] = this.krnAddress(key) };
		};
		// no crossfade: the old sender is freed in the same bundle the new one starts
		// in, so no tick is ever reported twice
		tap.fadeTime = 0;
		tap.source = {
			var vals = if(proxies.isEmpty) { [DC.kr(0)] } { proxies.collect { |pr| pr.kr(1) } };
			SendReply.kr(Impulse.kr(hz), cmd, vals, gen);
			DC.kr(0)
		};
	}

	// [cmd, nodeID, replyID (= generation), value, value, ...] -> one message per key
	*prFanOut { |msg|
		var keys = krGens !? { krGens[msg[2]] };
		var clock, spec;
		if(running.not or: { keys.isNil }) { ^this };
		try {
			keys.do { |key, i|
				var v = msg[3 + i];
				v !? {
					addr.sendMsg(krAddrs[key], v);
					if(krn) {
						spec = Spec.specs[key];
						spec !? { addr.sendMsg(krnAddrs[key], spec.unmap(v)) };
					};
				};
			};
			clock = space.clock;
			clock !? {
				addr.sendMsg(tempoAddr, clock.tempo * 60);
				// the bar phase at the audible time: the clock runs `latency` ahead of
				// the sound, as the events and grid pulses already allow for
				addr.sendMsg(barAddr, this.barPhase(clock.beatInBar, clock.beatsPerBar,
					this.prLatency * clock.tempo));
			};
		} { |err| this.prSendFailed(err) };
	}

	// ----- stems -------------------------------------------------------------------

	*stemKey { |index| ^(prefix ++ "stem" ++ index).asSymbol }
	*mainDefName { ^(prefix ++ "main").asSymbol }

	*prStartStems {
		var server = space.server;
		var available = server.options.numOutputBusChannels;
		var room = ((available - stemOut) div: 2) - 1;    // pairs after the main mix
		if(room < 0) {
			this.prWarnOnce(\outputs, "Broadcast: the server has " ++ available
				++ " output channels — no room for stems from channel " ++ stemOut
				++ ". Boot on the aggregate device (interface + BlackHole) for stems.");
			stems = false;
			^this
		};
		if(room < maxStems) {
			this.prWarnOnce(\outputs, "Broadcast: the server has " ++ available
				++ " output channels — room for " ++ room ++ " stem(s) + main from channel "
				++ stemOut ++ ", not " ++ maxStems ++ ". Capped.");
			maxStems = room;
		};
		stemTable = BroadcastStemTable(maxStems);
		stemGains = Array.newClear(maxStems + 1);
		mainDef = SynthDef(this.mainDefName, { |out = 2|
			// at the head of the root node, bus 0 still holds last cycle's finished mix
			Out.ar(out, InFeedback.ar(0, 2))
		});
		stemWalker = Routine {
			loop {
				if(running and: { server.serverRunning }) { this.prWalkStems };
				0.1.wait;
			}
		}.play(SystemClock);
		this.prPublishStems;
	}

	*prWalkStems {
		var changed = false;
		var server = space.server;
		if(mainSynth.isNil) { this.prStartMain(server) };
		space.envir.keysValuesDo { |key, proxy|
			var index, decision;
			if(this.isHidden(key).not) {
				index = stemTable.indexOf(proxy);
				decision = this.stemDecision(index.notNil, proxy.source.notNil,
					proxy.rate == \audio, this.isAudible(proxy));
				switch(decision,
					\assign, {
						index = this.prAssignStem(proxy, key);
						index !? { changed = true };
					},
					\release, {
						this.prReleaseStem(proxy, index, key);
						changed = true;
					}
				);
				index !? { this.prTrackGain(proxy, index) };
			};
		};
		// a proxy that left the environment altogether (p.envir.removeAt) frees its stem
		stemTable.proxies.do { |proxy|
			if(space.envir.includes(proxy).not) {
				this.prReleaseStem(proxy, stemTable.indexOf(proxy), stemTable.keyOf(proxy));
				changed = true;
			};
		};
		if(changed) { this.prPublishStems };
	}

	// the main-mix tap: at the head of the root node, before the default group and the
	// limiter, so InFeedback on bus 0 reads the previous cycle's finished output
	*prStartMain { |server|
		if(mainPending) { ^this };
		mainPending = true;
		Routine {
			var name = this.mainDefName;
			mainDef.send(server);
			server.sync;
			if(running and: { stems }) {
				mainSynth = Synth.head(RootNode(server), name, [\out, stemOut]);
			};
			mainPending = false;
		}.play(SystemClock);
	}

	*prAssignStem { |proxy, key|
		var index = stemTable.assign(proxy, key);
		var tap;
		index ?? {
			this.prWarnOnce(\stemsFull, "Broadcast: all " ++ maxStems ++ " stems are taken — ~"
				++ key ++ " gets none (maxStems is a start setting)");
			^nil
		};
		tap = space[this.stemKey(index)];
		tap.quant = 0;             // a stem appears the moment its proxy is heard
		tap.fadeTime = 0.02;
		tap.source = { proxy.ar(2) * Lag.kr(\bc_gain.kr(0), \bc_lag.kr(0.02)) };
		tap.play(stemOut + (2 * index), 2, fadeTime: 0.02);
		stemGains[index] = nil;
		^index
	}

	*prReleaseStem { |proxy, index, key|
		stemTable.release(proxy);
		index !? {
			space[this.stemKey(index)].clear;
			stemGains[index] = nil;
		};
		key !? { this.prSend(this.goneAddress(key)) };
	}

	// gain follows the Monitor: its vol while playing, 0 when stopped, lagged by the
	// proxy's fadeTime — or by the time hush was given, while that hint is fresh
	*prTrackGain { |proxy, index|
		var monitor = proxy.monitor;
		var gain, lag;
		gain = if(this.isAudible(proxy)) {
			if(monitor.vol.isNumber) { monitor.vol } { 1 }
		} { 0 };
		if(stemGains[index] != gain) {
			lag = if(gain == 0 and: { hushLagUntil.notNil } and: { Main.elapsedTime < hushLagUntil }) {
				hushLag
			} {
				proxy.fadeTime
			};
			space[this.stemKey(index)].set(\bc_lag, lag.max(0.001), \bc_gain, gain);
			stemGains[index] = gain;
		};
	}

	*prPublishStems {
		stemTable !? { this.prSend(stemsAddr, *stemTable.keys.collect { |k| k ? '-' }) };
	}

	// hush tells Broadcast its fade time, so stems can follow a hush(15) instead of
	// guessing the proxy's fadeTime. Not a performance word: hush is, and calls this.
	*prFadeHint { |seconds|
		hushLag = seconds;
		hushLagUntil = Main.elapsedTime + 1.0;
	}

	// ----- heartbeat, warnings ------------------------------------------------------

	*prStartHeartbeat {
		heartbeat = Routine {
			loop {
				if(running) { this.prSend(aliveAddr, (Main.elapsedTime - startedAt).round(0.001)) };
				1.wait;
			}
		}.play(SystemClock);
	}

	// one warning per condition for the life of a start
	*prWarnOnce { |condition, text|
		if(warnings[condition].isNil) {
			warnings[condition] = text;
			text.warn;
		};
	}

	*prStreamNames {
		var names = List.new;
		if(events) { names.add("events") };
		if(kr) { names.add("continuous") };
		if(stems) { names.add("stems") };
		^if(names.isEmpty) { "no streams" } { names.join(", ") }
	}
}


// The stem assignment table: index -> proxy key, proxy -> index. Index 0 is always
// the main mix. An index is given out once per proxy for the life of the table and
// handed back only by release (which the walker calls when the proxy's source goes
// nil), so the receiver can learn "stem 3 is the kick" once. A released index is
// reused by the next new proxy. Pure bookkeeping — unit tested.
BroadcastStemTable {
	var <size, <keys, indices;

	*new { |size = 8| ^super.newCopyArgs(size).init }

	init {
		keys = Array.newClear(size + 1);
		keys[0] = \main;
		indices = IdentityDictionary.new;
	}

	// the index this proxy holds, or nil
	indexOf { |proxy| ^indices[proxy] }

	keyOf { |proxy| ^indices[proxy] !? { |i| keys[i] } }

	proxies { ^indices.keys.asArray }

	// the proxy's index, assigning the lowest free one if it has none; nil when full
	assign { |proxy, key|
		var index = indices[proxy];
		index !? { ^index };
		index = (1..size).detect { |i| keys[i].isNil };
		index ?? { ^nil };
		keys[index] = key;
		indices[proxy] = index;
		^index
	}

	release { |proxy|
		var index = indices.removeAt(proxy);
		index !? { keys[index] = nil };
		^index
	}

	count { ^indices.size }
}
