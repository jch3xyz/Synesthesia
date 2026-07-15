# Synesthesia

A live-coding vocabulary for SuperCollider where **the patch is the canvas and
patterns are brushes**. Layered over JITLib/ProxySpace; every terse form evaluates
to a plain SuperCollider object, so raw SC composes anywhere.

Status: **Phase 1 — the MVP audio language is real** (bare control words, step/degree
notation, drones, effects, wiring, bridges, pattern transforms). The painting
register (colors/tools) arrives in Phase 2.

**Documentation:** open `Guides/Synesthesia` in the SuperCollider help browser for a
complete novice-friendly guide with runnable examples (it includes a minimal
environment using SC's bundled sounds, so nothing else is required). Every word is
also documented on its home class (NodeProxy, Symbol, String, SimpleNumber, Pattern)
via extension help files. Design rationale and the build roadmap
live in the companion repo
([Live-Coding-With-SuperCollider](https://github.com/jch3xyz/Live-Coding-With-SuperCollider)):
`Design Notes.md`, `Companion Quarks.md`, `Roadmap.md`, `Vocabulary Sketch.scd`.

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
