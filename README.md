# KAMI NLE

EDN-native browser non-linear video editor for `kotoba-lang`. It owns frame/timecode transport, video and audio lanes, clip selection, trim/split/move semantics, project bin, media effects, and master export. It does not own music composition or 3D character authoring.

The production path is browser-native: multiple imported videos are bound to timeline clips through `:clip/source-id`. Preview resolves the active clip at the playhead, while export consumes the pure `render-segments` plan in timeline order, honoring edited source-in/out values, per-clip audio gain, fade-to-black, and dual-decoder cross-dissolve transitions. During a dissolve, two stable video elements feed alpha-composited canvas frames and complementary Web Audio gains. Clip gains feed a master gain and dBFS analyser before the mixed source audio and picture are encoded to downloadable WebM through `MediaRecorder`. Pure timeline relations expose gaps and overlaps explicitly.

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

Maturity: **逍遥** — multi-asset binding, source trim, ripple trim, roll and slip modes, gap/overlap classification, fade and cross-dissolve rendering, per-clip/master audio mixing, master dBFS metering, canvas effects, and timeline-ordered WebM export are implemented. Pointer drag handles, proxies, color management, multitrack audio lanes/EQ, loudness measurement, and codec/container presets remain follow-up scope.
