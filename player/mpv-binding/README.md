# libmpv source-identity binding

This module vendors the minimal upstream `libmpv-android` wrapper and JNI sources needed by
Aurorama. It keeps libmpv itself unchanged.

## Upstream provenance

- Wrapper source: `jarnedemeulemeester/libmpv-android`, tag `v1.0.0`,
  commit `fcf6745703dc1265bca88f12fee8fc355ddf251e`.
- Native libmpv: `mpv v0.41.0`, commit
  `41f6a645068483470267271e1d09966ca3b9f413`.
- FFmpeg: `n8.1`, as built by the upstream wrapper's `v1.0.0` build scripts.
- Original Maven artifact: `dev.jdtech.mpv:libmpv:1.0.0`, SHA-256
  `DF146592480FC8418415A06B1F1A1D6318B0088E21F52254B0E9A82B61CA8FA2`.

The prebuilt non-wrapper native libraries in `src/main/jniLibs` are copied unchanged from that
AAR. This module rebuilds only `libplayer.so` from the vendored wrapper/JNI sources.

## Aurorama patch

- `MPVLib.EventObserver` receives a `playbackEvent` callback carrying playlist-entry and native
  generation identity.
- `commandForSource()` uses `mpv_command_ret()` and the `loadfile` result's
  `playlist_entry_id`.
- START_FILE/END_FILE use libmpv's `playlist_entry_id`; FILE_LOADED is bound to the preceding
  START_FILE on libmpv's single FIFO client-event queue.
- The source-event gate rejects stale playlist identities, native generations, duplicates and
  out-of-order START/FILE_LOADED/END sequences before `MPVPlayer`.

## Licenses

- Wrapper/JNI: MIT, see `LICENSE.libmpv-android.txt`.
- Aurorama integration and combined distribution: GPL-3.0, see `LICENSE.GPL-3.0.txt`.
- The bundled libmpv/FFmpeg binaries retain their upstream GPL obligations.
