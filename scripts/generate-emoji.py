#!/usr/bin/env python3
"""Build the offline picker from the vendored gemoji and Unicode sources."""
import hashlib
import json
import re
import unicodedata
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parent.parent
source = json.loads((root / 'third_party/gemoji/emoji.json').read_text())
rows = []
for entry in source:
    glyph = entry['emoji']
    rows.append((glyph, entry['description'], entry['category'], ' '.join(entry['aliases'] + entry['tags'])))
# Small, explicit symbol collection authored for Droidcast. Unicode glyphs only;
# Android supplies the font artwork. This is not the complete Unicode repertoire.
symbols = [
    ('→', 'right arrow', 'right next forward'), ('←', 'left arrow', 'left back previous'),
    ('↑', 'up arrow', 'up north'), ('↓', 'down arrow', 'down south'),
    ('↔', 'left right arrow', 'horizontal both'), ('⇒', 'right double arrow', 'implies implication'),
    ('⇐', 'left double arrow', 'implied'), ('⇔', 'equivalence', 'if and only if iff'),
    ('↵', 'return', 'enter keyboard'), ('⌘', 'command key', 'mac keyboard cmd'),
    ('⌥', 'option key', 'alt keyboard'), ('⌃', 'control key', 'ctrl keyboard'),
    ('⇧', 'shift key', 'keyboard'), ('⎋', 'escape key', 'esc keyboard'),
    ('⌫', 'backspace key', 'delete keyboard'), ('⇥', 'tab key', 'keyboard'),
    ('±', 'plus minus', 'math positive negative'), ('×', 'multiplication', 'math times multiply'),
    ('÷', 'division', 'math divide'), ('≠', 'not equal', 'math unequal'),
    ('≈', 'approximately equal', 'math almost'), ('≤', 'less than or equal', 'math'),
    ('≥', 'greater than or equal', 'math'), ('∞', 'infinity', 'math infinite'),
    ('√', 'square root', 'math radical'), ('∑', 'summation', 'math sigma sum'),
    ('∏', 'product', 'math pi'), ('∫', 'integral', 'math calculus'),
    ('∂', 'partial derivative', 'math calculus'), ('∆', 'increment', 'math delta change'),
    ('∈', 'element of', 'math set membership'), ('∉', 'not element of', 'math set'),
    ('∅', 'empty set', 'math null'), ('∩', 'intersection', 'math set'),
    ('∪', 'union', 'math set'), ('∀', 'for all', 'math universal'),
    ('∃', 'there exists', 'math existential'), ('π', 'pi', 'greek math circle'),
    ('α', 'alpha', 'greek'), ('β', 'beta', 'greek'), ('γ', 'gamma', 'greek'),
    ('δ', 'delta', 'greek'), ('ε', 'epsilon', 'greek'), ('θ', 'theta', 'greek'),
    ('λ', 'lambda', 'greek'), ('μ', 'mu', 'greek micro'), ('σ', 'sigma', 'greek'),
    ('φ', 'phi', 'greek'), ('ω', 'omega', 'greek'), ('Ω', 'capital omega', 'greek ohm resistance'),
    ('€', 'euro', 'currency money'), ('£', 'pound sterling', 'currency money gbp'),
    ('¥', 'yen', 'currency money jpy yuan'), ('₹', 'indian rupee', 'currency money inr'),
    ('₩', 'won', 'currency money krw'), ('₿', 'bitcoin', 'currency money btc'),
    ('°', 'degree', 'temperature angle'), ('℃', 'degrees celsius', 'temperature'),
    ('℉', 'degrees fahrenheit', 'temperature'), ('²', 'superscript two', 'squared power'),
    ('³', 'superscript three', 'cubed power'), ('½', 'one half', 'fraction'),
    ('¼', 'one quarter', 'fraction'), ('¾', 'three quarters', 'fraction'),
    ('•', 'bullet', 'list dot'), ('…', 'ellipsis', 'dots punctuation'),
    ('–', 'en dash', 'punctuation range'), ('—', 'em dash', 'punctuation'),
    ('§', 'section sign', 'paragraph legal'), ('†', 'dagger', 'footnote'),
]
rows.extend((glyph, name, 'Text symbols', keywords) for glyph, name, keywords in symbols)
# Append new entries so the relative order of existing favorites and shortcuts is stable.
unicode_root = root / 'third_party/unicode-emoji'
manifest = json.loads((unicode_root / 'sources.json').read_text(encoding='utf-8'))
with zipfile.ZipFile(unicode_root / 'sources.zip') as archive:
    if len(archive.namelist()) != len(manifest) or set(archive.namelist()) != set(manifest):
        raise ValueError('Unexpected Unicode source inventory')
    sources = {name: archive.read(name) for name in manifest}
for name, data in sources.items():
    if hashlib.sha256(data).hexdigest() != manifest[name]['sha256']:
        raise ValueError(f'Unicode source checksum mismatch: {name}')

assets = root / 'app/src/main/assets'
(assets / 'UNICODE-LICENSE').write_bytes(sources['LICENSE'])
category = subgroup = None
qualified = set()
for line in sources['emoji-test.txt'].decode('utf-8').splitlines():
    if line.startswith('# group: '):
        category = line.removeprefix('# group: ')
        subgroup = None
    if line.startswith('# subgroup: '):
        subgroup = line.removeprefix('# subgroup: ').replace('-', ' ')
    if not line or line.startswith('#'):
        continue
    values, description = line.split('#', 1)
    points, status = map(str.strip, values.split(';'))
    if status not in ('fully-qualified', 'component'):
        continue
    glyph = ''.join(chr(int(point, 16)) for point in points.split())
    match = re.fullmatch(r'(\S+) E[\d.]+ (.+)', description.strip())
    if match is None or match[1] != glyph or glyph in qualified or not category or not subgroup:
        raise ValueError(f'Invalid emoji data: {line}')
    qualified.add(glyph)
    rows.append((glyph, match[2], category, subgroup))

entries = {}
for glyph, name, category, keywords in rows:
    if glyph not in entries:
        identity = '-'.join(f'{ord(c):x}' for c in glyph)
        entries[glyph] = (identity, glyph, name, category, keywords)

def tsv(fields):
    if any(any(c in value for c in '\t\n\r') for value in fields):
        raise ValueError(f'Invalid TSV field: {fields!r}')
    return '\t'.join(fields)

header = '# Generated by scripts/generate-emoji.py; see third_party/{gemoji,unicode-emoji}/UPSTREAM.md'
(assets / 'emoji-catalog.tsv').write_text('\n'.join([header, *map(tsv, entries.values())]) + '\n', encoding='utf-8')
packs = assets / 'emoji-keywords'
packs.mkdir(exist_ok=True)
for locale in sorted(Path(name).stem for name in sources if name.startswith('annotations/')):
    annotations = {}
    for directory in ('annotationsDerived', 'annotations'):
        xml = ET.fromstring(sources[f'{directory}/{locale}.xml'])
        for item in xml.findall('./annotations/annotation'):
            if 'alt' in item.attrib:
                continue
            terms = [word.strip() for word in (item.text or '').split('|')]
            terms = [word for word in terms if word and word not in ('↑↑↑', '∅∅∅')]
            if terms:
                annotations.setdefault(item.attrib['cp'], {})[item.get('type', 'keywords')] = terms
    lines = []
    for glyph, (identity, *_) in entries.items():
        entry = annotations.get(glyph.replace('\ufe0f', ''), {})
        if entry:
            title = next(iter(entry.get('tts', [])), '')
            terms = [term for term in dict.fromkeys(entry.get('keywords', [])) if term != title]
            # Normalize searchable terms once on the build host. Preserve glyphs and IDs verbatim.
            folded = [unicodedata.normalize('NFC', word).lower().replace('_', ' ').replace('-', ' ').strip()
                      for word in [title, *terms]]
            lines.append(tsv([identity, *folded]))
    (packs / f'{locale}.tsv').write_text('\n'.join(lines) + '\n', encoding='utf-8')
print(f'{len(entries)} picker entries, {len(qualified)} Unicode sequences')
