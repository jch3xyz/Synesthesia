// Synesthesia step-string sugar on String — usable in any \dur socket.
//   "x.x. x.xx".steps       → duration Pseq (rests included)
//   "x.X.".stepAmps         → amplitude Pseq aligned with .steps

+ String {

	steps { |step = 0.25, amp = 1|
		var events = Synesthesia.parseHits(this, step, amp);
		^Pseq(events.collect { |e|
			if(e[\rest]) { Rest(e[\dur]) } { e[\dur] }
		}, inf)
	}

	stepAmps { |step = 0.25, amp = 1, accent = 1.4|
		var events = Synesthesia.parseHits(this, step, amp, accent);
		^Pseq(events.collect(_[\amp]), inf)
	}
}
