# Broadcast Manifest

Success parameters for the **Broadcast** feature of Synesthesia. This document
says what "done" looks like. It does not prescribe build order or sequencing.
Where it names a mechanism, that is a hint, not a requirement, unless marked
MUST.

## Purpose

Broadcast lets a second machine (a VJ running Resolume or MadMapper, connected
by Ethernet) receive everything musically meaningful that happens on the
Synesthesia canvas, with **zero words typed during the performance**. It is
configured once in the setup file and then watches the ProxySpace on its own.

Three streams leave the machine:

| Stream | Carries | Transport |
|---|---|---|
| Events | one message per hit/note from every brush | OSC over UDP |
| Continuous | every control-rate proxy's current value | OSC over UDP |
| Stems | one audio channel pair per audible proxy | multichannel audio via an aggregate device into SonoBus (or JackTrip) |

## Non-negotiables inherited from Synesthesia's design rules

1. **Rule 3 governs this whole feature.** Broadcast is auxiliary. It MUST NOT
   add any word a performer types mid-set. Its entire API is setup-time
   (`Broadcast.start(...)`, `.stop`, `.status`, per-stream enable flags).
2. **Broadcast MUST never break the music.** Receiver absent, wrong IP,
   SonoBus not running, aggregate device missing: the canvas sounds identical
   and the post window gets at most one warning per condition, never a stream
   of them. Every failure mode degrades to "Caleb gets less," never to "John
   hears less."
3. **Rule 1 still holds for anything Broadcast touches.** Patterns returned by
   `.hits`, `.deg`, `.chord`, `.chop` remain plain Pbinds. If Broadcast injects
   anything into them it MUST compose with user-supplied keys (a user's own
   `\callback` is wrapped, never replaced).
4. **Rule 2 is the data source, not something to reinvent.** Broadcast reads
   the NamedControls and proxies the vocabulary already creates. It does not
   maintain a parallel model of the canvas.
5. Broadcast's own internal proxies (if any) MUST be invisible to the rest of
   the vocabulary: `hush` must not stop them, the stem walker must not tap
   them, the kr walker must not report them. A reserved key prefix
   (`syn_bc_`) is the suggested mechanism; `hush` in `extInterpreter.sc` will
   need to learn to skip it.

## Stream 1: Events

### What counts as an event

Every event produced by a Symbol brush: `.hits` (step and euclid), `.deg`,
`.chord`, `.chop`. These all flow through `Pbind` and therefore through the
Event play machinery, which is where Broadcast hooks in.

Slice onsets inside the `\break` engine (`~d.brk`, `.grind`, `.seq`) are
server-side and do not produce Events. Broadcasting them is a stretch goal via
`SendReply` inside the `\break` SynthDef; the manifest does not require it.

### Success parameters

- One OSC message per sounding event. Rests produce nothing.
- The message identifies the **material**, not the proxy: the sample folder
  name for `.hits`/`.chop`, the SynthDef name for `.deg`/`.chord`. The Symbol
  is known at brush construction, so this needs no bookkeeping.
- Payload per event: amplitude (post-accent), and whichever of these exist:
  sample variant index, scale degree (or MIDI note for `.chord`), duration in
  beats.
- **Timing MUST be compensated for server latency.** The Event `\callback` key
  fires when the server message is sent, which is `s.latency` (typically 0.2 s)
  before the sound. Messages MUST arrive at the receiver within ±10 ms of the
  audible onset. Acceptable mechanisms: `SystemClock.sched(s.latency, ...)` or
  an OSC bundle with a timetag (check whether Resolume honors timetags; assume
  it does not).
- Verified by recording the stem output and the receiver's OSC log and
  comparing onset times.

## Stream 2: Continuous

### What counts as continuous

Every control-rate NodeProxy in `p.envir`: roots (`~r`), the `_env` followers
made by `.follow`, the `syn_pad*` proxies made by `pad`, the `syn_sig*`
derivatives made by `.mapExp`/`.mapLin`, and any kr proxy the performer makes
by hand. Plus the clock: tempo in BPM and beat-in-bar phase.

### Success parameters

- Discovery is automatic. The performer types
  `~a.fx(\lpf, pad(\x).mapExp(200, 8000))` and the new `syn_sig` proxy is on
  the wire within one bar with no further action.
- Every kr proxy is sampled at a configurable rate, default 30 Hz. Rate is a
  setup-time setting.
- Each value travels on its own stable OSC address (see Namespace) as a single
  float. This matches how Resolume/MadMapper "learn" mappings: one address,
  one parameter.
- Values are sent in the proxy's native range. A separate normalized variant
  (0..1 via the registered Spec where one exists) is a per-stream option so
  the receiver can choose which to learn.
- Rebuilding the tap when the proxy set changes MUST be quant-aligned and MUST
  NOT produce a glitch or dropout in the audio. Suggested mechanism: one hidden
  kr proxy whose source is a `SendReply.kr` over all current kr proxies, an
  `OSCdef` that fans out to the receiver, and a bar-rate routine that re-walks
  `p.envir` and swaps the source when the set differs.
- Proxies whose source becomes nil are dropped from the tap and announced once
  (see Namespace, `/syn/gone`).

## Stream 3: Stems

### How this differs from SuperDirt orbits, and why

In SuperDirt an orbit is a numbered output pair with its own global effects,
and the performer assigns each pattern to an orbit by hand (`# orbit 1`).
Synesthesia has no equivalent because it does not need one: every NodeProxy
already owns a private bus, and every brush is played *into* a proxy. The
proxy is the natural stem. Assigning stems by hand would violate Rule 3, so
Broadcast assigns them automatically by proxy identity.

The consequence: a stem is **a proxy, not a pattern**. If two brushes are
played into the same proxy they share a stem. That is the correct behavior.

### Success parameters

- Every audio-rate proxy that is currently monitored (has a playing Monitor)
  gets a stereo stem, up to a configurable cap (default 8 stems = 16 channels).
- Assignment is **stable for the session**: a proxy keeps its stem index
  through `.stop`/`.play` cycles and source swaps, and releases it only on
  `.clear`. The receiver can learn "stem 3 is the kick" once.
- The current assignment table is published over OSC (see `/syn/stems`) on
  every change, so the receiver can label channels.
- **Stems MUST follow audibility.** This is the fault line in the obvious
  design. `proxy.stop` only stops the Monitor; the proxy keeps running, so a
  naive tap on `proxy.ar(2)` would keep feeding the VJ a kick the audience can
  no longer hear, including after `hush`. Two acceptable mechanisms; the
  implementer picks one and documents why:
  - Mirror the Monitor. JITLib's `Monitor` supports `playN` with an `ins`
    mapping, so one Monitor can send proxy channels to both the main pair and
    the stem pair, fading together. This is exact but means intercepting or
    wrapping `.play`/`.stop`/`.playN`.
  - Gain-tracking tap. A hidden proxy taps `proxy.ar(2)` and multiplies by a
    gain that follows `monitor.isPlaying` and `monitor.vol`, lagged by the
    proxy's `fadeTime`. Approximate (fade curves may differ) but non-invasive.
- Stems are post-fx (the proxy's output after its `.fx` chain), pre-master.
  A stereo main mix is also sent as stem 0, always, so the receiver has a
  guaranteed-correct full signal even if stem assignment is confusing.
- Stem outputs land on hardware output channels starting at a configurable
  offset (default 2, leaving 0..1 for the house PA). The expected topology is
  a macOS aggregate device of the performance interface plus BlackHole 16ch,
  with SonoBus reading BlackHole. Broadcast MUST warn once and cap the stem
  count if the server reports fewer output channels than requested.
- SonoBus supports multiple input groups but its per-connection channel
  ceiling is undocumented in what we found. The receiving end must be tested
  at the full stem count before a show; JackTrip is the fallback transport if
  SonoBus cannot carry it. Broadcast itself is transport-agnostic: its job
  ends at the hardware output channels.

## OSC namespace

Flat, stable, one float where the receiver will learn-map. Addresses MUST NOT
change based on canvas state (no indices that shift when a proxy is removed).

```
/syn/hit/<folder>        amp index         .hits and .chop
/syn/note/<synthdef>     amp degree dur    .deg (degree) and .chord (midinote)
/syn/kr/<proxyKey>       value             native range
/syn/krn/<proxyKey>      value01           Spec-normalized, optional stream
/syn/tempo               bpm
/syn/beat                phase01           beat-in-bar, at the continuous rate
/syn/stems               key0 key1 ...     stem index -> proxy key, on change
/syn/gone/<proxyKey>                       proxy cleared or source nil
/syn/alive               seconds           heartbeat, 1 Hz
```

Keys are the ProxySpace keys as the performer typed them (`k`, `a`, `m`).
Names in addresses are the Symbol as typed (`k`, `bplay`, `ping`). The
namespace root (`/syn`) is a setup-time setting so two Synesthesia machines
could coexist.

## Configuration surface

All setup-time, none performance-time.

- `Broadcast.start(addr, events: true, kr: true, stems: true, rate: 30, stemOut: 2, maxStems: 8, root: "/syn")`
- `Broadcast.stop`
- `Broadcast.status` posts: receiver address, which streams are live, kr
  proxies currently tapped, stem table, server output channel count.
- Per-stream enable flags exist so the collaborator can say "events and three
  envelopes, no stems" after a rehearsal and that becomes one line in Setup.

## Performance budget

- Event hook adds no measurable latency to pattern playback.
- Continuous stream at 30 Hz with 20 kr proxies costs under 1% of sclang CPU.
- Stem taps add at most one lightweight synth per stem on the server.
- No allocation inside the per-event callback beyond the OSC message itself.

## Verification

- Unit tests (add to `SynesthesiaTests.sc`): address formatting, payload
  shapes for each brush type, stem index stability under stop/play/swap/clear,
  kr-set diffing.
- A receiver stub that runs on the second machine (or in a second sclang)
  logging every message with timestamps, used for the ±10 ms timing check and
  for the "zero typed words" walkthrough below.
- The walkthrough: evaluate the guide's "A first session" line by line with
  Broadcast running and the stub listening. Every line that makes or changes
  sound must appear on the wire with no extra typing. That is the acceptance
  test.
- Negative tests: start Broadcast with an unreachable address, then run the
  walkthrough; audio and post window must be indistinguishable from a run with
  Broadcast off, except for one warning.

## Documentation

- `HelpSource/Classes/Broadcast.schelp` for the class.
- A section in `Guides/Synesthesia` ("Playing with a VJ") describing the
  aggregate-device setup and what the receiver sees.
- `Reference/Synesthesia-Reference` gains a "Broadcast" section listing the
  setup-time surface and the namespace. The reference page's own rule applies:
  if it disagrees with the code, the page is the bug.
- Code comments at the existing standard: thorough enough for a reader new to
  SuperCollider to follow each block.

## Out of scope

- Any visual generation on this machine. Visuals belong to the receiver. The
  "painting register" planned for Phase 2 stays a sound metaphor.
- Receiving anything from the VJ machine. One direction only.
- Network MIDI. macOS already provides it; if the collaborator wants MIDI it is
  an OSC-to-MIDI bridge on his side or a later feature.
- Transport (SonoBus/JackTrip configuration). Broadcast ends at the hardware
  outputs.

## Open questions to settle with the collaborator before the namespace freezes

- Does he want events, envelopes, stems, or some subset? Default on for all;
  trim after a rehearsal.
- Native-range or normalized kr values? Probably normalized for anything he
  will learn-map to a slider.
- Does his receiving software honor OSC bundle timetags? Determines the latency
  compensation mechanism.
- Confirm SonoBus's channel ceiling at our stem count on his machine.
