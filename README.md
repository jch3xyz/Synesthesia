# Synesthesia

A live-coding vocabulary for SuperCollider where **the patch is the canvas and
patterns are brushes**. Layered over JITLib/ProxySpace; every terse form evaluates
to a plain SuperCollider object, so raw SC composes anywhere.

Status (November 2026):

- **Phase 1 — the audio language is real**: bare control words, step/degree
  notation, drones, effects, wiring, bridges, pattern transforms.
- **Phase 1.6 — the breakbeat machine is real**: the `Break` registry (tempo
  inference, sidecar overrides, whole-library indexing with load-on-first-use) and
  the `brk` / `grind` / `seq` / `chop` words over the `\break` engine.
- **Phase 2 — the painting register is real** (November 2026, revived after being
  dropped in August): `Paint` holds a palette of colours (hue/sat/val plus a
  spread, named after the screen colour wheel) and the tools `brush` / `mark` /
  `dab` / `drip` / `spray` / `wash`, each one voice applied in time —
  `\orange.brush("Cm7 G7")` — with the moves `.darker` / `.lighter` / `.paler` /
  `.saturate` / `.warmer` / `.cooler` / `.paint` on patterns and proxies, and
  `mix` between tubes. Every stroke goes out under `/syn/paint/<tool>/` with its
  colour. The palette is a first set: calibration by ear is John's.
- **Phase 4 — Broadcast is real** (November 2026): events, control values and
  per-proxy audio stems streamed to a VJ's machine, configured once in Setup and
  then watching the ProxySpace on its own (`Broadcast.start/stop/status`, nothing
  typed mid-set). Spec: [Broadcast Manifest.md](Broadcast%20Manifest.md), with an
  "as built" note at the bottom.

**Documentation:** open `Guides/Synesthesia` in the SuperCollider help browser for a
complete novice-friendly guide with runnable examples (it includes a minimal
environment using SC's bundled sounds, so nothing else is required), and
`Guides/Breaks` for the breakbeat machine; the guide's "Playing with a VJ" section
and `Classes/Broadcast` cover the Broadcast layer. `Reference/Synesthesia-Reference`
covers the whole vocabulary on one page. Every word is also documented on its home
class (NodeProxy, Symbol, String, SimpleNumber, Pattern, Break, Broadcast, Paint). Design rationale and the
build roadmap live in the companion repo
([Live-Coding-With-SuperCollider](https://github.com/jch3xyz/Live-Coding-With-SuperCollider)):
`Roadmap.md`, `Design Notes.md`, `Breaks Build Plan.md`, `Companion Quarks.md`,
`Vocabulary Sketch.scd`.

**Checks:** `TestSynesthesiaParsers.run`, `TestPaint.run`, `TestBreak.run` and
`TestBroadcast.run` in the IDE (no server needed), `TestSynesthesiaGuard.run` (boots a small private server
of its own for one test), and `check-help.scd` after any help-file edit (runs
headless too). The live-server checks (`SetupCheck`, `BreakIntegration`,
`BroadcastCheck` with its receiver stub) live in the companion repo's `Setup/`.

## Install

Clone, then add to SuperCollider either way:

```supercollider
// via Quarks, from a local clone:
Quarks.install("/path/to/Synesthesia");
// or add the folder to sclang_conf.yaml includePaths and recompile.
```

## Dependencies

Installed automatically via the Quarks system (listed in `Synesthesia.quark`):
Bjorklund, BatLib, ddwSnippets, JITLibExtensions.

Manual installs (not in the official quarks directory):

```supercollider
Quarks.install("https://github.com/cappelnord/BenoitLib");
// ChordSymbol2 — John's fork of triss/ChordSymbol (asFreqs + octave-digit syntax)
```

## License

GPL-3.0-or-later (matching the SuperCollider ecosystem).
