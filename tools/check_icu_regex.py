#!/usr/bin/env python3
"""
Compiles every Regex literal in the Kotlin sources with ICU4C — the engine behind
java.util.regex on Android. The JVM accepts patterns that ICU rejects (e.g. a bare "}"),
and such a pattern only fails on the phone, so CI runs this check.

Usage: tools/check_icu_regex.py core/src/main app/src/main
"""
import ctypes
import glob
import pathlib
import re
import sys


def load_icu():
    for directory in ("/lib/x86_64-linux-gnu", "/usr/lib/x86_64-linux-gnu", "/usr/lib", "/lib"):
        for path in sorted(glob.glob(f"{directory}/libicui18n.so.*")):
            match = re.search(r"libicui18n\.so\.(\d+)$", path)
            if match:
                version = match.group(1)
                i18n = ctypes.CDLL(path)
                uc = ctypes.CDLL(path.replace("libicui18n", "libicuuc"))
                return i18n, uc, version
    sys.exit("ICU (libicui18n) not found")


i18n, uc, ver = load_icu()
uregex_open = getattr(i18n, f"uregex_open_{ver}")
uregex_open.restype = ctypes.c_void_p
uregex_open.argtypes = [ctypes.c_void_p, ctypes.c_int32, ctypes.c_uint32, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int32)]
uregex_close = getattr(i18n, f"uregex_close_{ver}")
uregex_close.argtypes = [ctypes.c_void_p]
error_name = getattr(uc, f"u_errorName_{ver}")
error_name.restype = ctypes.c_char_p


class UParseError(ctypes.Structure):
    _fields_ = [("line", ctypes.c_int32), ("offset", ctypes.c_int32),
                ("pre", ctypes.c_uint16 * 16), ("post", ctypes.c_uint16 * 16)]


def icu_error(pattern):
    data = pattern.encode("utf-16-le")
    buffer = ctypes.create_string_buffer(data)
    status = ctypes.c_int32(0)
    parse_error = UParseError()
    handle = uregex_open(buffer, len(data) // 2, 0, ctypes.byref(parse_error), ctypes.byref(status))
    if handle:
        uregex_close(handle)
    return None if status.value <= 0 else f"{error_name(status.value).decode()} at offset {parse_error.offset}"


def kotlin_string(raw):
    return raw.replace("\\\\", "\0").replace('\\"', '"').replace("\0", "\\")


TRIPLE = re.compile(r'Regex\(\s*"""(.*?)"""', re.S)
PLAIN = re.compile(r'Regex\(\s*"(?!"")((?:[^"\\]|\\.)*)"')
# Patterns assembled at runtime (AiIgnore globs via Regex.escape, Markdown inline parser pieces).
DYNAMIC = [r"[^/]*\Q.\Eenv", r"(?<code>`+)(?<codeBody>.+?)\k<code>"]

failures, total = [], 0
for root in sys.argv[1:]:
    for file in pathlib.Path(root).rglob("*.kt"):
        text = file.read_text(encoding="utf-8")
        patterns = [m.group(1) for m in TRIPLE.finditer(text)] + [kotlin_string(m.group(1)) for m in PLAIN.finditer(text)]
        for pattern in patterns:
            total += 1
            error = icu_error(pattern)
            if error:
                failures.append(f"{file}: {error}\n    {pattern}")
for pattern in DYNAMIC:
    total += 1
    error = icu_error(pattern)
    if error:
        failures.append(f"(runtime pattern): {error}\n    {pattern}")

print(f"ICU {ver}: {total} patterns checked, {len(failures)} rejected")
for failure in failures:
    print("FAIL", failure)
sys.exit(1 if failures else 0)
