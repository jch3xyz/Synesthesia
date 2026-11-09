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
//   Break.scan(dir)        scan <dir>/breaks*\/ folders and load them (Setup calls this)
//   Break.addLibrary(dir)  index a whole sample library, any depth, loading on first use
//   Break.list("amen")     post the library (or the matching part) with inferred tempos
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
	classvar <>libraryExclude;      // folder names addLibrary always skips (case-insensitive)
	classvar tempoWatcher, serverWatcher, lastTempo, sidecarName = "_breaks.scd";
	classvar offGridCount;          // non-nil while addLibrary runs: counts instead of warning

	*initClass {
		all = IdentityDictionary.new;
		order = [];
		voices = IdentityDictionary.new;
		windows = IdentityDictionary.new;
		bpmWindow = [60, 200];
		audioExtensions = ["wav", "aiff", "aif", "flac"];
		// Sample packs ship their loops pre-sliced in folders with these names. A
		// slice is a one-shot, not a loop — the engine makes its own slices anyway.
		libraryExclude = ["chops", "one shots", "one-shots", "oneshots"];
	}

	// ----- scanning -------------------------------------------------------

	// Scan a samples root for breaks* folders. Also accepts a breaks folder
	// directly, so Break.scan("~/loops/amen".standardizePath) works.
	*scan { |dir, server|
		var root, folders, loaded = 0, tagged = 0;
		server = server ? Server.default;
		this.prWatchServer;
		this.clear;
		this.prMakeWindows(server);
		dir ?? { ^this };
		// Trailing slash on purpose: PathName:folderName is the segment between the last
		// two slashes, so without it a folder handed in directly ("~/loops/breaks175")
		// would answer its PARENT's name, and lose the tempo tag in its own.
		root = PathName(dir.standardizePath.withTrailingSlash);
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

	// Index a sample library of any depth WITHOUT loading it. Every folder under
	// `dir` that holds audio files becomes a breaks folder: its own name feeds tempo
	// inference ("93 BPM/" tags every file inside it) and its own _breaks.scd sidecar
	// applies. A folder whose name is in `exclude` or libraryExclude is skipped with
	// everything beneath it — that is how one-shot folders stay out.
	//
	// Nothing reaches the server here. Each break is loaded the first time something
	// plays it (brk, chop, .buf — all go through bufOf), so a library of a thousand
	// loops costs a thousand header reads at boot instead of gigabytes of memory.
	// Use preload for the breaks a set opens with.
	//
	// Additive: call it after scan, which clears the registry first.
	*addLibrary { |dir, exclude, server|
		var root, skip, walk, before = order.size, folders = 0, skipped = List.new;
		server = server ? Server.default;
		this.prWatchServer;
		if(windows.isEmpty and: { server.serverRunning }) { this.prMakeWindows(server) };
		dir ?? { ^this };
		root = PathName(dir.standardizePath.withTrailingSlash);   // see scan
		if(root.isFolder.not) {
			("Break.addLibrary: not a folder — " ++ dir).warn;
			^this
		};
		skip = (exclude ? []).asArray ++ libraryExclude;
		offGridCount = 0;
		walk = { |folder|
			var files, sidecar;
			if(this.prExcluded(folder.folderName, skip)) {
				skipped.add(folder.folderName);
			} {
				files = this.prAudioFiles(folder);
				if(files.notEmpty) {
					folders = folders + 1;
					sidecar = this.prReadSidecar(folder.fullPath);
					files.do { |file| this.prAddFile(file, folder, sidecar, server, true) };
				};
				folder.folders.do(walk);
			};
		};
		protect {
			walk.(root);
		} {
			("Breaks: indexed " ++ (order.size - before) ++ " loop(s) in " ++ folders
				++ " folder(s) of " ++ root.folderName ++ " — loaded on first use").postln;
			if(skipped.notEmpty) {
				("  skipped " ++ skipped.size ++ " folder(s) named "
					++ skipped.asArray.asSet.asArray.sort.join(", ")).postln;
			};
			if(offGridCount > 0) {
				("  " ++ offGridCount ++ " loop(s) are not cut to whole beats at their"
					++ " tagged tempo — rounded; fix any that drift with \\key.len_(n)").postln;
			};
			offGridCount = nil;
		};
		^this
	}

	// is this folder name on the exclude list? (pure — unit tested)
	*prExcluded { |folderName, names|
		var name = folderName.asString.toLower;
		^names.asArray.any { |n| n.asString.toLower == name }
	}

	// The entry's mono Buffer, loading it now if it was indexed by addLibrary.
	// The Buffer object comes back at once (its bufnum is allocated language-side);
	// the samples arrive a moment later. Every word that plays a break asks here.
	*bufOf { |key|
		var entry = this.at(key), server = this.server;
		entry ?? { ^nil };
		entry[\buf] !? { ^entry[\buf] };
		if(server.serverRunning.not) {
			("Break: cannot load \"" ++ key ++ "\" — the server is not running").warn;
			^nil
		};
		entry[\buf] = this.prLoadMono(entry[\path], entry[\chans], server);
		entry[\buf] ?? { ("Break: could not load " ++ entry[\file]).warn };
		^entry[\buf]
	}

	// Load library breaks ahead of time — the ones a set opens with — so their very
	// first bar is never the one the samples are still arriving in.
	*preload { |...keys|
		^keys.flat.collect { |key| this.bufOf(key) }
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

	// A server quit invalidates every Buffer we hold, so the whole library goes with
	// it — a rescan is needed anyway once the server is back, and Setup does that at
	// boot. Two things matter here:
	//
	// Nothing is FREED. After the next boot those bufnums get reissued to whatever
	// loads first, so freeing a stale object would free somebody else's buffer.
	//
	// And the registry is emptied rather than left holding entries with nil buffers.
	// Half-alive entries made the vocabulary throw raw doesNotUnderstand errors
	// ("Message 'bufnum' not understood") on the next `brk`; empty means the words hit
	// their own "no breaks loaded — Break.scan(...)" path, which says what to do.
	*prWatchServer {
		serverWatcher ?? {
			serverWatcher = { |server|
				var had = order.size;
				all.clear;
				order = [];
				windows.clear;
				voices.clear;
				if(had > 0) {
					("Break: server stopped — " ++ had
						++ " break(s) dropped. Re-run Setup (or Break.scan) after booting.").postln;
				};
			};
			ServerQuit.add(serverWatcher);
		};
		^serverWatcher
	}

	// Compared as Symbols on purpose: Collection:includes tests IDENTITY, so
	// ["wav"].includes("wav") is false — two equal Strings are not the same object.
	// Symbols are interned, so identity and equality coincide. (includesEqual would
	// also work; Symbols additionally let a user set either form on audioExtensions.)
	*prAudioFiles { |pathName|
		var exts = audioExtensions.collect { |e| e.asString.toLower.asSymbol };
		^pathName.files.select { |f| exts.includes(f.extension.toLower.asSymbol) }
	}

	// lazy = index only (addLibrary): the buffer is loaded later, by bufOf
	*prAddFile { |file, folder, sidecar, server, lazy = false|
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
		if(lazy.not) {
			entry[\buf] = this.prLoadMono(path, chans, server);
			entry[\buf] ?? { ("Break: could not load " ++ file.fileName).warn; ^nil };
		};
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
			// indexing a whole library: one summary line instead of hundreds
			if(offGridCount.notNil) { offGridCount = offGridCount + 1; ^beats.asInteger };
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
			// Longer than 32 beats at any plausible tempo — a whole track, not a loop.
			// The beat-count search has nothing to offer, but a bare number in the name
			// ("thesqueeze samp 80") is now the best evidence there is.
			if(hint.notNil and: { this.prPlausible(hint) }) {
				^(bpm: hint, beats: this.beatsFor(hint, dur), src: \hint)
			};
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
		// live voices playing this break pick the new length up immediately —
		// including the slice grid, which is derived from the beat count, and the
		// sequence, which has to grow with it if it is still in source order
		voices.keysValuesDo { |proxy, voice|
			if(voice[\key] == entry[\key]) {
				voice[\steps] = (entry[\beats] * 4).min(seqSize);
				proxy.set(\beats, entry[\beats], \steps, voice[\steps]);
				if(voice[\ordered] == true and: { voice[\slen] != voice[\steps] }) {
					this.writeSeq(proxy, Array.series(voice[\steps], 0, 1), true);
				};
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
			// Written back: anything the user has corrected, AND anything whose key
			// differs from the one the filename would produce — i.e. a rename. A
			// rename-only sidecar entry keeps the inferred `src`, so filtering on
			// src == \sidecar alone silently destroyed hand-written renames the next
			// time anything in the folder was corrected.
			var renamed = entry[\key] != this.prKeyFor(entry[\file]);
			if((entry[\folder] == folderPath)
				and: { (entry[\src] == \sidecar) or: { renamed } }) {
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

	// Break.list posts everything; Break.list("funk") only the keys, files or
	// folders containing "funk" — the way to find one loop in a big library
	*list { |match|
		var keys = order, row;
		if(order.isEmpty) { ^"Breaks: none loaded — Break.scan(dir)".postln };
		match !? {
			match = match.asString.toLower;
			keys = order.select { |key|
				var e = all[key];
				[key.asString, e[\file].asString, (e[\folder] ? "").asString]
					.any { |s| s.toLower.contains(match) }
			};
			if(keys.isEmpty) { ^("Breaks: nothing matches \"" ++ match ++ "\"").postln };
		};
		// Every column ends in a space it can never fill, so a value wider than its
		// column (a long library key, a bpm like 180.6) pushes the row along instead
		// of running into the next value.
		row = { |key, bpm, beats, dur, src, file|
			(key.asString.padRight(16) ++ " " ++ bpm.asString.padRight(6) ++ " "
				++ beats.asString.padRight(5) ++ " " ++ dur.asString.padRight(7) ++ " "
				++ src.asString.padRight(9) ++ " " ++ file).postln;
		};
		row.("key", "bpm", "beats", "dur", "from", "file");
		keys.do { |key|
			var e = all[key];
			row.(key, e[\bpm].round(0.1), e[\beats], e[\dur].round(0.01).asString ++ "s",
				e[\src], e[\file]);
		};
		^keys
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
		voice !? { voice[\seq] !? { |b| if(b.bufnum.notNil) { b.free } } };
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
		var mk;
		server = server ? Server.default;
		if(server.serverRunning.not) {
			"Break: server not running — grain windows not allocated".warn;
			^windows
		};
		mk = { |env| Buffer.sendCollection(server, env.discretize(1024), 1) };
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
