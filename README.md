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

Maturity: **逍遥** — verified persistent media cache, direct pointer/keyboard trim handles, portable verified packages, persistent bounded undo/redo, batch relinking, missing-media reporting, versioned recovery, validated EDN persistence, multi-asset binding, source/ripple/roll/slip trim modes, gap/overlap classification, transitions, per-clip/master audio mixing, dBFS metering, canvas effects, delivery profiles and timeline-ordered WebM export are implemented. Proxy generation, streaming packages above 512 MiB, search paths, color management, multitrack audio lanes/EQ, loudness measurement, additional containers and hardware-encoder control remain follow-up scope.
