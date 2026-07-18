# KAMI NLE

EDN-native browser non-linear video editor for `kotoba-lang`. It owns frame/timecode transport, video and audio lanes, clip selection, trim/split/move semantics, project bin, media effects, and master export. It does not own music composition or 3D character authoring.

The production path is browser-native: multiple imported videos are bound to timeline clips through `:clip/source-id`. Accessible in/out handles provide pointer trimming directly on the timeline; drag movement previews in runtime state, pointer up commits exactly one project generation, and pointer cancel discards the preview. In-trim advances source and timeline start together, while out-trim changes the source end. Project and source media can travel together in a SHA-256-verified `.kami.zip` package that rebuilds object URLs and decode sources. Every project-value edit enters a persistent 50-generation undo/redo history; decoded media, drag previews, meters and transport state are excluded.

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

Maturity: **逍遥** — direct pointer trim handles, portable SHA-256-verified project/media packages, persistent bounded undo/redo, batch relinking, missing-media reporting, versioned recovery, validated EDN persistence, multi-asset binding, source/ripple/roll/slip trim modes, gap/overlap classification, fade and cross-dissolve rendering, per-clip/master audio mixing, master dBFS metering, canvas effects, delivery profiles, and timeline-ordered WebM export are implemented. Streaming packages above 512 MiB, search paths, proxies, color management, multitrack audio lanes/EQ, loudness measurement, additional containers and hardware-encoder control remain follow-up scope.
