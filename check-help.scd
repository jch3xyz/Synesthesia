// check-help.scd — does every help file in this quark still parse AND render?
//
// Run this after ANY edit to a .schelp file. Parsing alone is not enough: the help
// browser indexes and renders, and this does both, which is the path a reader hits.
//
// It exists because a guide once shipped with "syntax error, unexpected ::" in it —
// the docs were validated, then edited again, and never re-validated. The failure is
// invisible in the source: SCDoc's inline tags do NOT nest, so
//     strong::Moving code::grind:: on its own::        <- broken
// parses as strong::Moving code:: followed by a stray ::. Write it as
//     strong::Moving grind on its own::                <- fine
// Keep one inline tag per span; put code:: and emphasis:: beside each other, never
// inside each other.
//
// Usage: evaluate the whole file in the IDE. Takes a minute (it indexes all help
// documents). Also runs headless, where it exits on its own:
//   /Applications/SuperCollider.app/Contents/MacOS/sclang check-help.scd

(
var quark = thisProcess.nowExecutingPath.dirname;
var base = quark +/+ "HelpSource" +/+ "";
var files = (base ++ "**/*.schelp").pathMatch;
var pass = 0, fail = 0;

"".postln;
("=== parse: " ++ files.size ++ " help file(s) in " ++ base ++ " ===").postln;
files.do { |path|
	var rel = path.copyToEnd(base.size);
	var partial = rel.contains(".ext.schelp");
	var r = try {
		if(partial) { SCDoc.parseFilePartial(path) } { SCDoc.parseFileFull(path) }
	} { |e| ("THREW: " ++ e.errorString) };
	if(r.isNil or: { r.isKindOf(String) }) {
		fail = fail + 1;
		("  FAIL " ++ rel ++ if(r.isKindOf(String), { "  " ++ r }, "")).postln;
	} {
		pass = pass + 1;
		("  ok   " ++ rel).postln;
	};
};

"".postln;
"=== render (indexing all documents, slow) ===".postln;
SCDoc.indexAllDocuments(false);
files.do { |path|
	var rel = path.copyToEnd(base.size);
	var docPath, doc, r;
	if(rel.contains(".ext.schelp").not) {
		docPath = rel.copyRange(0, rel.size - 8);   // drop ".schelp"
		doc = SCDoc.documents[docPath];
		if(doc.isNil) {
			("  --   " ++ docPath ++ "  (not in the index — nothing to render)").postln;
		} {
			r = try { SCDoc.parseAndRender(doc); \ok } { |e| e.errorString };
			if(r == \ok) {
				pass = pass + 1;
				("  ok   " ++ docPath).postln;
			} {
				fail = fail + 1;
				("  FAIL " ++ docPath ++ "  " ++ r).postln;
			};
		};
	};
};

"".postln;
("=== HELP CHECK: " ++ pass ++ " passed, " ++ fail ++ " failed ===").postln;
if(fail == 0) { "Every help file parses and renders.".postln };
// exit when run headless; stay open when evaluated in the IDE
if(Platform.ideName != "scide") { fail.min(1).exit };
)
