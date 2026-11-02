#!/usr/bin/env python3
"""Checks the FFmpeg decoders inside a Files APK (or a libffmpegJNI.so).

Every lib/<abi>/libffmpegJNI.so must define exactly the decoders listed in
scripts/ffmpeg/versions.env (FFMPEG_DECODERS) and none of the decoders that were
removed on purpose (AAC, E-AC-3, DTS, MLP, TrueHD: those formats are left to the
phone's own decoders). FFmpeg's decoders are global data symbols named
ff_<name>_decoder; libffmpegJNI.so exports them in its dynamic symbol table,
which stays after stripping, so the shipped APK itself is checked.

Usage: check-ffmpeg-decoders.py <app.apk | libffmpegJNI.so>...

Copyright 2026 CLP Studio. Licensed under the Apache License, Version 2.0.
"""
import os
import re
import shlex
import struct
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REMOVED = {"aac", "aac_fixed", "aac_latm", "eac3", "dca", "mlp", "truehd"}
ABIS = {"arm64-v8a", "armeabi-v7a"}


def expected_decoders():
    with open(os.path.join(ROOT, "scripts", "ffmpeg", "versions.env"), encoding="utf-8") as f:
        for line in f:
            m = re.match(r"\s*FFMPEG_DECODERS=(.*)$", line)
            if m:
                return set(shlex.split(m.group(1))[0].split())
    raise SystemExit("FFMPEG_DECODERS not found in versions.env")


def dynamic_symbols(data):
    """Names of the defined symbols in an ELF file's .dynsym (32/64-bit, little-endian)."""
    if data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("not a little-endian ELF file")
    is64 = data[4] == 2
    if is64:
        shoff, = struct.unpack_from("<Q", data, 0x28)
        shentsize, shnum = struct.unpack_from("<HH", data, 0x3A)
    else:
        shoff, = struct.unpack_from("<I", data, 0x20)
        shentsize, shnum = struct.unpack_from("<HH", data, 0x2E)
    sections = []
    for i in range(shnum):
        off = shoff + i * shentsize
        if is64:
            _, sh_type, _, _, sh_offset, sh_size, sh_link, _, _, sh_entsize = struct.unpack_from("<IIQQQQIIQQ", data, off)
        else:
            _, sh_type, _, _, sh_offset, sh_size, sh_link, _, _, sh_entsize = struct.unpack_from("<IIIIIIIIII", data, off)
        sections.append((sh_type, sh_offset, sh_size, sh_link, sh_entsize))
    names = set()
    for sh_type, off, size, link, entsize in sections:
        if sh_type != 11:  # SHT_DYNSYM
            continue
        str_off = sections[link][1]
        for i in range(1, size // entsize):
            e = off + i * entsize
            if is64:
                st_name, _, _, st_shndx = struct.unpack_from("<IBBH", data, e)
            else:
                st_name, = struct.unpack_from("<I", data, e)
                st_shndx, = struct.unpack_from("<H", data, e + 14)
            if st_shndx == 0:  # undefined
                continue
            end = data.index(b"\x00", str_off + st_name)
            names.add(data[str_off + st_name:end].decode("ascii", "replace"))
    return names


def decoders_in(data):
    return {m.group(1) for n in dynamic_symbols(data) for m in [re.fullmatch(r"ff_([a-z0-9_]+)_decoder", n)] if m}


def main(paths):
    expected = expected_decoders()
    bad = REMOVED & expected
    if bad:
        print(f"error: versions.env enables removed decoders: {sorted(bad)}")
        return 1
    libs = []
    for path in paths:
        if path.endswith(".so"):
            libs.append((path, open(path, "rb").read()))
            continue
        with zipfile.ZipFile(path) as z:
            found = sorted(n for n in z.namelist() if n.endswith("/libffmpegJNI.so"))
            abis = {n.split("/")[1] for n in found}
            if abis != ABIS:
                print(f"error: {path}: libffmpegJNI.so ABIs {sorted(abis)}, expected {sorted(ABIS)}")
                return 1
            libs += [(f"{path}!{n}", z.read(n)) for n in found]
    failed = False
    for name, data in libs:
        got = decoders_in(data)
        if got != expected:
            failed = True
            print(f"error: {name}: decoders {sorted(got)}; expected exactly {sorted(expected)}"
                  f" (missing {sorted(expected - got)}, unexpected {sorted(got - expected)})")
        elif got & REMOVED:
            failed = True
            print(f"error: {name}: removed decoders present: {sorted(got & REMOVED)}")
        else:
            print(f"ok: {name}: exactly {len(got)} decoders {sorted(got)}; absent: {sorted(REMOVED)}")
    return 1 if failed else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    sys.exit(main(sys.argv[1:]))
