# KAMI NLE

EDN-native browser non-linear video editor for `kotoba-lang`. It owns frame/timecode transport, video and audio lanes, clip selection, trim/split/move semantics, project bin, media effects, and master export. It does not own music composition or 3D character authoring.

The production path is browser-native: multiple imported videos are bound to timeline clips through `:clip/source-id`. Preview resolves the active clip at the playhead, while export consumes the pure `render-segments` plan in timeline order, honoring each clip's source-in and duration. Frames pass through the filtered canvas and mixed source audio is encoded to downloadable WebM through `MediaRecorder`.

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

Maturity: **逍遥** — multi-asset timeline binding, real decode, canvas effects, and timeline-ordered WebM export are implemented. Gap/overlap policy, rendered transitions, trim handles, proxies, color management, meters, and codec/container selection remain follow-up scope.
