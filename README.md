# CLIPFORGE 1.0

[![CI](https://github.com/sabhckr/clipforge/actions/workflows/ci.yml/badge.svg)](https://github.com/sabhckr/clipforge/actions/workflows/ci.yml)
[![Android](https://github.com/sabhckr/clipforge/actions/workflows/android.yml/badge.svg)](https://github.com/sabhckr/clipforge/actions/workflows/android.yml)

Deterministic video repurposing. CLIPFORGE turns one long video into a set of short clips
(9:16 vertical by default) using FFmpeg scene detection and fixed rules. Every project gets a
signed provenance manifest. It uses **no AI**: no generative models, no LLMs, no speech
recognition, no cloud inference.

Use it for videos you own or have permission to process.

## What it does

1. **Validates** the upload by content (FFprobe), not by file extension.
2. **Inspects** duration, resolution, rotation, codecs and audio.
3. **Detects scene changes** with FFmpeg's `scene` score (`select='gt(scene,T)'`). This is a
   deterministic frame-difference metric.
4. **Plans clips** around a target length (default 30 s). Each cut snaps to the nearest scene
   change within ±5 s, while respecting the minimum and maximum lengths. If scene detection
   fails, it falls back to fixed intervals.
5. **Renders** each clip on its own: H.264 (libx264 or a hardware encoder), AAC audio, yuv420p,
   MP4/MOV with fast-start, 1080×1920 by default. It scales and crops (or pads) and never
   stretches.
6. **Hashes** the source and every clip (SHA-256) and writes a **signed manifest** (Ed25519)
   that records which clips came from which source.
7. **Exports** single clips, selected clips or all clips as a ZIP. Each ZIP contains the
   manifest, thumbnails, the public key and a verification report.

## Quick start (macOS)

```bash
brew install ffmpeg python@3.13 node
cd clipforge
scripts/setup.sh
scripts/start-dev.sh
```

Then open http://127.0.0.1:5173.

To serve the UI and API from one port instead (http://127.0.0.1:8710):

```bash
scripts/start.sh
```

To run every automated check:

```bash
scripts/test.sh
```

Windows and Linux setup, configuration and troubleshooting are in [INSTALL.md](INSTALL.md).

## Platform support

CLIPFORGE comes in two forms:

- **Desktop/server app** (Python + FFmpeg, with a web interface you open in a browser).
- **Native Android app** ([android/](android/README.md)) that does all the processing on the
  phone itself, offline. It uses the same segmentation rules and the same signed-manifest
  format, so either one verifies the other's exports.

| Platform | How it runs | Status |
|---|---|---|
| macOS (Apple silicon / Intel) | Desktop app | Tested locally, plus CI |
| Linux | Desktop app | CI (Ubuntu) |
| Windows 10/11 | Desktop app | CI (Windows Server). Use the PowerShell steps in [INSTALL.md](INSTALL.md); the `scripts/*.sh` helpers are for macOS/Linux |
| Android 10+ | **Native app (APK)**, fully on the phone | CI: build, unit tests and emulator tests on Android 10 (API 29) and Android 15 (API 35). Not yet tested on a physical phone |
| Android / iOS browser | Web interface of a desktop app on your Wi-Fi | Phone layout tested in browser emulation ([INSTALL.md](INSTALL.md#use-from-an-android-phone-or-any-device-on-your-wi-fi)) |

### Android app

Download `CLIPFORGE-1.0.0.apk` (Releases, or the **CLIPFORGE-android-apk** artifact of the
latest [Android workflow run](https://github.com/sabhckr/clipforge/actions/workflows/android.yml)),
open it on the phone and allow the install. Pick a video from the gallery (or **Share** one to
CLIPFORGE), and it detects scenes, cuts and renders clips with the phone's own H.264 encoder,
then signs a manifest. No computer, no upload, and the app has no internet permission.
Details, differences from the desktop engine and limits: [android/README.md](android/README.md).

Typical Android recordings are HEVC (H.265), portrait with a rotation flag, and AAC stereo.
Both apps handle them: they read them as upright and produce upright clips (covered by tests).

## Pages

| Page | Purpose |
|---|---|
| Dashboard | Real totals (videos, clips, storage, free disk), active jobs, recent jobs, quick upload |
| New project | Drag-and-drop or browse, a file check before upload, per-project settings, upload progress |
| Processing queue | Status, stage, progress, clip count, error output; cancel, retry and delete |
| Clip gallery | Thumbnails, time ranges, multi-select, ZIP export of selected or all clips, downloads |
| Clip preview | Player, metadata, asset ID, SHA-256, previous/next |
| Export center | Full-project export, manifest and public-key download, export history |
| Settings | Default processing settings, optional owner details, system info |
| Ownership | Public key, on-disk project verification, upload-and-verify of manifests and files |

## Export layout

```
CLIPFORGE_EXPORT/
  project.json                     project summary, settings, included clips
  manifest.json                    signed provenance manifest (all clips in the project)
  clips/clip_0001.mp4 …
  thumbnails/clip_0001.jpg …
  verification/
    public_key.pem                 key to verify manifest.json
    verification_report.json       hash and signature check made when the ZIP was built
```

## Documentation

- [INSTALL.md](INSTALL.md): setup on macOS, Windows and Linux, configuration, troubleshooting
- [ARCHITECTURE.md](ARCHITECTURE.md): modules, pipeline, segmentation algorithm, storage layout
- [API.md](API.md): HTTP endpoints
- [SECURITY.md](SECURITY.md): threat model, signing keys, network deployment, provenance limits
- [TESTING.md](TESTING.md): test suites and what they cover
- [android/README.md](android/README.md): the native Android app

## Honest limitations

- Center, left, right and custom crops are **fixed** regions. CLIPFORGE does not detect faces
  or speakers.
- Scene detection finds visual cuts, not topic changes. Talking-head footage with no cuts falls
  back to fixed-length clips.
- Signed manifests prove **integrity and which key signed them**. They do not prevent copying
  and do not establish copyright ownership.
- No processing speeds are claimed. Speed depends on source resolution, preset and hardware.
- Built-in authentication is a single shared token. It is meant for a private network behind
  HTTPS, not for multi-user public hosting (see [SECURITY.md](SECURITY.md)).
