#!/usr/bin/env bash
# Generates the small synthetic videos used by the instrumented (emulator) tests.
# Requires FFmpeg with libx264, libx265 and the native AAC encoder.
# Usage (from the repository root): android/tools/make_test_assets.sh
set -euo pipefail
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/androidTest/assets"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT"
FF=(ffmpeg -hide_banner -loglevel error -y)
X264=(-c:v libx264 -preset medium -crf 30 -pix_fmt yuv420p -g 30 -bf 0)

# scenes.mp4 — 640x360 @15 fps, 26 s, hard cuts at exactly 8 s and 17 s, AAC tone.
"${FF[@]}" \
  -f lavfi -i "testsrc2=size=640x360:rate=15:duration=8" \
  -f lavfi -i "smptebars=size=640x360:rate=15:duration=9" \
  -f lavfi -i "testsrc=size=640x360:rate=15:duration=9" \
  -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=26" \
  -filter_complex "[0:v][1:v][2:v]concat=n=3:v=1:a=0,format=yuv420p[v]" \
  -map "[v]" -map 3:a "${X264[@]}" -c:a aac -b:a 96k -ac 2 -movflags +faststart "$OUT/scenes.mp4"

# redblue.mp4 — 640x360 landscape, left half red, right half blue, 6 s, no audio.
"${FF[@]}" \
  -f lavfi -i "color=c=red:size=320x360:rate=15:duration=6" \
  -f lavfi -i "color=c=blue:size=320x360:rate=15:duration=6" \
  -filter_complex "[0:v][1:v]hstack=inputs=2,format=yuv420p[v]" \
  -map "[v]" "${X264[@]}" "$TMP/redblue_src.mp4"
cp "$TMP/redblue_src.mp4" "$OUT/redblue.mp4"

# redblue_rot.mp4 — same picture stored as portrait 360x640 frames plus a display-rotation
# flag, the way phones record. Displayed upright it must look exactly like redblue.mp4.
"${FF[@]}" -i "$TMP/redblue_src.mp4" -vf "transpose=1" "${X264[@]}" "$TMP/rot_coded.mp4"
"${FF[@]}" -display_rotation:v:0 90 -i "$TMP/rot_coded.mp4" -c copy "$OUT/redblue_rot.mp4"

# phone_hevc.mp4 — HEVC portrait recording style: 640x360 coded + rotation flag, 12 s, AAC.
"${FF[@]}" \
  -f lavfi -i "testsrc2=size=640x360:rate=15:duration=12" \
  -f lavfi -i "sine=frequency=660:sample_rate=48000:duration=12" \
  -c:v libx265 -preset fast -crf 32 -pix_fmt yuv420p -tag:v hvc1 -x265-params log-level=error:bframes=0 \
  -c:a aac -b:a 64k -shortest "$TMP/hevc_coded.mp4"
"${FF[@]}" -display_rotation:v:0 90 -i "$TMP/hevc_coded.mp4" -c copy "$OUT/phone_hevc.mp4"

ls -la "$OUT"
