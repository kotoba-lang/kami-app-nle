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

Maturity: **逍遥** — independently imported and decoded audio lanes, project-authoritative audio fade/crossfade and gain automation, master gain and delivery loudness policy, per-clip/master three-band EQ, browser-generated proxies, verified persistent media cache, recursive relinking, direct trim handles, portable verified packages, persistent undo/redo, source/ripple/roll/slip trim modes, transitions, dBFS metering, canvas effects, delivery profiles and timeline-ordered WebM export are implemented. Production export performs a first pass through the actual embedded-video and audio-lane graph, measures gated K-weighted stereo loudness plus per-channel sample peak, constrains normalization by target LUFS and the configured peak ceiling, then records the same timeline graph at the computed gain. Overlapping audio clips produce sample-timed crossfades; portable packages and verified cache restore decode audio assets back to AudioBuffers. Persistent proxy jobs, persistent directory grants, streaming packages above 512 MiB, color management, certified true-peak/broadcast conformance, additional containers and hardware-encoder control remain follow-up scope.
