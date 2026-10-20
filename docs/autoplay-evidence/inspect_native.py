"""Read-only evidence extraction for the autoplay analysis. Requires capstone."""
import hashlib
import json
from pathlib import Path
import struct
import capstone

ROOT = Path(__file__).resolve().parents[3]
path = ROOT / 'analysis/lib/arm64-v8a/libil2cpp.so'
data = path.read_bytes()
symbols = json.loads((ROOT / 'analysis/dump/script.json').read_text(encoding='utf-8'))
names = {item['Address']: item['Name'] for item in symbols['ScriptMethod']}
phoff = struct.unpack_from('<Q', data, 32)[0]
size, count = struct.unpack_from('<HH', data, 54)
segments = [struct.unpack_from('<IIQQQQQQ', data, phoff + i*size) for i in range(count)]
def read(va, length):
    segment = next(s for s in segments if s[0] == 1 and s[3] <= va < s[3]+s[5])
    off = segment[2] + va - segment[3]
    return data[off:off+length]

md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
lines = [f'Sample: {path.name}', f'SHA256: {hashlib.sha256(data).hexdigest()}',
         f'ELF class={data[4]} endian={data[5]} machine={struct.unpack_from("<H", data, 18)[0]}',
         f'Capstone: {capstone.__version__}']
for start, end in [
    (0x1D44CD8, 0x1D45060), (0x1D45C10, 0x1D45C50),
    (0x1D7CB1C, 0x1D7CDFC), (0x1D7DEC0, 0x1D7DF7C),
    (0x1D821B0, 0x1D82310), (0x1D829E8, 0x1D82AF8),
    (0x1D82F40, 0x1D83100), (0x1D83BF8, 0x1D83D08),
    (0x1D83F98, 0x1D840E8), (0x1D874E4, 0x1D876B8),
    (0x1D8789C, 0x1D87980),
]:
    lines.append(f'\nRVA {start:#x}: {names.get(start, "function fragment")}')
    for inst in md.disasm(read(start, end-start), start):
        annotation = ''
        if inst.mnemonic in ('bl', 'b') and inst.op_str.startswith('#'):
            annotation = names.get(int(inst.op_str[1:], 16), '')
        lines.append(f'{inst.address:08X}  {inst.mnemonic:8} {inst.op_str:32} {annotation}')
lines.append('\nNoteUpdateManager.Update calls to Judge:')
for inst in md.disasm(read(0x1D84224, 0xC30), 0x1D84224):
    if inst.mnemonic == 'bl':
        name = names.get(int(inst.op_str[1:], 16), '')
        if 'Judge' in name:
            lines.append(f'{inst.address:08X} {name}')
lines.append('\nSelected float constants (raw bytes / value):')
for va in (0xC344B0, 0xC347DC, 0xC34854):
    b = read(va, 4)
    lines.append(f'{va:08X} {b.hex()} {struct.unpack("<f", b)[0]}')
# ELF dynamic dependencies from SHT_DYNAMIC and its linked string table.
shoff = struct.unpack_from('<Q', data, 40)[0]
ssize, snum = struct.unpack_from('<HH', data, 58)
sections = [struct.unpack_from('<IIQQQQIIQQ', data, shoff+i*ssize) for i in range(snum)]
lines.append('\nDT_NEEDED:')
for section in sections:
    if section[1] != 6:
        continue
    strings = sections[section[6]]
    for off in range(section[4], section[4]+section[5], 16):
        tag, value = struct.unpack_from('<qQ', data, off)
        if tag == 1:
            start = strings[4] + value
            lines.append(data[start:data.index(b'\x00', start)].decode())
lines.append('\nRelocation-resolved metadata references:')
refs = {0x4186990, 0x4186910, 0x417FD60, 0x4184FA8, 0x417E3F0}
metadata = {item['Address']: item.get('Value', item.get('Name'))
            for key in ('ScriptString', 'ScriptMetadata') for item in symbols[key]}
for section in sections:
    if section[1] != 4:
        continue
    for off in range(section[4], section[4]+section[5], 24):
        address, info, addend = struct.unpack_from('<QQq', data, off)
        if address in refs:
            lines.append(f'{address:08X} -> {addend:08X}: {metadata.get(addend)}')
output = Path(__file__).with_name('native-evidence.txt')
output.write_text('\n'.join(lines)+'\n', encoding='utf-8')
print(output)
