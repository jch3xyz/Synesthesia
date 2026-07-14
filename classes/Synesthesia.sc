// Synesthesia — a live-coding vocabulary for SuperCollider.
// The patch is the canvas; patterns are brushes.
//
// Design docs live in the Live-Coding-With-SuperCollider repo:
//   Design Notes.md   — language thesis, registers, vocabulary buckets
//   Roadmap.md        — phased build pipeline (this is Phase 0: skeleton)
//
// Design rules every addition must keep:
//   1. Every terse form evaluates to a plain SC object (Pattern/Function/NodeProxy).
//   2. All parameters are named controls with registered Specs — never bare literals.
//   3. Nothing auxiliary (GUI, visuals, camera) gets typed performance commands.

Synesthesia {
	classvar <version = "0.1.0";

	// path to the quark's root folder
	*dir {
		^this.filenameSymbol.asString.dirname.dirname
	}
}
