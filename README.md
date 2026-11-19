# CLIPFORGE 1.0

[![CI](https://github.com/sabhckr/clipforge/actions/workflows/ci.yml/badge.svg)](https://github.com/sabhckr/clipforge/actions/workflows/ci.yml)

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

CLIPFORGE has two parts. The **server** does all the video processing (Python + FFmpeg). The
**web interface** is a regular website you open in a browser.

| Platform | Run the server | Use the interface | Status |
|---|---|---|---|
| macOS (Apple silicon / Intel) | Yes | Yes | Tested locally, plus CI |
| Linux | Yes | Yes | CI (Ubuntu) |
| Windows 10/11 | Yes | Yes | CI (Windows Server). Use the PowerShell steps in [INSTALL.md](INSTALL.md); the `scripts/*.sh` helpers are for macOS/Linux |
| Android 10+ | No (see below) | Yes, in Chrome/Firefox | Phone layout (375 px, Android Chrome UA) tested in browser emulation; not yet tested on a physical device |
| iOS / iPadOS | No | Yes, in Safari | Same responsive layout; not tested on a device |

**Using it from an Android phone.** Run the server on a Mac, Windows PC or Linux machine, then
open the interface in your phone's browser over your Wi-Fi. The five steps are in
[INSTALL.md › Use from an Android phone](INSTALL.md#use-from-an-android-phone-or-any-device-on-your-wi-fi).
From the phone you can upload videos from the gallery, watch progress, preview clips and
download them or the ZIP. The output (H.264 + AAC MP4, yuv420p) plays natively on Android 10+.

Typical Android recordings are HEVC (H.265), portrait with a rotation flag, and AAC stereo.
They are handled correctly: the server reads them as upright 1080×1920 and produces upright
clips.

Running the server *on* the phone (for example in Termux, which packages Python and FFmpeg) may
be possible but is not supported or tested.

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
