# CLIPFORGE for Android

A native Android app (Android 10 and newer) that runs the whole CLIPFORGE pipeline on the
phone: no computer, no server, no upload. The app does not even request the internet
permission.

It follows the same rules as the desktop app: deterministic scene detection, the same
hybrid segmentation algorithm, crop or pad without stretching, H.264 + AAC MP4 output,
SHA-256 hashes and an Ed25519-signed provenance manifest. There is **no AI** in it.

## Install

1. Download `CLIPFORGE-1.0.0.apk`: from the repository's **Releases** page when a release
   exists, or from the latest successful run of the
   [Android workflow](https://github.com/sabhckr/clipforge/actions/workflows/android.yml)
   (artifact **CLIPFORGE-android-apk**, a ZIP that contains the APK and `SHA256SUMS.txt`).
2. Open the APK on the phone. Android asks you to allow installs from that source (your browser
   or file manager). Allow it for this install.
3. Open **CLIPFORGE**. On Android 13+ it asks for permission to show notifications; this is only
   used for the progress notification while clips render.

The APK is self-signed (it is not from the Play Store), so Android shows its usual warning for
apps from outside the store.

## Using it

| Screen | What it does |
|---|---|
| Home | Totals (videos, clips, storage, free space), active and recent projects |
| New | Pick a video (gallery or files), name it, adjust settings, start. You can also **Share** a video from the gallery to CLIPFORGE |
| Queue | Stage and progress, cancel, retry, delete, error output |
| Clips | Finished projects → clip grid with thumbnails, multi-select, ZIP export, save to gallery, share |
| Clip preview | Player, source time range, cut types, asset ID, SHA-256, previous/next |
| Exports | Export history, save ZIPs to Downloads, share, save manifests and the public key |
| Settings | Default processing settings, optional owner details, device encoders |
| Ownership | This phone's public key, re-verify a project, verify any CLIPFORGE ZIP or manifest (including ones made by the desktop app) |

Processing keeps running in the background with a progress notification. If the app is killed
mid-job, the job is requeued the next time you open it (up to 3 attempts).

Saved files go to `Movies/CLIPFORGE` (clips) and `Download/CLIPFORGE` (ZIPs, manifests, key).
No storage permission is needed for that on Android 10+.

## How it works on the phone

| Step | Desktop | Android |
|---|---|---|
| Validate / inspect | FFprobe | `MediaExtractor` + `MediaMetadataRetriever` (by content, not extension) |
| Scene detection | FFmpeg `scene` score | Every frame decoded with `MediaCodec`, reduced to a 64×36 block-averaged luma grid, scored with FFmpeg's formula: `min(mafd, |mafd − previous mafd|) / 100` |
| Segmentation | `segmentation.py` | Line-by-line Kotlin port, checked against 415 cases planned by the Python code |
| Render | FFmpeg + libx264 | Media3 Transformer: trim, crop/pad (never stretch), H.264 + AAC with the phone's encoders (hardware when available); HDR is tone-mapped to SDR |
| Hash + sign | SHA-256, Ed25519 (`cryptography`) | SHA-256, Ed25519 (Tink) with byte-identical canonical JSON |
| Key storage | PEM file, mode 0600 | Ed25519 seed sealed with an AES-256-GCM key held in the Android Keystore |

Manifests are interchangeable: the desktop **Ownership** page verifies manifests signed on a
phone, and the phone verifies desktop exports. Ed25519 is deterministic, and CI checks that the
Kotlin code produces the exact same signature bytes as the Python code for the same key and
manifest.

Differences from the desktop app, by design:

- Quality is a bitrate tier (`high`, `standard`, `compact`) because phone encoders take a
  bitrate, not an x264 CRF. The chosen bitrate is recorded in the manifest.
- Output is always MP4 (`MediaMuxer`). The `moov` atom is written at the end of the file
  (no fast-start). This does not matter for playback from the phone or after download.
- The manifest's `generator` records `engine` and `platform` (for example
  `Android Media3 1.5.1 (MediaCodec)`) instead of an FFmpeg version.

## Limits

- Speed depends on the phone's encoder. No speeds are claimed.
- 4K sources are decoded twice (scene pass + render pass) and can take a while on older phones.
- Videos are copied into the app's private storage before processing (so the source can be
  hashed and cannot change underneath a job). Make sure there is room: the app keeps 500 MB free.
- Uninstalling the app or clearing its data deletes its projects and its signing key. Clips you
  saved to the gallery or Downloads stay. Manifests signed with a deleted key still verify
  against the public key embedded in them, but no new manifests can be signed with it.

## Build from source

Requirements: JDK 17 and the Android SDK (API 35). From `android/`:

```bash
./gradlew assembleRelease
```

Without signing variables the release APK is signed with the debug key. To sign with your own
key, set `CLIPFORGE_KEYSTORE` (PKCS#12 file), `CLIPFORGE_KEYSTORE_PASSWORD` and
`CLIPFORGE_KEY_ALIAS`.

Tests:

```bash
./gradlew testDebugUnitTest            # JVM: segmentation, JSON, signatures vs desktop golden files
./gradlew connectedDebugAndroidTest    # device/emulator: probe, scenes, crop/rotation, pipeline, export, UI
```

`tools/make_golden.py` regenerates the golden files from the Python engine, and
`tools/make_test_assets.sh` regenerates the synthetic test videos with FFmpeg.
