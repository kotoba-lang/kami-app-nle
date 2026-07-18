# KAMI NLE

EDN-native browser non-linear video editor for `kotoba-lang`. It owns frame/timecode transport, video and audio lanes, clip selection, trim/split/move semantics, project bin, media effects, and master export. It does not own music composition or 3D character authoring.

The production path is browser-native: multiple imported videos are bound to timeline clips through `:clip/source-id`. Imported/package media is deduplicated into a versioned IndexedDB cache by SHA-256; recovery rehashes complete blobs before rebuilding object URLs, deletes corrupt hits, and exposes an explicit clear-cache control. Accessible in/out handles provide direct pointer and keyboard trimming; drag movement previews in runtime state, pointer up commits one project generation, and pointer cancel discards it. Projects also travel with media in verified `.kami.zip` packages. Every project edit enters persistent bounded undo/redo; decoded media, cache state, drag previews, meters and transport are excluded.

Project-authoritative captions support assigned reviewers, threaded review replies, audited resolve/reopen actions, `@user` mentions, recipient-scoped unread notifications, and an append-only `draft → review → approved` status history with actor and timestamp. Mentioned users, assigned reviewers and thread authors receive deterministic notification records; only the recipient can mark one read. Caption removal and translation replacement cascade their notifications. Resolved threads reject new replies; reviewer assignment can be cleared; when assigned, only that reviewer may approve a cue. Translation clones and imported WebVTT/IMSC cues start as draft; preview burn-in, WebVTT, IMSC 1.2 Text Profile and production capture share an approved-only gate. Safe IMSC import rejects DOCTYPE and malformed/non-profile XML, requires TTML namespace plus IMSC 1.2 signaling, accepts clock milliseconds, offset seconds and frame-clock timing, preserves language and `<br>` text, and resolves styling through bounded recursive style references and TTML ancestor inheritance before region fallback. Cyclic references terminate safely, and inherited region IDs participate in top/bottom mapping from percentage `tts:origin`. One-language replacement cascades stale review notifications. External delivery integrations, translation memory and TTML animation remain explicit commercial-product gaps.

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

Maturity: **逍遥** — project-authoritative caption editing with canvas burn-in, WebVTT and styled/localized IMSC 1.2 delivery, independently imported and decoded audio lanes, project-authoritative audio automation, delivery loudness and display color, per-clip/master EQ, proxies, verified media cache, recursive relinking, trim handles, verified packages, persistent undo/redo, professional trim modes, transitions, metering, effects and timeline-ordered production export are implemented. Export profiles declare container, MIME candidates, bitrates and extension. The UI enables only profiles supported by the current MediaRecorder, never silently substitutes codecs, and writes the actual MIME and matching `.webm` or `.mp4` filename. Current Chrome verification produced VP8/VP9 + Opus WebM and H.264 + AAC MP4. Persistent proxy jobs, directory grants, streaming packages above 512 MiB, TTML animation/full layout fidelity, OCIO/ICC/HDR and container color tagging, broadcast certification, MOV/MXF/IMF delivery and explicit hardware-encoder control remain follow-up scope.
