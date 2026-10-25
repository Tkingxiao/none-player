"""Generate an OpenDML (indx/ix##) AVI fixture with real WMV2+PCM payloads and NO idx1.

E227: the no-idx1 AVI feature-film path was playable (E204) but not seekable; the
extractor's super-index path needs a real device fixture. ffmpeg's AVI muxer only
writes OpenDML master indexes on >1GiB overflows, so this script restructures a
normal ffmpeg AVI by hand:

  1. ffmpeg encodes a still-image WMV2 video + PCM audio (same recipe as
     gen-androidtest-assets.py np-wmv2.avi, avoiding the testsrc2-motion WMV2
     `send -22` known issue in task doc §9.3).
  2. Python walks the RIFF, keeps strh/strf and the movi payload chunks, and
     rebuilds: hdrl(strl(strh+strf+indx)...) + odml(dmlh) + movi(ix## interleaved)
     with per-group ix## chunks and NO idx1.

Output: fixtures/avi-odml/np-odml-wmv2-pcm.avi (10 min by default, ~130MB).

Usage:
  python gen-avi-odml-fixture.py [--duration 600] [--ix-every 500] [--out DIR]
"""

import argparse
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path


def ffmpeg_bin() -> str:
    exe = __import__("os").environ.get("FFMPEG")
    if exe:
        return exe
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError:
        pass
    exe = shutil.which("ffmpeg")
    if not exe:
        raise SystemExit("ffmpeg not found: set FFMPEG or install imageio-ffmpeg")
    return exe


def run(ffmpeg: str, *args: str) -> None:
    subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def color_bars(path: Path) -> None:
    from PIL import Image

    colors = [
        (255, 255, 255),
        (255, 255, 0),
        (0, 255, 255),
        (0, 255, 0),
        (255, 0, 255),
        (255, 0, 0),
        (0, 0, 255),
        (16, 16, 16),
    ]
    width, height = 640, 360
    img = Image.new("RGB", (width, height))
    pixels = img.load()
    for x in range(width):
        color = colors[min(x * len(colors) // width, len(colors) - 1)]
        for y in range(height):
            pixels[x, y] = color
    img.save(path)


def u16(v: int) -> bytes:
    return struct.pack("<H", v)


def u32(v: int) -> bytes:
    return struct.pack("<I", v & 0xFFFFFFFF)


def u64(v: int) -> bytes:
    return struct.pack("<Q", v & 0xFFFFFFFFFFFFFFFF)


def fourcc(s: str) -> bytes:
    return s.encode("latin-1")


def chunk(cid: str, payload: bytes) -> bytes:
    return fourcc(cid) + u32(len(payload)) + payload + (b"\x00" if len(payload) % 2 else b"")


def parse_source(data: bytes) -> dict:
    """Walk the ffmpeg AVI: return per-stream (strh, strf) and the movi chunk list with
    per-chunk keyframe flags taken from the source idx1 (real encoder truth)."""
    assert data[:4] == fourcc("RIFF") and data[8:12] == fourcc("AVI "), "not an AVI"
    streams: list[dict] = []
    media: list[tuple[bytes, bytes, int]] = []  # (fourcc, payload, absolute position)
    idx1_entries: list[tuple[bytes, bool, int]] = []  # (ckid, keyframe, offset)
    movi_fcc_pos = -1
    pos = 12
    end = len(data)
    while pos + 8 <= end:
        cid = data[pos : pos + 4]
        size = struct.unpack("<I", data[pos + 4 : pos + 8])[0]
        if cid == fourcc("LIST"):
            ltype = data[pos + 8 : pos + 12]
            if ltype == fourcc("hdrl"):
                p = pos + 12
                hend = pos + 8 + size
                while p + 8 <= hend:
                    cid2 = data[p : p + 4]
                    sz2 = struct.unpack("<I", data[p + 4 : p + 8])[0]
                    if cid2 == fourcc("LIST") and data[p + 8 : p + 12] == fourcc("strl"):
                        stream: dict = {"strh": None, "strf": None}
                        q = p + 12
                        qend = p + 8 + sz2
                        while q + 8 <= qend:
                            cid3 = data[q : q + 4]
                            sz3 = struct.unpack("<I", data[q + 4 : q + 8])[0]
                            if cid3 == fourcc("strh"):
                                stream["strh"] = data[q + 8 : q + 8 + sz3]
                            elif cid3 == fourcc("strf"):
                                stream["strf"] = data[q + 8 : q + 8 + sz3]
                            q += 8 + sz3 + (sz3 & 1)
                        streams.append(stream)
                    p += 8 + sz2 + (sz2 & 1)
            elif ltype == fourcc("movi"):
                movi_fcc_pos = pos
                p = pos + 12
                mend = min(pos + 8 + size, end)
                while p + 8 <= mend:
                    sz2 = struct.unpack("<I", data[p + 4 : p + 8])[0]
                    media.append((data[p : p + 4], data[p + 8 : p + 8 + sz2], p))
                    p += 8 + sz2 + (sz2 & 1)
        elif cid == fourcc("idx1"):
            p = pos + 8
            for _ in range(size // 16):
                ck = data[p : p + 4]
                flags, off = struct.unpack("<II", data[p + 4 : p + 12])
                idx1_entries.append((ck, bool(flags & 0x10), off))
                p += 16
        pos += 8 + size + (size & 1)
    assert streams, "no strl in source"
    assert media, "no movi payload in source"

    # idx1 偏移的基址口径与提取器同款：movi fourcc / movi 数据起点 / 绝对 0。
    # 用首条 entry 的 fourcc 对账，命中后按「绝对位置 → 关键帧位」回填 media。
    by_position: dict[int, tuple[bytes, bool]] = {}
    if idx1_entries:
        for base in (movi_fcc_pos + 8, movi_fcc_pos + 12, movi_fcc_pos, 0):
            if base < 0:
                continue
            first = idx1_entries[0]
            probe = data[base + first[2] : base + first[2] + 4]
            if probe == first[0]:
                by_position = {base + e[2]: (e[0], e[1]) for e in idx1_entries}
                break
        assert by_position, "idx1 base not resolved"
    media = [(fcc, payload, by_position.get(p, (fcc, False))[1]) for fcc, payload, p in media]
    return {"streams": streams, "media": media}


def stream_no(fcc: bytes) -> int:
    return int(fcc[:2], 16)


def build_odml(source: dict, ix_every: int) -> bytes:
    streams = source["streams"]
    media = source["media"]

    video_no = next(i for i, s in enumerate(streams) if s["strh"][:4] == fourcc("vids"))
    media_by_stream: dict[int, list[tuple[bytes, bytes, bool]]] = {i: [] for i in range(len(streams))}
    for fcc, payload, keyframe in media:
        n = stream_no(fcc)
        media_by_stream[n].append((fcc, payload, keyframe))

    video_kind = media_by_stream[video_no][0][0][2:4].decode("latin-1")  # "dc"/"db"
    video_groups = [media_by_stream[video_no][i : i + ix_every] for i in range(0, len(media_by_stream[video_no]), ix_every)]

    # ---- hdrl（先空 indx 占位定尺寸，两遍布局）----
    def build_hdrl(video_indx: bytes, audio_indxs: list[bytes]) -> bytes:
        strl_v = chunk("LIST", fourcc("strl") + chunk("strh", streams[video_no]["strh"]) + chunk("strf", streams[video_no]["strf"]) + video_indx)
        body = fourcc("hdrl") + strl_v
        for i, s in enumerate(streams):
            if i == video_no:
                continue
            body += chunk(
                "LIST",
                fourcc("strl") + chunk("strh", s["strh"]) + chunk("strf", s["strf"]) + audio_indxs[i if i < video_no else i - 1],
            )
        dmlh = bytearray(56)
        struct.pack_into("<I", dmlh, 0, len(media_by_stream[video_no]))
        body += chunk("LIST", fourcc("odml") + chunk("dmlh", bytes(dmlh)))
        return chunk("LIST", body)

    audio_nos = [i for i in range(len(streams)) if i != video_no]
    audio_groups = {i: [media_by_stream[i][j : j + ix_every] for j in range(0, len(media_by_stream[i]), ix_every)] for i in audio_nos}

    probe_v_indx = chunk("indx", bytearray(20 + len(video_groups) * 16))
    probe_a_indxs = [chunk("indx", bytearray(20 + len(audio_groups[i]) * 16)) for i in audio_nos]
    hdrl_probe = build_hdrl(probe_v_indx, probe_a_indxs)
    movi_fcc_pos = 12 + len(hdrl_probe)
    movi_data_start = movi_fcc_pos + 12

    # 与 ffmpeg avienc 同一口径：idx/ix 偏移相对 **movi 字符串**位置（LIST 头 +8）
    written_base = movi_fcc_pos + 8

    # ---- 布局：组间交错（视频组 g → 各音频组 g …），先算全部绝对偏移 ----
    frame_offsets: list[dict[int, list[int]]] = []
    ix_offsets_v: list[int] = []
    ix_offsets_a: dict[int, list[int]] = {i: [] for i in audio_nos}
    pos = movi_data_start
    max_groups = max([len(video_groups)] + [len(audio_groups[i]) for i in audio_nos])
    for g in range(max_groups):
        if g < len(video_groups):
            ix_offsets_v.append(pos)
            pos += 8 + 20 + len(video_groups[g]) * 8
            offs = []
            for fcc, payload, _kf in video_groups[g]:
                offs.append(pos)
                pos += 8 + len(payload) + (len(payload) & 1)
            frame_offsets.append(offs)
        for i in audio_nos:
            if g < len(audio_groups[i]):
                ix_offsets_a[i].append(pos)
                pos += 8 + 20 + len(audio_groups[i][g]) * 8
                for fcc, payload, _kf in audio_groups[i][g]:
                    pos += 8 + len(payload) + (len(payload) & 1)

    # ---- 生成 movi 字节（ix 条目相对 written_base）----
    def build_ix(stream_no: int, kind: str, offsets: list[int], group: list[tuple[bytes, bytes, bool]]) -> bytes:
        n = len(group)
        payload = bytearray(20 + n * 8)
        struct.pack_into("<H", payload, 0, 2)
        payload[2] = 0
        payload[3] = 1  # AVI_INDEX_OF_CHUNKS
        struct.pack_into("<I", payload, 4, n)
        payload[8:12] = fourcc("%02d%s" % (stream_no, kind))
        struct.pack_into("<I", payload, 12, written_base & 0xFFFFFFFF)
        for i, ((fcc, payload_chunk, keyframe), off) in enumerate(zip(group, offsets)):
            entry = 20 + i * 8
            struct.pack_into("<I", payload, entry, (off - written_base) & 0xFFFFFFFF)
            # OpenDML DELTAFRAME 位：置位 = 差分帧；关键帧（源 idx1 的 0x10 位）保持清零
            size_flags = len(payload_chunk) & 0x7FFFFFFF
            if not keyframe:
                size_flags |= 0x80000000
            struct.pack_into("<I", payload, entry + 4, size_flags & 0xFFFFFFFF)
        return chunk("%02dix" % stream_no, bytes(payload))

    movi = bytearray()
    movi += fourcc("LIST")
    movi += u32(4 + (pos - movi_data_start))
    movi += fourcc("movi")
    for g in range(max_groups):
        if g < len(video_groups):
            movi += build_ix(video_no, video_kind, frame_offsets[g], video_groups[g])
            for fcc, payload, _kf in video_groups[g]:
                movi += fcc + u32(len(payload)) + payload + (b"\x00" if len(payload) % 2 else b"")
        for i in audio_nos:
            if g < len(audio_groups[i]):
                kind = audio_groups[i][g][0][0][2:4].decode("latin-1")
                offs = []
                p = ix_offsets_a[i][g] + 8 + 20 + len(audio_groups[i][g]) * 8
                for fcc, payload, _kf in audio_groups[i][g]:
                    offs.append(p)
                    p += 8 + len(payload) + (len(payload) & 1)
                movi += build_ix(i, kind, offs, audio_groups[i][g])
                for fcc, payload, _kf in audio_groups[i][g]:
                    movi += fcc + u32(len(payload)) + payload + (b"\x00" if len(payload) % 2 else b"")

    # ---- indx（ix 块绝对偏移）----
    def build_indx(stream_no: int, kind: str, ix_offs: list[int], ix_chunk_sizes: list[int], durations: list[int]) -> bytes:
        n = len(ix_offs)
        payload = bytearray(20 + n * 16)
        struct.pack_into("<H", payload, 0, 4)
        payload[2] = 0
        payload[3] = 0  # AVI_INDEX_OF_INDEXES
        struct.pack_into("<I", payload, 4, n)
        payload[8:12] = fourcc("%02d%s" % (stream_no, kind))
        for i in range(n):
            entry = 20 + i * 16
            struct.pack_into("<Q", payload, entry, ix_offs[i])
            struct.pack_into("<I", payload, entry + 8, ix_chunk_sizes[i])
            struct.pack_into("<I", payload, entry + 12, durations[i])
        return chunk("indx", bytes(payload))

    v_indx = build_indx(
        video_no,
        video_kind,
        ix_offsets_v,
        [8 + 20 + len(g) * 8 for g in video_groups],
        [len(g) for g in video_groups],
    )
    a_indxs = []
    for i in audio_nos:
        a_indxs.append(
            build_indx(
                i,
                media_by_stream[i][0][0][2:4].decode("latin-1"),
                ix_offsets_a[i],
                [8 + 20 + len(g) * 8 for g in audio_groups[i]],
                [len(g) for g in audio_groups[i]],
            )
        )
    hdrl = build_hdrl(v_indx, a_indxs)
    body = fourcc("AVI ") + hdrl + bytes(movi)
    return fourcc("RIFF") + u32(len(body)) + body


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--duration", type=int, default=600)
    ap.add_argument("--rate", type=int, default=25)
    ap.add_argument("--audio-rate", type=int, default=48000)
    ap.add_argument("--ix-every", type=int, default=500)
    ap.add_argument("--out", type=Path, default=Path(__file__).resolve().parent.parent.parent / "fixtures" / "avi-odml")
    ap.add_argument("--name", default="np-odml-wmv2-pcm.avi")
    args = ap.parse_args()

    ffmpeg = ffmpeg_bin()
    args.out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="np-odml-") as tmp:
        tmp = Path(tmp)
        bars = tmp / "bars.png"
        color_bars(bars)
        src = tmp / "src.avi"
        run(
            ffmpeg,
            "-loop", "1", "-i", str(bars), "-t", str(args.duration), "-r", str(args.rate),
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=%d:duration=%d" % (args.audio_rate, args.duration),
            "-c:v", "wmv2", "-b:v", "700k", "-pix_fmt", "yuv420p",
            "-c:a", "pcm_s16le", "-ac", "1",
            "-shortest",
            str(src),
        )
        source = parse_source(src.read_bytes())
        out = build_odml(source, args.ix_every)
        dst = args.out / args.name
        dst.write_bytes(out)

        n_video = sum(1 for fcc, _, _ in source["media"] if stream_no(fcc) == 0)
        n_audio = len(source["media"]) - n_video
        v_keys = sum(1 for fcc, _, kf in source["media"] if stream_no(fcc) == 0 and kf)
        a_keys = sum(1 for fcc, _, kf in source["media"] if stream_no(fcc) != 0 and kf)
        print(f"written {dst} ({len(out)} bytes)")
        print(f"  media chunks: video={n_video} audio={n_audio} (src idx1 keyframes: video={v_keys} audio={a_keys})")
        print(f"  ix groups: video=ceil({n_video}/{args.ix_every}) audio=ceil({n_audio}/{args.ix_every})")
        print(f"  idx1 present: NO (OpenDML indx/ix## only)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
