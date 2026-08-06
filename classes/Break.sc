// Break — the breakbeat registry: language-side intelligence for the \break engine.
//
// A "break" is one drum-loop recording plus the two numbers the engine needs to
// lock it to the session: its length in BEATS, and its own BPM. Neither is in the
// audio file, so this class infers them at load time (filename tag -> folder tag ->
// duration heuristic) and lets a per-folder sidecar file override the guess
// permanently.
//
// Why a class and not a SynthDef: the def contract (Setup/SynthDefs/CONVENTIONS.md
// rule 5) forbids a def from touching interpreter state, so nothing server-side can
// know sample tempos, index the sample library, or hold a rewritable sequence.
// That intelligence lives here; the def is pure DSP driven by controls.
//
//   Break.scan(dir)        scan <dir>/breaks*\/ folders (Setup calls this)
//   Break.list             post the library with inferred tempos
//   Break.at(\amen)        the entry: (buf:, bpm:, beats:, dur:, path:, src:)
//   \amen.bpm_(172)        override + persist to the folder's _breaks.scd
//   ~d.brk(\amen)          play it (see extBreaks.sc for the vocabulary)

Break {
	classvar <all;                  // \key -> (buf:, bpm:, beats:, dur:, path:, file:, folder:, src:)
	classvar <order;                // keys in scan order — `~d.brk` with no argument cycles this
	classvar <voices;               // NodeProxy -> (key:, seq:, steps:, slen:)
	classvar <windows;              // \hann \punch \gate \swell -> grain-envelope Buffers
	classvar <>seqSize = 64;        // frames in a slice-sequence buffer (max sequence length)
	classvar <>folderPrefix = "breaks";
	classvar <>monoMode = \mix;     // \mix (true mixdown), \left, or \right
	classvar <>bpmWindow;           // plausible source tempos, used by the duration heuristic
	classvar <>audioExtensions;
	classvar tempoWatcher, lastTempo, sidecarName = "_breaks.scd";

	*initClass {
		all = IdentityDictionary.new;
		order = [];
		voices = IdentityDictionary.new;
		windows = IdentityDictionary.new;
		bpmWindow = [60, 200];
		audioExtensions = ["wav", "aiff", "aif", "flac"];
	}

	// ----- scanning -------------------------------------------------------

	// Scan a samples root for breaks* folders. Also accepts a breaks folder
	// directly, so Break.scan("~/loops/amen".standardizePath) works.
	*scan { |dir, server|
		var root, folders, loaded = 0, tagged = 0;
		server = server ? Server.default;
		this.clear;
		this.prMakeWindows(server);
		dir ?? { ^this };
		root = PathName(dir.standardizePath);
		if(root.isFolder.not) {
			("Break.scan: not a folder — " ++ dir).warn;
			^this
		};
		folders = root.folders.select { |f|
			f.folderName.toLower.beginsWith(folderPrefix)
		};
		// a folder handed to us directly (no breaks* children but audio inside)
		if(folders.isEmpty and: { this.prAudioFiles(root).notEmpty }) { folders = [root] };
		folders.do { |folder|
			var sidecar = this.prReadSidecar(folder.fullPath);
			this.prAudioFiles(folder).do { |file|
				var entry = this.prAddFile(file, folder, sidecar, server);
				entry !? {
					loaded = loaded + 1;
					if(entry[\src] == \sidecar) { tagged = tagged + 1 };
				};
			};
		};
		("Breaks: " ++ loaded ++ " loop(s) in " ++ folders.size ++ " folder(s) — "
			++ tagged ++ " tagged, " ++ (loaded - tagged) ++ " inferred").postln;
		if(loaded > 0) { ("  " ++ order.join(" ")).postln };
		^this
	}

	*clear {
		all.do { |entry|
			var buf = entry[\buf];
			if(buf.notNil and: { buf.bufnum.notNil }) { buf.free };
		};
		all.clear;
		order = [];
		^this
	}

	// Compared as Symbols on purpose: Collection:includes tests IDENTITY, so
	// ["wav"].includes("wav") is false — two equal Strings are not the same object.
	// Symbols are interned, so identity and equality coincide. (includesEqual would
	// also work; Symbols additionally let a user set either form on audioExtensions.)
	*prAudioFiles { |pathName|
		var exts = audioExtensions.collect { |e| e.asString.toLower.asSymbol };
		^pathName.files.select { |f| exts.includes(f.extension.toLower.asSymbol) }
	}

	*prAddFile { |file, folder, sidecar, server|
		var path = file.fullPath;
		var over = (sidecar !? { sidecar[file.fileName] }) ? ();
		var sf, dur, chans, key, guess, entry;
		sf = SoundFile.openRead(path);
		sf ?? { ("Break: could not read " ++ file.fileName).warn; ^nil };
		dur = sf.duration;
		chans = sf.numChannels;
		sf.close;
		if(dur <= 0) { ("Break: zero-length file " ++ file.fileName).warn; ^nil };

		key = this.prUniqueKey(over[\key] ?? { this.prKeyFor(file.fileName) });
		guess = this.inferTempo(file.fileName, folder.folderName, dur);
		entry = (
			key: key,
			path: path,
			file: file.fileName,
			folder: folder.fullPath,
			dur: dur,
			chans: chans,
			bpm: over[\bpm] ?? { guess[\bpm] },
			beats: over[\beats] ?? { over[\bpm] !? { this.beatsFor(over[\bpm], dur) } } ?? { guess[\beats] },
			src: if(over[\bpm].notNil or: { over[\beats].notNil }) { \sidecar } { guess[\src] }
		);
		entry[\buf] = this.prLoadMono(path, chans, server);
		entry[\buf] ?? { ("Break: could not load " ++ file.fileName).warn; ^nil };
		^this.prAdd(entry)
	}

	// the one place an entry enters the library
	*prAdd { |entry|
		all[entry[\key]] = entry;
		order = order.add(entry[\key]);
		^entry
	}

	// ...and the one place it leaves. Removing from `all` alone would leave a key in
	// `order` pointing at nothing, which then breaks Break.list and `brk` cycling.
	*remove { |key|
		var entry = all.removeAt(key.asSymbol);
		order = order.reject { |k| k == key.asSymbol };
		^entry
	}

	// filename -> a short lowercase symbol; the sidecar's `key:` overrides this
	*prKeyFor { |fileName|
		var stem = fileName.splitext[0].toLower;
		var out = "", lastUnderscore = true;
		stem.do { |c|
			case
			{ c.isAlphaNum } { out = out ++ c.asString; lastUnderscore = false }
			{ lastUnderscore.not } { out = out ++ "_"; lastUnderscore = true };
		};
		while { out.endsWith("_") } { out = out.drop(-1) };
		if(out.isEmpty) { out = "break" };
		if(out[0].isDecDigit) { out = "b" ++ out };
		^out.asSymbol
	}

	*prUniqueKey { |key|
		var base = key.asSymbol, n = 2, try;
		if(all[base].isNil) { ^base };
		try = (base.asString ++ n).asSymbol;
		while { all[try].notNil } {
			n = n + 1;
			try = (base.asString ++ n).asSymbol;
		};
		^try
	}

	// GrainBuf needs a MONO buffer (a stereo one renders silence), and the spatial
	// rule wants mono cores anyway. Default is a true mixdown, done language-side
	// with array slicing so it costs milliseconds, not a server graph.
	*prLoadMono { |path, chans, server|
		var sf, frames, n, data, mono;
		if(chans == 1) { ^Buffer.read(server, path) };
		if(monoMode == \left) { ^Buffer.readChannel(server, path, channels: [0]) };
		if(monoMode == \right) {
			^Buffer.readChannel(server, path, channels: [(chans - 1).min(1)])
		};
		sf = SoundFile.openRead(path);
		sf ?? { ^nil };
		frames = sf.numFrames;
		n = sf.numChannels;
		data = FloatArray.newClear(frames * n);
		sf.readData(data);
		sf.close;
		if(n == 2) {
			mono = ((data[0, 2 .. (frames - 1) * 2]
				+ data[1, 3 .. ((frames - 1) * 2) + 1]) * 0.5).as(Signal);
		} {
			mono = Signal.newClear(frames);
			frames.do { |i|
				var sum = 0;
				n.do { |c| sum = sum + data[(i * n) + c] };
				mono[i] = sum / n;
			};
		};
		^Buffer.loadCollection(server, mono, 1)
	}

	// ----- tempo inference (pure functions — unit tested) -----------------

	// Priority: a bpm-tagged number in the filename, then in the folder name,
	// then the duration heuristic (with any bare number in the filename used
	// only to break ties). Returns (bpm:, beats:, src:).
	*inferTempo { |fileName, folderName, dur|
		var tags = this.bpmTags(fileName);
		var folderTags = this.bpmTags(folderName);
		var hit, hint;
		hit = tags.detect { |t| t[\tagged] };
		if(hit.notNil) {
			^(bpm: hit[\num], beats: this.beatsFor(hit[\num], dur), src: \filename)
		};
		hit = folderTags.detect { |t| t[\tagged] }
			?? { folderTags.detect { |t| this.prPlausible(t[\num]) } };
		if(hit.notNil) {
			^(bpm: hit[\num], beats: this.beatsFor(hit[\num], dur), src: \folder)
		};
		hint = tags.detect { |t| this.prPlausible(t[\num]) };
		^this.bpmFromDur(dur, hint !? { |t| t[\num] })
	}

	*prPlausible { |bpm|
		^((bpm >= bpmWindow[0]) and: { bpm <= bpmWindow[1] })
	}

	// every digit run in a string, flagged if it sits next to "bpm"
	// ("175bpm", "175 BPM", "bpm175" are tagged; "250093" is not)
	*bpmTags { |string|
		var s = string.asString.toLower;
		var n = s.size, i = 0, out = List.new;
		var j, k, num, tagged, back;
		while { i < n } {
			if(s[i].isDecDigit) {
				j = i;
				while { (j < n) and: { s[j].isDecDigit } } { j = j + 1 };
				num = s.copyRange(i, j - 1).asInteger;
				// look forward past separators for "bpm"
				k = j;
				while { (k < n) and: { " _-.".includes(s[k]) } } { k = k + 1 };
				// note the parens: SuperCollider has no operator precedence, so
				// `k + 3 <= n` would evaluate as `(k + 3 <= n)` left to right
				tagged = ((k + 3) <= n) and: { s.copyRange(k, k + 2) == "bpm" };
				// look backward for "bpm175"
				if(tagged.not and: { i >= 3 }) {
					back = i;
					while { (back > 0) and: { " _-.".includes(s[back - 1]) } } { back = back - 1 };
					tagged = (back >= 3) and: { s.copyRange(back - 3, back - 1) == "bpm" };
				};
				out.add((num: num, tagged: tagged));
				i = j;
			} {
				i = i + 1;
			};
		};
		^out.asArray
	}

	// beats a loop of `dur` seconds holds at `bpm` — rounded, with a warning when
	// the file clearly is not cut to whole beats
	*beatsFor { |bpm, dur|
		var exact = dur * bpm / 60;
		var beats = exact.round.max(1);
		if((exact - beats).abs > (beats * 0.05)) {
			("Break: " ++ dur.round(0.001) ++ "s at " ++ bpm ++ " bpm is "
				++ exact.round(0.01) ++ " beats — not a whole number; using "
				++ beats ++ " (override with .beats_)").warn;
		};
		^beats.asInteger
	}

	// no tempo tag anywhere: assume the loop is cut to a whole number of beats and
	// pick the reading whose tempo is most break-plausible. `hint` (a bare number
	// from the filename) only breaks ties.
	*bpmFromDur { |dur, hint|
		var cands = List.new, pow2, pool, best;
		#[1, 2, 3, 4, 6, 8, 12, 16, 24, 32].do { |beats|
			var bpm = beats * 60 / dur;
			if(this.prPlausible(bpm)) { cands.add((bpm: bpm, beats: beats)) };
		};
		if(cands.isEmpty) {
			^(bpm: 120, beats: (dur * 2).round.max(1).asInteger, src: \fallback)
		};
		// a bare number in the filename settles it outright, if it matches a candidate
		if(hint.notNil) {
			best = cands.detect { |c| ((c[\bpm] / hint) - 1).abs < 0.02 };
			best !? {
				^(bpm: best[\bpm].round(0.01), beats: best[\beats], src: \hint)
			};
		};
		// Prefer a power-of-two beat count. Loops are cut in halves far more often
		// than in thirds, so a 2-second file is much more likely 4 beats at 120 than
		// 6 at 180 — which is what a nearest-to-150 score alone would have chosen.
		// The 3/6/12/24 candidates stay available for genuinely triple-metre loops.
		pow2 = cands.select { |c| c[\beats].isPowerOfTwo };
		pool = if(pow2.isEmpty) { cands } { pow2 };
		best = pool.minItem { |c| (c[\bpm] / 150).log.abs };
		^(bpm: best[\bpm].round(0.01), beats: best[\beats], src: \duration)
	}

	// ----- entries, overrides, persistence --------------------------------

	*at { |key| ^all[key.asSymbol] }

	*size { ^order.size }

	*keyAt { |index| ^order.wrapAt(index) }

	// nil -> the break after `current` (cycling); Integer -> by index; else by name
	*resolveKey { |name, current|
		var i;
		if(order.isEmpty) { ^nil };
		if(name.isNil) {
			i = order.indexOf(current);
			^if(i.isNil) { order[0] } { order.wrapAt(i + 1) }
		};
		if(name.isNumber) { ^order.wrapAt(name.asInteger) };
		^all[name.asSymbol].notNil.if({ name.asSymbol }, { nil })
	}

	*set { |key, bpm, beats|
		var entry = this.at(key);
		entry ?? { ("Break.set: no break \"" ++ key ++ "\"").warn; ^nil };
		bpm !? {
			entry[\bpm] = bpm;
			entry[\beats] = beats ?? { this.beatsFor(bpm, entry[\dur]) };
		};
		beats !? {
			var implied = (beats * 60 / entry[\dur]).round(0.01);
			entry[\beats] = beats.asInteger;
			if(bpm.isNil) {
				// bpm and beats are two views of one fact, tied together by the file's
				// duration, so setting one re-derives the other. Say so when that
				// silently replaces a tempo the user set a moment ago.
				if((entry[\bpm] - implied).abs > (implied * 0.02)) {
					("Break: " ++ entry[\key] ++ " at " ++ beats ++ " beats implies "
						++ implied ++ " bpm, replacing " ++ entry[\bpm]
						++ " (the two are linked by the file's length)").warn;
				};
				entry[\bpm] = implied;
			};
		};
		entry[\src] = \sidecar;
		this.prWriteSidecar(entry[\folder]);
		// live voices playing this break pick the new length up immediately
		voices.keysValuesDo { |proxy, voice|
			if(voice[\key] == entry[\key]) {
				proxy.set(\beats, entry[\beats]);
				this.trig(proxy, \resync);
			};
		};
		^entry
	}

	*prReadSidecar { |folderPath|
		var path = folderPath +/+ sidecarName;
		var dict;
		if(File.exists(path).not) { ^nil };
		dict = try { path.load };
		if(dict.isKindOf(Dictionary).not) {
			("Break: " ++ sidecarName ++ " in " ++ folderPath
				++ " did not evaluate to a Dictionary — ignoring").warn;
			^nil
		};
		^dict
	}

	// rewrite the folder's sidecar from the entries currently overridden.
	// A captured break has no folder, so there is nowhere to persist it — that is
	// not an error, just nothing to do.
	*prWriteSidecar { |folderPath|
		var path, lines = List.new, file;
		if(folderPath.isNil) { ^nil };
		path = folderPath +/+ sidecarName;
		all.do { |entry|
			if((entry[\folder] == folderPath) and: { entry[\src] == \sidecar }) {
				// a quote in a filename would otherwise produce a sidecar that fails
				// to compile, silently losing every override in the folder
				lines.add("\t\"" ++ entry[\file].asString.replace("\"", "\\\"")
					++ "\" -> (key: '" ++ entry[\key]
					++ "', bpm: " ++ entry[\bpm] ++ ", beats: " ++ entry[\beats] ++ ")");
			};
		};
		if(lines.isEmpty) { ^nil };
		file = File(path, "w");
		if(file.isOpen.not) { ("Break: cannot write " ++ path).warn; ^nil };
		file.write("// Break metadata — written by Break.set / \\key.bpm_ / \\key.beats_\n"
			"// Merged over inference at every boot. Safe to hand-edit.\n"
			"// `key:` also renames the break, which is how long filenames get short names.\n"
			"Dictionary[\n" ++ lines.join(",\n") ++ "\n]\n");
		file.close;
		("Break: wrote " ++ path).postln;
		^path
	}

	*list {
		if(order.isEmpty) { ^"Breaks: none loaded — Break.scan(dir)".postln };
		"key              bpm  beats  dur     from       file".postln;
		order.do { |key|
			var e = all[key];
			(key.asString.padRight(17)
				++ e[\bpm].round(0.1).asString.padRight(5)
				++ e[\beats].asString.padRight(7)
				++ (e[\dur].round(0.01).asString ++ "s").padRight(8)
				++ e[\src].asString.padRight(11)
				++ e[\file]).postln;
		};
		^order
	}

	// ----- live capture ---------------------------------------------------

	// register an already-recorded buffer (rec() captures need no inference —
	// they were recorded at the session tempo, so `beats` is exact)
	*capture { |key, buf, beats|
		var entry;
		buf ?? { "Break.capture: no buffer".warn; ^nil };
		key = this.prUniqueKey(key ? \live);
		entry = (
			key: key, buf: buf, beats: beats.asInteger, dur: buf.duration,
			bpm: (beats * 60 / buf.duration).round(0.01),
			path: nil, file: "(captured)", folder: nil, chans: buf.numChannels,
			src: \capture
		);
		this.prAdd(entry);
		^key
	}

	// ----- session state --------------------------------------------------

	*server { ^Server.default }

	// beats per second on the performance clock. Read directly rather than through
	// Synesthesia.space, which warns when there is no ProxySpace — this is polled
	// several times a second by the tempo watcher, and would flood the post window.
	*tempo {
		var space = thisProcess.interpreter.p;
		if(space.isKindOf(ProxySpace).not) { space = nil };
		^(space.tryPerform(\clock).tryPerform(\tempo)) ? 1
	}

	// the source-to-session speed ratio: play a break at this rate and it fits
	*stretchOf { |key|
		var entry = this.at(key);
		entry ?? { ^1 };
		^entry[\dur] * this.tempo / entry[\beats]
	}

	// every live break voice follows the clock. Called by `bpm()` for immediacy
	// and by a watcher for anything that changes the tempo behind its back.
	*tempo_ { |bps|
		lastTempo = bps;
		voices.keysDo { |proxy| proxy.set(\tempo, bps) };
		^bps
	}

	*prStartWatcher {
		tempoWatcher ?? {
			tempoWatcher = SkipJack({
				var t = this.tempo;
				if(t != lastTempo) { this.tempo_(t) };
			}, 0.2, name: "BreakTempo");
		};
		^tempoWatcher
	}

	// ----- voices ---------------------------------------------------------

	*voiceOf { |proxy| ^voices[proxy] }

	*keyOf { |proxy| ^voices[proxy] !? { |v| v[\key] } }

	// give a proxy the \break engine and its own slice-sequence buffer
	*register { |proxy, key|
		var voice = voices[proxy];
		var entry = this.at(key);
		var steps;
		entry ?? { ^nil };
		steps = (entry[\beats] * 4).min(seqSize);
		if(voice.isNil) {
			voice = (
				key: key,
				seq: Buffer.alloc(this.server, seqSize, 1),
				steps: steps,
				slen: steps,
				ordered: true
			);
			voice[\seq].setn(0, Array.series(seqSize, 0, 1));
			voices[proxy] = voice;
			proxy.source = \break;
			proxy.set(
				\sbuf, voice[\seq].bufnum,
				\slen, steps,
				\envbuf, (windows[\punch] !? { |b| b.bufnum }) ? -1,
				\tempo, this.tempo
			);
			this.prStartWatcher;
		} {
			voice[\key] = key;
			voice[\steps] = steps;
		};
		^voice
	}

	*unregister { |proxy|
		var voice = voices.removeAt(proxy);
		voice !? { voice[\seq].free };
		^voice
	}

	*freeAll {
		voices.keys.asArray.do { |proxy| this.unregister(proxy) };
		windows.do { |buf| if(buf.bufnum.notNil) { buf.free } };
		windows.clear;
		this.clear;
		tempoWatcher !? { tempoWatcher.stop };
		tempoWatcher = nil;
		^this
	}

	// ----- grain windows --------------------------------------------------

	// GrainBuf's built-in window is a Hann curve, which fades in every grain and
	// so mushes the transient a chop is mostly made of. These are per-grain
	// envelope shapes; the bufnum is polled per grain, so \win switches live.
	*prMakeWindows { |server|
		var mk = { |env| Buffer.sendCollection(server, env.discretize(1024), 1) };
		windows.do { |buf| if(buf.bufnum.notNil) { buf.free } };
		windows.clear;
		windows[\hann]  = Buffer.sendCollection(server, Signal.hanningWindow(1024), 1);
		// fast attack, long body, short curved tail — an MPC-style chop
		windows[\punch] = mk.(Env(#[0, 1, 1, 0], #[0.005, 0.965, 0.03], #[\lin, \lin, -2]));
		// near-rectangular: maximum punch, hard edges
		windows[\gate]  = mk.(Env(#[0, 1, 1, 0], #[0.002, 0.996, 0.002], \lin));
		// reverse swell
		windows[\swell] = mk.(Env(#[0, 1, 1, 0], #[0.6, 0.35, 0.05], #[2, \lin, -2]));
		^windows
	}

	*window { |name|
		^(windows[name] !? { |b| b.bufnum }) ?? {
			("Break.window: no window \"" ++ name ++ "\" — "
				++ windows.keys.asArray.join(" ")).warn;
			-1
		}
	}

	// ----- slice sequences (pure parser — unit tested) --------------------

	// "0.325" -> [0, -1, 3, 2, 5]. Digits then letters index slices (base 36),
	// `.` and `~` are rests, spaces and `|` are visual only. An Array passes
	// through, with nil / negative / Rest entries becoming rests.
	*parseSeq { |spec|
		var out = List.new;
		if(spec.isNil) { ^[] };
		if(spec.isKindOf(String).not and: { spec.isKindOf(Symbol).not }) {
			^spec.asArray.collect { |v|
				case
				{ v.isNil } { -1 }
				{ v.isKindOf(Rest) } { -1 }
				{ v.isNumber } { v.asInteger.max(-1) }
				{ -1 }
			}
		};
		spec.asString.do { |c|
			case
			{ c.isSpace or: { c == $| } } { }
			{ (c == $.) or: { c == $~ } } { out.add(-1) }
			{ c.isDecDigit } { out.add(c.digit) }
			{ c.isAlpha } { out.add(10 + (c.toLower.ascii - $a.ascii)) }
			{ ("Break.parseSeq: ignoring \"" ++ c.asString ++ "\"").warn };
		};
		^out.asArray
	}

	// write a sequence to a voice's buffer and tell the engine how long it is.
	// `ordered` marks the sequence as plain source order, so a later change of
	// `steps` can safely regenerate it at the new length.
	*writeSeq { |proxy, values, ordered = false|
		var voice = voices[proxy];
		var vals = values.asArray;
		if(voice.isNil) { ^nil };
		if(vals.isEmpty) { ^nil };
		if(vals.size > seqSize) {
			("Break: sequence truncated to " ++ seqSize ++ " steps").warn;
			vals = vals.keep(seqSize);
		};
		voice[\seq].setn(0, vals.collect(_.asFloat));
		voice[\slen] = vals.size;
		voice[\ordered] = ordered;
		proxy.set(\slen, vals.size);
		// a new sequence should start from its first step, on the bar
		this.trig(proxy, \resync);
		^vals
	}

	// Fire a trigger-rate control once, sent straight to the running synths via the
	// proxy's group.
	//
	// Deliberately NOT proxy.set: NodeProxy:set stores the value in the nodeMap,
	// which is replayed on every rebuild, so a \tr control left at 1 fires a phantom
	// trigger every time the proxy is re-evaluated or faded in. Following it with
	// unset removes that, but unset also sends the control's default (0) in a second
	// bundle — and if the two bundles land in the same control block the trigger can
	// be cancelled before it is ever seen. Setting on the group sidesteps both: the
	// running synths get the trigger, and the nodeMap is never written at all.
	//
	// A proxy that is not playing needs no trigger: its phase starts at 0 anyway.
	*trig { |proxy, key = \resync|
		var group = proxy.tryPerform(\group);
		if(group.notNil and: { proxy.isPlaying }) { group.set(key, 1) };
		^proxy
	}

	// ----- scheduling -----------------------------------------------------

	// run `func` on the next quant boundary of the performance clock, so
	// structural changes (a new break, a new sequence) land musically
	*schedBar { |func|
		var space = Synesthesia.space;
		var clock = space.tryPerform(\clock);
		var quant = (space.tryPerform(\quant) ? 4).asArray[0] ? 4;
		if(clock.isNil or: { quant == 0 }) { func.value; ^nil };
		clock.schedAbs(clock.nextTimeOnGrid(quant), { func.value; nil });
		^quant
	}
}
