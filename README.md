# KAMI NLE

EDN-native browser non-linear video editor for `kotoba-lang`. It owns frame/timecode transport, video and audio lanes, clip selection, trim/split/move semantics, project bin, media effects, and master export. It does not own music composition or 3D character authoring.

The production path is browser-native: an imported local video is decoded by `HTMLVideoElement`, rendered frame-by-frame through a filtered canvas, and encoded with its audio track to a downloadable WebM through `MediaRecorder`.

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

Maturity: **逍遥** — real media decode, canvas effects, playback, and WebM export are implemented. Multi-clip source relinking, transitions in the rendered master, and codec/container selection remain follow-up scope.
