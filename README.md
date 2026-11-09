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
- **The painting register (colors/tools) was dropped** in August 2026: the flexible
  language underneath it proved to be the better language.
- **Next: Broadcast** — events, control values and per-proxy stems streamed to a VJ
  machine, configured once in Setup. Specified, not started: see
  [Broadcast Manifest.md](Broadcast%20Manifest.md).

**Documentation:** open `Guides/Synesthesia` in the SuperCollider help browser for a
complete novice-friendly guide with runnable examples (it includes a minimal
environment using SC's bundled sounds, so nothing else is required), and
`Guides/Breaks` for the breakbeat machine. `Reference/Synesthesia-Reference` covers
the whole vocabulary on one page. Every word is also documented on its home class
(NodeProxy, Symbol, String, SimpleNumber, Pattern, Break). Design rationale and the
build roadmap live in the companion repo
([Live-Coding-With-SuperCollider](https://github.com/jch3xyz/Live-Coding-With-SuperCollider)):
`Roadmap.md`, `Design Notes.md`, `Breaks Build Plan.md`, `Companion Quarks.md`,
`Vocabulary Sketch.scd`.

**Checks:** `TestSynesthesiaParsers.run` and `TestBreak.run` in the IDE (no server
needed), and `check-help.scd` after any help-file edit (runs headless too).

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
