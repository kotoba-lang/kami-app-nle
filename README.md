# KAMI NLE

EDN-native browser non-linear video editor for `kotoba-lang`. It owns frame/timecode transport, video and audio lanes, clip selection, trim/split/move semantics, project bin, media effects, and master export. It does not own music composition or 3D character authoring.

The production path is browser-native: multiple imported videos are bound to timeline clips through `:clip/source-id`. Every project-value edit enters a bounded 50-generation undo/redo history available through controls and Cmd/Ctrl+Z; decoded media, meters and transport state are excluded. Undo and redo feed the same autosave authority, so reload recovery matches the visible timeline. Validated EDN and recovery envelopes retain stable IDs, source filenames and SHA-256 content hashes alongside timeline edits, audio levels, transitions and delivery profile. After reload, missing IDs are reported explicitly and renamed media relinks by content. Hashing and registration run sequentially so new source IDs cannot collide.

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

Maturity: **逍遥** — bounded project undo/redo, SHA-256/filename batch media relinking, missing-media reporting, versioned autosave/recovery, validated EDN project persistence, multi-asset binding, source trim, ripple trim, roll and slip modes, gap/overlap classification, fade and cross-dissolve rendering, per-clip/master audio mixing, master dBFS metering, canvas effects, codec/bitrate delivery profiles, and timeline-ordered WebM export are implemented. Persistent history, search paths, packaged media, pointer trim gestures, proxies, color management, multitrack audio lanes/EQ, loudness measurement, additional containers and hardware-encoder control remain follow-up scope.
