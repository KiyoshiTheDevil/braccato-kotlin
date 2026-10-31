// Runs the published `@braccato/parsers` over every lyric file in a folder and writes what it reads to
// a matching `.json` file in a second folder. `UpstreamComparisonTest` (braccato-parsers, jvmTest)
// reads the same files with the Kotlin port and compares the two.
//
//   node dump.mjs <lyrics folder> <output folder>
//
// The JSON is canonical so both sides can produce it byte for byte: object keys sorted, `undefined`
// left out, NaN written as null (which is what JSON.stringify does with it anyway).
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";
import { detectFormat, detectParser, parseLRC, parseTTMLContent } from "@braccato/parsers";

// Matches SONG_DURATION_MS in UpstreamComparisonTest.kt.
const SONG_DURATION_MS = 240000;

function canonical(value) {
	if (Array.isArray(value)) return value.map(canonical);
	if (value && typeof value === "object") {
		const out = {};
		for (const key of Object.keys(value).sort()) {
			if (value[key] !== undefined) out[key] = canonical(value[key]);
		}
		return out;
	}
	return value;
}

function read(text) {
	const format = detectFormat(text);
	const parser = detectParser(text);
	const result = {
		format,
		lyrics: parser.parse(text, SONG_DURATION_MS),
		metadata: parser.metadata(text),
	};
	if (format === "ttml") {
		const content = parseTTMLContent(text, { songDurationMs: SONG_DURATION_MS });
		result.ttml = { isWordSynced: content.isWordSynced, language: content.language, lyrics: content.lyrics };
	}
	if (format === "lrc") result.rawLrc = parseLRC(text, SONG_DURATION_MS);
	return result;
}

const [inputDir, outputDir] = process.argv.slice(2);
if (!inputDir || !outputDir) {
	console.error("usage: node dump.mjs <lyrics folder> <output folder>");
	process.exit(1);
}
mkdirSync(outputDir, { recursive: true });

let count = 0;
for (const name of readdirSync(inputDir).sort()) {
	if (!/\.(ttml|lrc|srt|qrc|txt)$/i.test(name)) continue;
	const text = readFileSync(join(inputDir, name), "utf8");
	writeFileSync(join(outputDir, `${basename(name)}.json`), `${JSON.stringify(canonical(read(text)), null, 1)}\n`);
	count++;
}
console.log(`wrote ${count} file(s) to ${outputDir}`);
