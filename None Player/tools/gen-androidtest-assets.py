#!/usr/bin/env python3
"""Generate the synthetic androidTest media assets with ffmpeg.

The assets are intentionally gitignored. Run this script after a clean
checkout, before assembling/running the instrumentation tests.

Assets and the tests that consume them:
  np-h264.mp4          LegacyPlaybackTest (H.264 <-> soft-decode switches)
  np-mjpeg.avi         LegacyPlaybackTest, FormatProbeDecisionTest
  np-wmv2.avi          Wmv2PictureTest, FormatProbeDecisionTest, LegacyPlaybackTest
  xvid-clip.avi        LegacyPlaybackTest, QpelCodecProbeTest, Mpeg4ProfileProbeTest
  xvid-qpel.avi        LegacyPlaybackTest, QpelCodecProbeTest, Mpeg4ProfileProbeTest
  subtitle-queue.mp4   SubtitleQueueTest (video only; the SRT is written by the test)
  np-ps-seek.mpg       NpFfmpegPsSeekTest（core 专用：MPEG-PS 30s 回起点 seek）

The WMV2 fixture must be six vertical color bars (red, green, yellow, blue,
magenta, cyan) because Wmv2PictureTest samples those bars at y=230.
"""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
CORE_OUT = ROOT / "core" / "media-android" / "src" / "androidTest" / "assets"
PLUGIN_OUT = ROOT / "plugins" / "plugin-player-exo" / "src" / "androidTest" / "assets"
ASSETS = (
    "np-h264.mp4",
    "np-mjpeg.avi",
    "np-wmv2.avi",
    "xvid-clip.avi",
    "xvid-qpel.avi",
    "subtitle-queue.mp4",
)

# 只给 core 的提取器测试用（plugin-player-exo 不需要，避免重复 2MB）
CORE_ONLY_ASSETS = ("np-ps-seek.mpg",)


def ffmpeg_bin() -> str:
    env = os.environ.get("FFMPEG")
    if env:
        return env
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        exe = shutil.which("ffmpeg")
        if exe:
            return exe
        raise SystemExit("ffmpeg not found: set FFMPEG or install imageio-ffmpeg")


def run(ffmpeg: str, *args: str) -> None:
    subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def color_bars(path: Path) -> None:
    width, height = 320, 240
    colors = [
        (255, 0, 0),
        (0, 255, 0),
        (255, 255, 0),
        (0, 0, 255),
        (255, 0, 255),
        (0, 255, 255),
    ]
    image = Image.new("RGB", (width, height))
    pixels = image.load()
    for x in range(width):
        color = colors[min(x * len(colors) // width, len(colors) - 1)]
        for y in range(height):
            pixels[x, y] = color
    image.save(path)


def build(ffmpeg: str, out: Path) -> None:
    bars = out / "wmv2-bars.png"
    color_bars(bars)

    run(
        ffmpeg,
        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25:duration=6",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=6",
        "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p",
        "-b:v", "690k", "-g", "25",
        "-c:a", "aac", "-ac", "1", "-b:a", "126k", "-shortest",
        str(out / "np-h264.mp4"),
    )
    run(
        ffmpeg,
        "-f", "lavfi", "-i", "testsrc2=size=480x320:rate=15:duration=5",
        "-c:v", "mjpeg", "-q:v", "3", "-an",
        str(out / "np-mjpeg.avi"),
    )
    run(
        ffmpeg,
        "-loop", "1", "-i", str(bars), "-t", "3", "-r", "15",
        "-c:v", "wmv2", "-b:v", "700k", "-pix_fmt", "yuv420p", "-an",
        str(out / "np-wmv2.avi"),
    )
    run(
        ffmpeg,
        "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=15:duration=4",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=4",
        "-c:v", "libxvid", "-vtag", "XVID",
        "-c:a", "libmp3lame", "-ac", "1", "-ar", "44100", "-b:a", "96k", "-shortest",
        str(out / "xvid-clip.avi"),
    )
    run(
        ffmpeg,
        "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=15:duration=4",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=4",
        "-c:v", "libxvid", "-vtag", "XVID", "-flags", "+qpel",
        "-c:a", "libmp3lame", "-ac", "1", "-ar", "44100", "-b:a", "96k", "-shortest",
        str(out / "xvid-qpel.avi"),
    )
    run(
        ffmpeg,
        "-f", "lavfi", "-i", "color=c=0x202020:s=1920x1080:r=30:d=180",
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "35", "-g", "30",
        "-pix_fmt", "yuv420p", "-an",
        str(out / "subtitle-queue.mp4"),
    )
    # MPEG-PS：45s 足够让「回起点+丢包」seek 到 30s；音视频都有，seek 后要验证关键帧等待。
    run(
        ffmpeg,
        "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25:duration=45",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=45",
        "-c:v", "mpeg2video", "-b:v", "250k",
        "-c:a", "mp2", "-b:a", "96k",
        "-f", "vob",
        str(out / "np-ps-seek.mpg"),
    )


def main() -> int:
    ffmpeg = ffmpeg_bin()
    CORE_OUT.mkdir(parents=True, exist_ok=True)
    PLUGIN_OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="np-androidtest-assets-") as tmp:
        out = Path(tmp)
        build(ffmpeg, out)
        for name in ASSETS:
            data = (out / name).read_bytes()
            (CORE_OUT / name).write_bytes(data)
            (PLUGIN_OUT / name).write_bytes(data)
            print(
                f"{name} bytes={len(data)} "
                f"sha256={hashlib.sha256(data).hexdigest()}"
            )
        for name in CORE_ONLY_ASSETS:
            data = (out / name).read_bytes()
            (CORE_OUT / name).write_bytes(data)
            print(
                f"{name} bytes={len(data)} "
                f"sha256={hashlib.sha256(data).hexdigest()}"
            )
    # subtitle-queue.mp4 is only used by plugin-player-exo.
    core_extra = CORE_OUT / "subtitle-queue.mp4"
    if core_extra.exists():
        core_extra.unlink()
    print(f"core -> {CORE_OUT}")
    print(f"plugin -> {PLUGIN_OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
