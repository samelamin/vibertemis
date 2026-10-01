#!/usr/bin/env python3
"""Check the arm64 APK's ELF dependency closure before publishing it."""
import argparse
import collections
import hashlib
import json
import struct
import sys
import zipfile

# Public Android NDK libraries available at this app's minSdk (26).
SYSTEM_LIBS = frozenset(('libc.so libm.so libdl.so liblog.so libandroid.so '
    'libEGL.so libGLESv2.so libGLESv3.so libmediandk.so libaaudio.so '
    'libvulkan.so libz.so libOpenSLES.so libjnigraphics.so libnativewindow.so').split())
REQUIRED = frozenset(('libc++_shared.so libopenxr_loader.so '
    'libalvr_client_openxr.so libpyroclient.so libpyrowave-shared.so').split())


def parse_elf(data):
    """Read ELF64 program headers; section headers may have been stripped."""
    def region(offset, size):
        if offset < 0 or size < 0 or offset + size > len(data):
            raise ValueError('ELF region exceeds file bounds')
        return data[offset:offset + size]

    header = struct.unpack('<16sHHIQQQIHHHHHH', region(0, 64))
    ident, kind, machine, version = header[:4]
    if ident[:7] != b'\x7fELF\x02\x01\x01' or kind != 3 or machine != 183 or version != 1:
        raise ValueError('expected little-endian AArch64 ELF64 shared library')
    phoff, ehsize, phsize, phnum = header[5], header[8], header[9], header[10]
    if ehsize != 64 or phsize != 56 or not phnum:
        raise ValueError('invalid ELF program header table')
    region(phoff, phsize * phnum)
    loads, dynamics = [], []
    for i in range(phnum):
        p = struct.unpack('<IIQQQQQQ', region(phoff + i * phsize, 56))
        kind, offset, address, size = p[0], p[2], p[3], p[5]
        region(offset, size)
        if kind == 1:
            loads.append((offset, address, size))
        elif kind == 2:
            dynamics.append((offset, size))
    if len(dynamics) != 1 or dynamics[0][1] % 16:
        raise ValueError('missing or malformed PT_DYNAMIC')
    entries = collections.defaultdict(list)
    off, size = dynamics[0]
    terminated = False
    for i in range(0, size, 16):
        tag, value = struct.unpack('<qQ', region(off + i, 16))
        if tag == 0:
            terminated = True
            break
        entries[tag].append(value)
    if not terminated or len(entries[5]) != 1 or len(entries[10]) != 1:
        raise ValueError('dynamic table lacks terminator or unique string table')
    address, size = entries[5][0], entries[10][0]
    mappings = [offset + address - start for offset, start, length in loads
                if start <= address and address + size <= start + length]
    if not mappings:
        raise ValueError('dynamic string table is not file-backed')
    strings = region(mappings[0], size)

    def string(index):
        end = strings.find(b'\0', index) if index < len(strings) else -1
        if end < 0:
            raise ValueError('invalid dynamic string offset or terminator')
        name = strings[index:end].decode('ascii')
        if not name or '/' in name or '\\' in name:
            raise ValueError('invalid shared library name')
        return name

    if len(entries[14]) > 1:
        raise ValueError('multiple DT_SONAME entries')
    return {'soname': string(entries[14][0]) if entries[14] else None,
            'needed': [string(index) for index in entries[1]]}


def verify_apk(path, runtime_sha256=None):
    errors, libraries = [], {}
    try:
        with zipfile.ZipFile(path) as apk:
            names = [i.filename for i in apk.infolist()]
            for name, count in collections.Counter(names).items():
                if name.startswith('lib/') and count > 1:
                    errors.append(f'duplicate native ZIP entry: {name}')
            for info in apk.infolist():
                name = info.filename
                if not name.startswith('lib/') or info.is_dir():
                    continue
                parts = name.split('/')
                if len(parts) != 3 or parts[1] != 'arm64-v8a' or not parts[2].endswith('.so'):
                    errors.append(f'unexpected native ABI or entry: {name}')
                    continue
                if info.file_size > 200 * 1024 * 1024:
                    errors.append(f'native library exceeds size limit: {name}')
                    continue
                try:
                    data = apk.read(info)
                    elf = parse_elf(data)
                    filename = parts[2]
                    if elf['soname'] not in (None, filename):
                        raise ValueError(f'SONAME {elf["soname"]} differs from packaged filename')
                    libraries[filename] = elf
                    if filename == 'libc++_shared.so' and runtime_sha256:
                        if hashlib.sha256(data).hexdigest() != runtime_sha256:
                            raise ValueError('packaged C++ runtime differs from staged pinned NDK runtime')
                except (ValueError, struct.error, UnicodeError, zipfile.BadZipFile) as exc:
                    errors.append(f'{name}: {exc}')
    except (OSError, zipfile.BadZipFile) as exc:
        errors.append(f'cannot read APK: {exc}')
    for missing in sorted(REQUIRED - libraries.keys()):
        errors.append(f'required library missing: {missing}')
    for filename, elf in libraries.items():
        for needed in elf['needed']:
            if needed not in libraries and needed not in SYSTEM_LIBS:
                errors.append(f'{filename}: missing dependency {needed}')
    return {'ok': not errors, 'errors': errors, 'libraries': libraries}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    parser.add_argument('--runtime-sha256', help='SHA-256 of the staged pinned NDK runtime')
    args = parser.parse_args()
    if args.runtime_sha256 and (len(args.runtime_sha256) != 64 or
            any(c not in '0123456789abcdef' for c in args.runtime_sha256)):
        parser.error('--runtime-sha256 must be a lowercase SHA-256 digest')
    result = verify_apk(args.apk, args.runtime_sha256)
    print(json.dumps(result, indent=2))
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    sys.exit(main())
