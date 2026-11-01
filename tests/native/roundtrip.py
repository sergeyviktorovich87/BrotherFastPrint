#!/usr/bin/env python3
"""Independent bounded HBP decoder for byte-for-byte raster regression checks."""
import pathlib
import re
import subprocess
import sys
import tempfile

def decode(data, width):
    cursor = 0
    while True:
        start = data.find(b'\x1b*b1030m', cursor)
        if start < 0:
            break
        cursor = start + len(b'\x1b*b1030m')
        reference = bytearray(width)
        output = bytearray()
        while True:
            if data[cursor:cursor+6] == b'1030M\x0c':
                cursor += 6
                break
            header = re.match(rb'(\d+)w', data[cursor:])
            assert header
            length = int(header[1])
            assert 2 <= length <= 16352
            cursor += header.end()
            block = data[cursor:cursor+length]
            assert len(block) == length
            cursor += length
            count = int.from_bytes(block[:2], 'big')
            assert 1 <= count <= 64
            p = 2
            def byte():
                nonlocal p
                assert p < len(block)
                value = block[p]; p += 1
                return value
            def overflow():
                value = 0
                while True:
                    n = byte(); value += n
                    if n != 255: return value
            for _ in range(count):
                edits = byte()
                line = bytearray(width) if edits == 255 else reference.copy()
                pos = 0
                if edits != 255:
                    for _ in range(edits):
                        command = byte()
                        if command & 128:
                            offset = (command >> 5) & 3
                            if offset == 3: offset += overflow()
                            size = command & 31
                            if size == 31: size += overflow()
                            size += 2
                            pos += offset
                            assert pos + size <= width
                            line[pos:pos+size] = bytes([byte()]) * size
                        else:
                            offset = (command >> 3) & 15
                            if offset == 15: offset += overflow()
                            size = command & 7
                            if size == 7: size += overflow()
                            size += 1
                            pos += offset
                            assert pos + size <= width
                            line[pos:pos+size] = bytes(byte() for _ in range(size))
                        pos += size
                output.extend(line)
                reference = line
            assert p == len(block), (p, len(block))
        yield bytes(output)

fixture = str(pathlib.Path(sys.argv[1]).resolve())
with tempfile.TemporaryDirectory() as directory:
    for pages, height, mode in [(1,6816,""), (10,6816,""), (50,6816,""), (4,6816,"duplex"), (4,6816,"duplex-blank")]:
        duplex = bool(mode)
        hbp = pathlib.Path(directory)/'job.hbp'
        mono = pathlib.Path(directory)/'expected.mono'
        subprocess.run([fixture,str(hbp),str(mono),str(pages),str(height)] + ([mode] if mode else []),check=True)
        data = hbp.read_bytes()
        # Compare a page at a time so this check does not retain 50 decoded A4 rasters.
        count = 0
        with mono.open('rb') as expected:
            for decoded in decode(data,604):
                assert len(decoded) == 604 * height
                assert decoded == expected.read(len(decoded)), count
                count += 1
            assert not expected.read(1)
        assert count == pages
        assert data.count(b'@PJL JOB NAME=') == 1
        assert data.count(b'@PJL EOJ NAME=') == 1
        assert (b"\x1b&l2S" in data) == duplex
        print(f'HBP roundtrip: duplex={duplex}, mode={mode or 'simplex'}, pages={pages}, rows={height}, bytes={len(data)}, exact raster match')
