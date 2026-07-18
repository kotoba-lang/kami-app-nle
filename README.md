# KAMI NLE

EDN-native browser non-linear video editor for `kotoba-lang`. It owns frame/timecode transport, video and audio lanes, clip selection, trim/split/move semantics, project bin, media effects, and master export. It does not own music composition or 3D character authoring.

The production path is browser-native: multiple imported videos are bound to timeline clips through `:clip/source-id`. Preview resolves the active clip at the playhead, while export consumes the pure `render-segments` plan in timeline order, honoring edited source-in/out values and rendered fade-to-black transitions. Frames pass through the filtered canvas and mixed source audio is encoded to downloadable WebM through `MediaRecorder`; pure timeline relations expose gaps and overlaps explicitly.

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

Maturity: **逍遥** — multi-asset binding, source trim editing, gap/overlap classification, fade rendering, canvas effects, and timeline-ordered WebM export are implemented. Pointer trim handles, ripple/roll/slip modes, overlap compositing, proxies, color management, meters, and codec/container selection remain follow-up scope.
