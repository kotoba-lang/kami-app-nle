# KAMI NLE

EDN-native browser non-linear video editor for `kotoba-lang`. It owns frame/timecode transport, video and audio lanes, clip selection, trim/split/move semantics, project bin, media effects, and master export. It does not own music composition or 3D character authoring.

The production path is browser-native: multiple imported videos are bound to timeline clips through `:clip/source-id`. Validated `kami.eizo-project/v1` EDN and recovery envelopes retain an asset manifest of stable IDs and source filenames alongside timeline edits, audio levels, transitions and delivery profile. After reload, a batch of videos can be selected in any order and filenames map each object URL back to the correct source ID automatically. Invalid or future-version recovery data is discarded. Preview resolves the active clip at the playhead, while export consumes the pure `render-segments` plan in timeline order, honoring edited source-in/out values, per-clip audio gain, fade-to-black, and dual-decoder cross-dissolve transitions. During a dissolve, two stable video elements feed alpha-composited canvas frames and complementary Web Audio gains.

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

Maturity: **逍遥** — filename-based batch media relinking, versioned autosave/recovery, validated EDN project persistence, multi-asset binding, source trim, ripple trim, roll and slip modes, gap/overlap classification, fade and cross-dissolve rendering, per-clip/master audio mixing, master dBFS metering, canvas effects, codec/bitrate delivery profiles, and timeline-ordered WebM export are implemented. Content-hash relinking, recovery history, pointer drag handles, proxies, color management, multitrack audio lanes/EQ, loudness measurement, additional containers and hardware-encoder control remain follow-up scope.
