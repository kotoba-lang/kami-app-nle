# KAMI NLE

EDN-native browser non-linear video editor for `kotoba-lang`. It owns frame/timecode transport, video and audio lanes, clip selection, trim/split/move semantics, project bin, media effects, and master export. It does not own music composition or 3D character authoring.

The production path is browser-native: multiple imported videos are bound to timeline clips through `:clip/source-id`. Project and source media can travel together in a `.kami.zip` package containing EDN project/manifest entries and SHA-256-verified media; opening it rebuilds object URLs and decode sources without manual relinking. Both compressed input and expanded content are bounded to 512 MiB. Every project-value edit enters a persistent 50-generation undo/redo history available through controls and Cmd/Ctrl+Z; decoded media, meters and transport state are excluded. The current project and validated history share a versioned autosave envelope, including the redo branch and v1 migration. Hashing and registration run sequentially so new source IDs cannot collide.

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

Maturity: **逍遥** — portable SHA-256-verified project/media packages, persistent bounded project undo/redo, batch media relinking, missing-media reporting, versioned autosave/recovery, validated EDN persistence, multi-asset binding, source trim, ripple trim, roll and slip modes, gap/overlap classification, fade and cross-dissolve rendering, per-clip/master audio mixing, master dBFS metering, canvas effects, codec/bitrate delivery profiles, and timeline-ordered WebM export are implemented. Streaming packages above 512 MiB, search paths, pointer trim gestures, proxies, color management, multitrack audio lanes/EQ, loudness measurement, additional containers and hardware-encoder control remain follow-up scope.
