"""Offline verification against the phone's Phigros 4.0.1.2 arm64 library.

Usage: python docs/version-4.0.1.2/verify_native.py /path/to/libil2cpp.so
Requires capstone. This script is not packaged or executed in the module.
"""

import hashlib
from pathlib import Path
import re
import struct
import sys

import capstone

SHA256 = 'c5f6d6b72658ab6d5f03d3d11a63b47778c54d4b93871422218d6a75f7b4a10f'
ORIGINALS = {
    'ClickControl.TimeGuard': (0x1D848EC, '1f1d00726882013920070054'),
    'ClickControl.NoFabs': (0x1D848FC, '28c1201e'),
    'ClickControl.LoadZero': (0x1D84914, '000540bd'),
    'ClickControl.PassThrough': (0x1D8491C, 'e5090054'),
    'DragControl.LoadZero': (0x1D850F8, '002945bd'),
    'DragControl.PassThrough': (0x1D8510C, 'e8050034'),
    'FlickControl.LoadZero': (0x1D85670, '422945bd'),
    'FlickControl.PassThrough': (0x1D8568C, 'a0070054'),
    'HoldControl.HeadPassThrough': (0x1D8633C, 'a0030054'),
    'HoldControl.NoFabs': (0x1D86344, '09c1201e'),
    'HoldControl.LoadZero': (0x1D8635C, '000540bd'),
    'HoldControl.HeadRedirectWait': (0x1D86364, '85050054'),
    'HoldControl.PreventBroken': (0x1D865E8, '69360239'),
    'HoldControl.BodySkipToTail': (0x1D865EC, '081000b4'),
    'SongSelectorItem.SetZeroPrice': (0x1DB0F10, '0011891a'),
    'MoneyControl.SpendMoney': (0x1CAC2F4, 'fe57bea9f44f01a9'),
    'ProgressControl.BypassAudioWait': (0x1D88880, 'ad010054'),
    'ProgressControl.BypassCanLevelOver': (0x1D88890, '28010034'),
    'ProgressControl.SetOverTimeDirectly': (0x1D888D4, '0029201e'),
    'ScoreControl.InstantPhiResult': (0x1D88B78,
        '604240bde00314aae1031faae5e0fc97604640bde00314aae1031faafbe0fc97'
        '685640b9883600b96082c53c80c2813c603640fd80c202fc'),
}

data = Path(sys.argv[1]).read_bytes()
assert hashlib.sha256(data).hexdigest() == SHA256, 'Wrong library build'
assert data[:6] == b'\x7fELF\x02\x01'
assert struct.unpack_from('<H', data, 18)[0] == 183
phoff = struct.unpack_from('<Q', data, 32)[0]
phsize, phcount = struct.unpack_from('<HH', data, 54)
segments = [struct.unpack_from('<IIQQQQQQ', data, phoff + i * phsize)
            for i in range(phcount)]

def read(address, size):
    segment = next(s for s in segments
                   if s[0] == 1 and s[1] & 1
                   and s[3] <= address and address + size <= s[3] + s[5])
    offset = segment[2] + address - segment[3]
    return data[offset:offset + size]

source = (Path(__file__).resolve().parents[2]
          / 'app/src/main/java/io/github/meiyongai/phi/patcher/MemoryPatcher.kt')
text = re.sub(r'//[^\n]*', '', source.read_text('utf-8'))
entries = {}
for match in re.finditer(
        r'PatchEntry\(\s*"([^"]+)",\s*0x([0-9A-F]+)L,\s*arm64\((.*?)\)\s*\)',
        text, re.S):
    code = b''.join(struct.pack('<I', int(word, 16))
                    for word in re.findall(r'0x([0-9A-F]+)', match[3]))
    entries[match[1]] = (int(match[2], 16), code)
for match in re.finditer(
        r'PatchEntry\(\s*name = "([^"]+)",\s*rva = 0x([0-9A-F]+)L,'
        r'\s*patchBytes = byteArrayOf\((.*?)\n\s*\)', text, re.S):
    entries[match[1]] = (int(match[2], 16),
                         bytes(int(x, 16) for x in re.findall(r'0x([0-9A-F]+)', match[3])))
assert entries.keys() == ORIGINALS.keys(), 'Incomplete or unexpected modification table'

decoder = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
decoded = {}
lines = [f'Phigros 4.0.1.2 / versionCode 160 / arm64-v8a', f'SHA256: {SHA256}']
for name, (address, code) in sorted(entries.items(), key=lambda item: item[1][0]):
    expected_address, original = ORIGINALS[name]
    assert address == expected_address, name
    assert len(code) == len(bytes.fromhex(original)), name
    assert read(address, len(code)).hex() == original, name
    instructions = list(decoder.disasm(code, address))
    assert len(instructions) * 4 == len(code), name
    decoded[name] = [(i.mnemonic, i.op_str) for i in instructions]
    lines.append(f'\n{name} @ {address:#x}; original={original}')
    lines.extend(f'  {i.address:08x}: {i.mnemonic} {i.op_str}'.rstrip() for i in instructions)

# Verify control flow and the actual byte field written, not just instruction bytes.
assert decoded['ClickControl.TimeGuard'][-1] == ('b.gt', '#0x1d849d8')
assert decoded['HoldControl.HeadRedirectWait'] == [('b.pl', '#0x1d865c8')]
assert decoded['HoldControl.BodySkipToTail'] == [('b', '#0x1d866a4')]
assert decoded['HoldControl.PreventBroken'] == [('strb', 'wzr, [x19, #0x8d]')]
assert decoded['SongSelectorItem.SetZeroPrice'] == [('mov', 'w0, wzr')]
assert decoded['MoneyControl.SpendMoney'] == [('mov', 'w0, #1'), ('ret', '')]
assert decoded['ProgressControl.SetOverTimeDirectly'] == [('fmov', 's0, #3.50000000')]
assert decoded['ScoreControl.InstantPhiResult'][:2] == [
    ('ldr', 'x8, [x19, #0x30]'), ('ldr', 'w8, [x8, #0x2c]')]
assert decoded['ScoreControl.InstantPhiResult'][6:] == [
    ('stp', 'w10, w9, [x20, #0x10]'), ('mov', 'w9, #0x3f800000'),
    ('stp', 'w9, w8, [x20, #0x18]'), ('str', 'xzr, [x20, #0x20]'),
    ('str', 'xzr, [x20, #0x28]'), ('stp', 'wzr, w8, [x20, #0x30]'),
    ('nop', ''), ('nop', '')]

lines.append(f'\nPASS: {len(entries)} modification sites; branch targets and field operands verified.')
report = Path(__file__).with_name('native-verification.txt')
report.write_text('\n'.join(lines) + '\n', 'utf-8')
print(lines[-1].strip())
print(report)
