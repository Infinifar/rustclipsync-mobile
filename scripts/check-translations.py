#!/usr/bin/env python3
"""Validate translation keys and format arguments without an Android runtime."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / 'android/app/src/main'
def strings(path):
    nodes = ET.parse(path).getroot().findall('string')
    result = {node.attrib['name']: node.text or '' for node in nodes}
    assert len(result) == len(nodes), f'duplicate key: {path}'
    return result

baseline = strings(root / 'res/values/strings.xml')
for path in (root / 'res').glob('values-*/strings.xml'):
    translated = strings(path)
    assert translated.keys() == baseline.keys(), f'translation keys differ: {path}'
    for key, value in baseline.items():
        assert sorted(re.findall(r'%\d+\$[sd]', value)) == sorted(re.findall(r'%\d+\$[sd]', translated[key])), (path, key)
references = set()
for path in (root / 'java').rglob('*.kt'):
    references.update(re.findall(r'R\.string\.(\w+)', path.read_text()))
assert not references - baseline.keys(), f'missing strings: {references - baseline.keys()}'
print(f'Translation keys and placeholders verified: {len(baseline)}')
