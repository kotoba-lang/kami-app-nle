# KAMI NLE

EDN-native browser non-linear video editor for `kotoba-lang`. It owns frame/timecode transport, video and audio lanes, clip selection, trim/split/move semantics, project bin, media effects, and master export. It does not own music composition or 3D character authoring.

The production path is browser-native: multiple imported videos are bound to timeline clips through `:clip/source-id`. Imported/package media is deduplicated into a versioned IndexedDB cache by SHA-256; recovery rehashes complete blobs before rebuilding object URLs, deletes corrupt hits, and exposes an explicit clear-cache control. Accessible in/out handles provide direct pointer and keyboard trimming; drag movement previews in runtime state, pointer up commits one project generation, and pointer cancel discards it. Projects also travel with media in verified `.kami.zip` packages. Every project edit enters persistent bounded undo/redo; decoded media, cache state, drag previews, meters and transport are excluded.

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

Maturity: **逍遥** — independently imported and decoded audio lanes, project-authoritative audio automation, delivery loudness and display color pipeline, per-clip/master EQ, browser-generated proxies, verified persistent media cache, recursive relinking, direct trim handles, portable verified packages, persistent undo/redo, professional trim modes, transitions, dBFS metering, canvas effects, delivery profiles and timeline-ordered WebM export are implemented. Media metadata remains input color authority; the project selects sRGB or Display-P3 canvas output and bounded exposure, contrast and saturation. Preview and MediaRecorder production export capture the same graded canvas. Production audio export performs actual-timeline K-weighted preflight and peak-constrained normalization. Persistent proxy jobs, persistent directory grants, streaming packages above 512 MiB, OCIO/ICC transforms, HDR transfer functions and container color tagging, certified broadcast conformance, additional containers and hardware-encoder control remain follow-up scope.
