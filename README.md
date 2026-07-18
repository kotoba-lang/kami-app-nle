# KAMI NLE

Caption cards expose project-authoritative interval editing for linear, discrete, and spline animation. Spline intervals provide four bounded Bézier controls with linked `x1 <= x2` UI constraints; every edit is revalidated before persistence and immediately drives preview and production evaluation.

TTML animation ingest accepts bounded multi-keyframe `values`, strictly increasing `keyTimes`, and one validated `keySplines` curve per interval. Up to 17 authored values become 16 project-authoritative intervals evaluated by the same preview/production path and emitted as lossless equivalent IMSC animation segments.

EDN-native browser non-linear video editor for `kotoba-lang`. It owns frame/timecode transport, video and audio lanes, clip selection, trim/split/move semantics, project bin, media effects, and master export. It does not own music composition or 3D character authoring.

TTML animation supports project-authoritative `linear`, `discrete`, and cubic Bézier `spline` timing. Import validates bounded `keySplines`, frame preview and production capture solve the same time-to-curve mapping, and IMSC export preserves `calcMode` plus canonical spline control points.

The production path is browser-native: multiple imported videos are bound to timeline clips through `:clip/source-id`. Imported/package media is deduplicated into a versioned IndexedDB cache by SHA-256; recovery rehashes complete blobs before rebuilding object URLs, deletes corrupt hits, and exposes an explicit clear-cache control. Accessible in/out handles provide direct pointer and keyboard trimming; drag movement previews in runtime state, pointer up commits one project generation, and pointer cancel discards it. Projects also travel with media in verified `.kami.zip` packages. Every project edit enters persistent bounded undo/redo; decoded media, cache state, drag previews, meters and transport are excluded.

Project-authoritative captions support assigned reviewers, threaded review replies, audited resolve/reopen actions, `@user` mentions, recipient-scoped unread notifications, and an append-only `draft → review → approved` status history with actor and timestamp. Mentioned users, assigned reviewers and thread authors receive deterministic notification records; only the recipient can mark one read. Caption removal and translation replacement cascade their notifications. Resolved threads reject new replies; reviewer assignment can be cleared; when assigned, only that reviewer may approve a cue. Translation clones and imported WebVTT/IMSC cues start as draft; preview burn-in, WebVTT, IMSC 1.2 Text Profile and production capture share an approved-only gate. Safe IMSC import preserves bounded recursive styling and percentage region geometry. `tts:writingMode` (`lrtb`, `rltb`, `tbrl`, `tblr`), one-to-four-value percentage `tts:padding`, `tts:displayAlign`, bounded `tts:ruby` runs, and bounded linear, discrete, and spline `<animate>` opacity/font-size intervals survive in project authority, timed `<set>` segmentation, clipped canvas preview and per-caption IMSC delivery. Animation uses caption-relative timing, clips across set-derived segments, freezes at its terminal value, and drives the same frame evaluation used by preview and production capture. Horizontal RTL uses canvas direction; vertical modes stack glyphs in the authored column direction; horizontal ruby annotations are measured over their authored base text. Horizontal preview and production capture share bounded language-aware wrapping: Japanese and Chinese apply line-start/line-end kinsoku sets, while other languages preserve word boundaries and split only overlong tokens. Each caption card exposes an accessible 24-unit editorial line-break preview, while Canvas uses the same contract with its measured region capacity. External delivery integrations, translation memory, dictionary-grade morphological line breaking and advanced multi-keyframe animation remain explicit commercial-product gaps.

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

Maturity: **逍遥** — project-authoritative caption editing with canvas burn-in, bounded linear/discrete/cubic-spline TTML animation, bounded language-aware/kinsoku line breaking, WebVTT and styled/localized IMSC 1.2 delivery including timed `<set>`, percentage geometry, writing modes, padding, display alignment and ruby annotations, independently imported and decoded audio lanes, project-authoritative audio automation, delivery loudness and display color, per-clip/master EQ, proxies, verified media cache, recursive relinking, trim handles, verified packages, persistent undo/redo, professional trim modes, transitions, metering, effects and timeline-ordered production export are implemented. Export profiles declare container, MIME candidates, bitrates and extension. The UI enables only profiles supported by the current MediaRecorder, never silently substitutes codecs, and writes the actual MIME and matching `.webm` or `.mp4` filename. Persistent proxy jobs, directory grants, streaming packages above 512 MiB, advanced multi-keyframe TTML animation, dictionary-grade morphological line breaking, OCIO/ICC/HDR and container color tagging, broadcast certification, MOV/MXF/IMF delivery and explicit hardware-encoder control remain follow-up scope.
