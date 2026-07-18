# KAMI NLE

EDN-native browser non-linear video editor for `kotoba-lang`. It owns frame/timecode transport, video and audio lanes, clip selection, trim/split/move semantics, project bin, media effects, and master export. It does not own music composition or 3D character authoring.

The production path is browser-native: multiple imported videos are bound to timeline clips through `:clip/source-id`. Imported/package media is deduplicated into a versioned IndexedDB cache by SHA-256; recovery rehashes complete blobs before rebuilding object URLs, deletes corrupt hits, and exposes an explicit clear-cache control. Accessible in/out handles provide direct pointer and keyboard trimming; drag movement previews in runtime state, pointer up commits one project generation, and pointer cancel discards it. Projects also travel with media in verified `.kami.zip` packages. Every project edit enters persistent bounded undo/redo; decoded media, cache state, drag previews, meters and transport are excluded.

Project-authoritative captions support BCP-47-style language tags, active burn-in/export language selection, multiline text, top/bottom position, left/center/right alignment and bounded font scale inside a 5% title-safe area. The selected language is rendered onto the decoded preview canvas, so production capture contains the same localized burn-in, and is exported as its own deterministic multiline WebVTT sidecar. Translation memory, rich typography, TTML and IMSC delivery remain explicit commercial-product gaps.

## Run

```sh
npm install
npx shadow-cljs watch app
```

Open <http://localhost:9630>. Public app: <https://kotoba-lang.github.io/kami-app-nle/>

## Verify

```sh
npm run check
npm run release
```

Maturity: **逍遥** — project-authoritative caption editing with canvas burn-in and WebVTT delivery, independently imported and decoded audio lanes, project-authoritative audio automation, delivery loudness and display color, per-clip/master EQ, proxies, verified media cache, recursive relinking, trim handles, verified packages, persistent undo/redo, professional trim modes, transitions, metering, effects and timeline-ordered production export are implemented. Export profiles declare container, MIME candidates, bitrates and extension. The UI enables only profiles supported by the current MediaRecorder, never silently substitutes codecs, and writes the actual MIME and matching `.webm` or `.mp4` filename. Current Chrome verification produced VP8/VP9 + Opus WebM and H.264 + AAC MP4. Persistent proxy jobs, directory grants, streaming packages above 512 MiB, styled/localized TTML/IMSC caption delivery, OCIO/ICC/HDR and container color tagging, broadcast certification, MOV/MXF/IMF delivery and explicit hardware-encoder control remain follow-up scope.
