#!/usr/bin/env python3
"""Print the video track's dimensions, frame count, duration, fps and bitrate of an MP4.

Standard library only. Reads the moov box: trak/tkhd (display size), mdia/hdlr (track type),
mdia/mdhd (timescale, duration), minf/stbl/stts (sample durations), stsz (sample sizes) and
stsd (codec and coded size). Handles 64-bit box sizes and version 0/1 tkhd and mdhd.

Usage:
    python3 tools/mp4info.py FILE.mp4 [...]
    python3 tools/mp4info.py --selftest
"""
import os
import struct
import sys
import tempfile

CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl", b"edts", b"dinf"}


def iter_boxes(f, start, end):
    """Yield (type, payload_start, box_end) for boxes in [start, end)."""
    pos = start
    while pos + 8 <= end:
        f.seek(pos)
        size, kind = struct.unpack(">I4s", f.read(8))
        header = 8
        if size == 1:
            size = struct.unpack(">Q", f.read(8))[0]
            header = 16
        elif size == 0:
            size = end - pos
        if size < header:
            raise ValueError("bad box size %d at offset %d" % (size, pos))
        yield kind, pos + header, min(pos + size, end)
        pos += size


def find(f, start, end, kind):
    for k, s, e in iter_boxes(f, start, end):
        if k == kind:
            return s, e
    return None


def read_at(f, offset, n):
    f.seek(offset)
    data = f.read(n)
    if len(data) != n:
        raise ValueError("truncated box at offset %d" % offset)
    return data


def parse_track(f, s, e):
    t = {}
    tkhd = find(f, s, e, b"tkhd")
    if tkhd:
        version = read_at(f, tkhd[0], 1)[0]
        # tkhd ends with width and height as 16.16 fixed point.
        w, h = struct.unpack(">II", read_at(f, tkhd[1] - 8, 8))
        t["display"] = (w >> 16, h >> 16)
        t["tkhd_version"] = version
    mdia = find(f, s, e, b"mdia")
    if not mdia:
        return t
    hdlr = find(f, mdia[0], mdia[1], b"hdlr")
    if hdlr:
        t["handler"] = read_at(f, hdlr[0] + 8, 4)
    mdhd = find(f, mdia[0], mdia[1], b"mdhd")
    if mdhd:
        version = read_at(f, mdhd[0], 1)[0]
        if version == 1:
            timescale, duration = struct.unpack(">IQ", read_at(f, mdhd[0] + 20, 12))
        else:
            timescale, duration = struct.unpack(">II", read_at(f, mdhd[0] + 12, 8))
        t["timescale"], t["mdhd_duration"] = timescale, duration
    minf = find(f, mdia[0], mdia[1], b"minf")
    stbl = minf and find(f, minf[0], minf[1], b"stbl")
    if not stbl:
        return t
    stsd = find(f, stbl[0], stbl[1], b"stsd")
    if stsd:
        entry = stsd[0] + 8  # version/flags + entry count
        kind = read_at(f, entry + 4, 4)
        t["codec"] = kind.decode("latin-1")
        if t.get("handler") == b"vide":
            # VisualSampleEntry: 8 header + 6 reserved + 2 index + 16 predefined, then w, h.
            t["coded"] = struct.unpack(">HH", read_at(f, entry + 32, 4))
    stts = find(f, stbl[0], stbl[1], b"stts")
    if stts:
        (count,) = struct.unpack(">I", read_at(f, stts[0] + 4, 4))
        raw = read_at(f, stts[0] + 8, 8 * count)
        frames = total = 0
        deltas = {}
        for i in range(count):
            n, d = struct.unpack_from(">II", raw, 8 * i)
            frames += n
            total += n * d
            deltas[d] = deltas.get(d, 0) + n
        t["frames"], t["stts_duration"], t["deltas"] = frames, total, deltas
    stsz = find(f, stbl[0], stbl[1], b"stsz")
    if stsz:
        fixed, count = struct.unpack(">II", read_at(f, stsz[0] + 4, 8))
        if fixed:
            t["bytes"] = fixed * count
        else:
            raw = read_at(f, stsz[0] + 12, 4 * count)
            t["bytes"] = sum(struct.unpack(">%dI" % count, raw))
        t["samples"] = count
    return t


def analyze(path):
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        moov = find(f, 0, size, b"moov")
        if not moov:
            raise ValueError("no moov box (not an MP4, or still being written)")
        tracks = [parse_track(f, s, e) for k, s, e in iter_boxes(f, moov[0], moov[1]) if k == b"trak"]
    video = [t for t in tracks if t.get("handler") == b"vide"]
    if not video:
        raise ValueError("no video track")
    t = video[0]
    t["file_size"] = size
    t["track_count"] = len(tracks)
    return t


def report(path):
    t = analyze(path)
    ts = t.get("timescale") or 1
    dur_units = t.get("stts_duration") or t.get("mdhd_duration") or 0
    seconds = dur_units / ts
    frames = t.get("frames", 0)
    fps = frames / seconds if seconds else 0.0
    bits = t.get("bytes", 0) * 8
    lines = [
        path,
        "  tracks          %d (video codec %s)" % (t["track_count"], t.get("codec", "?")),
        "  dimensions      %s x %s (coded %s)" % (t.get("display", ("?", "?"))[0], t.get("display", ("?", "?"))[1],
                                                 "x".join(str(v) for v in t.get("coded", ())) or "?"),
        "  frames          %d" % frames,
        "  duration        %.3f s (mdhd %.3f s, timescale %d)" % (seconds, t.get("mdhd_duration", 0) / ts, ts),
        "  average fps     %.2f" % fps,
        "  frame durations %s" % ", ".join("%d x %d" % (n, d) for d, n in sorted(t.get("deltas", {}).items())),
        "  video bitrate   %.2f Mbps (%d video bytes, file %d bytes)" % (
            bits / seconds / 1e6 if seconds else 0.0, t.get("bytes", 0), t["file_size"]),
    ]
    return "\n".join(lines)


# ---- self test: a synthetic MP4 with only the boxes this tool reads ----

def box(kind, payload):
    return struct.pack(">I4s", 8 + len(payload), kind) + payload


def full(kind, version, payload):
    return box(kind, struct.pack(">I", version << 24) + payload)


def synthetic(width, height, frames, timescale, delta, sample_size, version=0, large_mdat=False):
    if version == 1:
        tkhd = full(b"tkhd", 1, bytes(8 + 8 + 4 + 4 + 8 + 8 + 2 + 2 + 2 + 2 + 36) + struct.pack(">II", width << 16, height << 16))
        mdhd = full(b"mdhd", 1, struct.pack(">QQIQ", 0, 0, timescale, frames * delta) + bytes(4))
    else:
        tkhd = full(b"tkhd", 0, bytes(4 + 4 + 4 + 4 + 4 + 8 + 2 + 2 + 2 + 2 + 36) + struct.pack(">II", width << 16, height << 16))
        mdhd = full(b"mdhd", 0, struct.pack(">IIII", 0, 0, timescale, frames * delta) + bytes(4))
    hdlr = full(b"hdlr", 0, bytes(4) + b"vide" + bytes(12) + b"\0")
    entry = box(b"avc1", bytes(6) + struct.pack(">H", 1) + bytes(16) + struct.pack(">HH", width, height) + bytes(50))
    stsd = full(b"stsd", 0, struct.pack(">I", 1) + entry)
    # Two stts runs to exercise the summing.
    first = frames // 2
    stts = full(b"stts", 0, struct.pack(">III", 2, first, delta) + struct.pack(">II", frames - first, delta))
    stsz = full(b"stsz", 0, struct.pack(">II", 0, frames) + struct.pack(">%dI" % frames, *([sample_size] * frames)))
    stbl = box(b"stbl", stsd + stts + stsz)
    minf = box(b"minf", stbl)
    mdia = box(b"mdia", mdhd + hdlr + minf)
    trak = box(b"trak", tkhd + mdia)
    moov = box(b"moov", trak)
    ftyp = box(b"ftyp", b"isom" + bytes(4) + b"isommp42")
    if large_mdat:
        mdat = struct.pack(">I4sQ", 1, b"mdat", 16 + 32) + bytes(32)
    else:
        mdat = box(b"mdat", bytes(32))
    return ftyp + mdat + moov


def selftest():
    cases = [
        # 1416 frames at 60 fps (timescale 90000, 1500 per frame), like the 1:1 device export.
        dict(width=1080, height=1080, frames=1416, timescale=90000, delta=1500, sample_size=10204, version=0),
        dict(width=1080, height=1920, frames=300, timescale=60000, delta=1000, sample_size=41667, version=1, large_mdat=True),
    ]
    for c in cases:
        with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as tmp:
            tmp.write(synthetic(**c))
            path = tmp.name
        try:
            t = analyze(path)
            seconds = t["stts_duration"] / t["timescale"]
            assert t["display"] == (c["width"], c["height"]), t["display"]
            assert t["coded"] == (c["width"], c["height"]), t["coded"]
            assert t["frames"] == c["frames"], t["frames"]
            assert abs(c["frames"] / seconds - 60.0) < 1e-9, seconds
            assert t["bytes"] == c["frames"] * c["sample_size"], t["bytes"]
            assert t["codec"] == "avc1"
            print(report(path))
        finally:
            os.unlink(path)
    print("selftest ok")


def main(argv):
    if len(argv) < 2 or argv[1] in ("-h", "--help"):
        print(__doc__.strip())
        return 2
    if argv[1] == "--selftest":
        selftest()
        return 0
    status = 0
    for path in argv[1:]:
        try:
            print(report(path))
        except (OSError, ValueError, struct.error) as e:
            print("%s: %s" % (path, e), file=sys.stderr)
            status = 1
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv))
