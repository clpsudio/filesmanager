#!/usr/bin/env python3
"""Checks that R8 kept what Files needs at run time, in a release APK.

    scripts/ci/check-apk-r8.py <app-release.apk> --mapping <mapping.txt> [--dexdump <path>] [--readelf <path>]

1. Classes kept under their original names by the keep rules (app/proguard-rules.pro and the
   decoder module's consumer rules): the FFmpeg decoder classes, XZ, Zip4j, PdfBox-Android.
2. JNI: every Java_* function exported by lib/<abi>/libffmpegJNI.so (each ABI) has a native method of
   that name in that class in the dex, and the method the native code calls back
   (FfmpegAudioDecoder.growOutputBuffer) is there with its exact signature.
3. App and library classes R8 may rename, merge or inline (archives, PDF, player): either the class
   is in the mapping and its new name is in the dex, or its methods were moved into another class
   (mapping lines naming "<class>.<method>") that is in the dex.
4. Framework classes the app's PDF and player code calls (PdfRenderer, Media3) are referenced by the dex.
5. Libraries that must NOT ship (Junrar / UnRAR, not an open-source licence): no class or type of theirs in the dex.
Exits 1 and prints every miss.

Copyright 2026 CLP Studio. Licensed under the Apache License, Version 2.0 (licence decision pending).
"""
import argparse, re, struct, subprocess, sys, tempfile, zipfile
from pathlib import Path

KEPT = [  # original names, kept by keep rules
    "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer",
    "androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder",
    "androidx.media3.decoder.ffmpeg.FfmpegLibrary",
    "androidx.media3.decoder.SimpleDecoderOutputBuffer",
    "org.tukaani.xz.LZMA2InputStream",
    "net.lingala.zip4j.ZipFile",
    "com.tom_roush.pdfbox.pdmodel.PDDocument",
    "com.tom_roush.pdfbox.text.PDFTextStripper",
    "com.clpstudio.filesmanager.playback.PlaybackService",  # manifest
]
APP = [  # may be renamed; must survive (mapping + dex)
    "com.clpstudio.filesmanager.data.Archives",
    "com.clpstudio.filesmanager.data.ArchiveOps",
    "com.clpstudio.filesmanager.viewer.ZipViewerScreenKt",
    "com.clpstudio.filesmanager.viewer.PdfDocument",
    "com.clpstudio.filesmanager.viewer.PdfViewerScreenKt",
    "com.clpstudio.filesmanager.thumbs.PdfThumbFetcher",
    "com.clpstudio.filesmanager.data.PdfSecurity",
    "org.apache.commons.compress.archivers.sevenz.SevenZFile",
    "androidx.media3.session.MediaSessionService",
    "androidx.media3.exoplayer.ExoPlayerImpl",
]
REFERENCED = [  # framework / library types the code must still reference
    "android.graphics.pdf.PdfRenderer",
    "android.graphics.pdf.PdfRenderer$Page",
    "androidx.media3.exoplayer.ExoPlayer",
]
FORBIDDEN = [  # package prefixes (as dex descriptors) and words that must not appear in any dex type
    "Lcom/github/junrar/",
    "junrar",
    "unrar",
]
CALLBACK = ("androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder", "growOutputBuffer",
            "(Landroidx/media3/decoder/SimpleDecoderOutputBuffer;I)Ljava/nio/ByteBuffer;")


def desc(cls):
    return "L" + cls.replace(".", "/") + ";"


def uleb(b, i):
    r = s = 0
    while True:
        x = b[i]; i += 1
        r |= (x & 0x7F) << s; s += 7
        if x < 0x80:
            return r, i


def dex_types(data):
    """All type descriptors (type_ids) of one dex file."""
    n_str, o_str, n_typ, o_typ = struct.unpack_from("<IIII", data, 0x38)
    def string(k):
        off = struct.unpack_from("<I", data, o_str + 4 * k)[0]
        _, i = uleb(data, off)
        j = data.index(0, i)
        return data[i:j].decode("utf-8", "replace")
    return {string(struct.unpack_from("<I", data, o_typ + 4 * t)[0]) for t in range(n_typ)}


def dexdump_classes(dexdump, path):
    """class descriptor -> list of (method name, type, access string)."""
    out = subprocess.run([dexdump, "-l", "plain", str(path)], capture_output=True, check=True).stdout.decode("utf-8", "replace")
    classes, cur, name, typ = {}, None, None, None
    for line in out.splitlines():
        m = re.match(r"\s+Class descriptor\s+:\s+'(.+)'", line)
        if m:
            cur = m.group(1); classes[cur] = []; continue
        m = re.match(r"\s+name\s+:\s+'(.+)'", line)
        if m: name = m.group(1); continue
        m = re.match(r"\s+type\s+:\s+'(.+)'", line)
        if m: typ = m.group(1); continue
        m = re.match(r"\s+access\s+:\s+0x[0-9a-f]+ \((.*)\)", line)
        if m and cur and name:
            classes[cur].append((name, typ, m.group(1))); name = None
    return classes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk"); ap.add_argument("--mapping", required=True)
    ap.add_argument("--dexdump", default="dexdump"); ap.add_argument("--readelf", default="readelf")
    a = ap.parse_args()
    miss, ok = [], []
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(a.apk) as z:
        dexes = sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        types, classes = set(), {}
        for n in dexes:
            p = Path(tmp, n); p.write_bytes(z.read(n))
            types |= dex_types(p.read_bytes())
            classes.update(dexdump_classes(a.dexdump, p))
        for c in KEPT:
            (ok if desc(c) in classes else miss).append(f"kept class {c}")
        # JNI exports of each ABI's libffmpegJNI.so must match native methods in the dex.
        for so in sorted(n for n in z.namelist() if n.endswith("/libffmpegJNI.so")):
            p = Path(tmp, so.replace("/", "_")); p.write_bytes(z.read(so))
            syms = subprocess.run([a.readelf, "-W", "--dyn-syms", str(p)], capture_output=True, text=True, check=True).stdout
            jni = sorted(set(re.findall(r"\bJava_(\w+)", syms)))
            if not jni:
                miss.append(f"{so}: no JNI exports found")
            for s in jni:
                cls_part, meth = s.rsplit("_", 1)
                cls = "L" + cls_part.replace("_", "/") + ";"
                natives = [m for m in classes.get(cls, []) if m[0] == meth and "NATIVE" in m[2]]
                (ok if natives else miss).append(f"{so}: Java_{s} -> native {cls}.{meth}")
            (ok if "JNI_OnLoad" in syms else miss).append(f"{so}: JNI_OnLoad exported")
        c, m, t = CALLBACK
        has_cb = any(x[0] == m and x[1] == t for x in classes.get(desc(c), []))
        (ok if has_cb else miss).append(f"callback {c}.{m}{t}")
        mapping, moved, current = {}, {}, None
        for line in Path(a.mapping).read_text(encoding="utf-8").splitlines():
            mm = re.match(r"^(\S+) -> (\S+):$", line)
            if mm:
                mapping[mm.group(1)] = mm.group(2); current = mm.group(2); continue
            for cl in APP:  # a member of cl that now lives in another class
                if current and (" " + cl + ".") in line:
                    moved.setdefault(cl, set()).add(current)
        for cl in APP:
            new = mapping.get(cl)
            if new is not None:
                (ok if desc(new) in classes else miss).append(f"class {cl} -> {new}")
            elif moved.get(cl):
                hosts = sorted(h for h in moved[cl] if desc(h) in classes)
                (ok if hosts else miss).append(f"class {cl}: code merged/inlined into {len(hosts)} kept class(es), e.g. {', '.join(hosts[:3])}")
            else:
                miss.append(f"class {cl}: not in mapping and no member kept (removed by R8)")
        for cl in REFERENCED:
            (ok if desc(cl) in types else miss).append(f"referenced {cl}")
        for word in FORBIDDEN:
            hits = sorted(t for t in types | set(classes) if word in t or word in t.lower())
            (miss.append(f"forbidden {word!r} in dex: {', '.join(hits[:5])}") if hits else ok.append(f"no {word!r} in dex"))
    for line in ok:
        print("ok   " + line)
    for line in miss:
        print("MISS " + line)
    print(f"{len(ok)} ok, {len(miss)} missing")
    sys.exit(1 if miss else 0)


if __name__ == "__main__":
    main()
