#!/usr/bin/env python3
"""Inspect and operate actual SystemUI notification buttons, using Android CLI layout data."""
import argparse
import json
from pathlib import Path
import subprocess
import time


def walk(node, parents=()):
    if isinstance(node, dict):
        yield node, parents
        for value in node.get('children', []):
            yield from walk(value, (node, *parents))
    elif isinstance(node, list):
        for value in node:
            yield from walk(value, parents)


def nodes(document):
    for window in document.get('windows', []):
        if window.get('type') == 'SYSTEM':
            for entry, parents in walk(window.get('content', [])):
                if not entry.get('hidden', False):
                    yield entry, parents


def center(node):
    value = node['center']
    return json.loads(value) if isinstance(value, str) else value


def layout(android, serial, output, environment=None):
    result = subprocess.run([*android, 'layout', '--full', '--device', serial], env=environment,
                            capture_output=True, text=True, check=True, timeout=40)
    document = json.loads(result.stdout)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n')
    return list(nodes(document))


def button(entries, title):
    for node, parents in entries:
        if node.get('text') == title:
            for candidate in (node, *parents):
                if 'CLICKABLE' in candidate.get('interactions', []) and candidate.get('center'):
                    return candidate
    return None


def prepare(android, adb, serial, output, environment=None):
    subprocess.run([adb, '-s', serial, 'shell', 'cmd', 'statusbar', 'expand-notifications'],
                   env=environment, check=True, timeout=15)
    time.sleep(.4)
    for _ in range(8):
        entries = layout(android, serial, output, environment)
        if button(entries, '复制') and (button(entries, '显示') or button(entries, '隐藏')):
            return entries
        titles = [node for node, _ in entries if node.get('text') == '加密收件箱']
        expanders = [node for node, _ in entries
                     if 'expand' in node.get('resource-id', '').lower()
                     and 'CLICKABLE' in node.get('interactions', []) and node.get('center')]
        if titles and expanders:
            # Choose the expander beside the observed inbox title, not another app's row.
            title_y = center(titles[0])[1]
            target = min(expanders, key=lambda node: abs(center(node)[1] - title_y))
            if abs(center(target)[1] - title_y) > 180:
                raise RuntimeError('no inbox expander near the observed title; inspect saved layout')
            subprocess.run([adb, '-s', serial, 'shell', 'input', 'tap', *map(str, center(target))],
                           env=environment, check=True, timeout=15)
        time.sleep(.3)
    raise RuntimeError('SystemUI did not expose the inbox text actions; inspect saved layout')


def click(android, adb, serial, output, title, environment=None):
    entries = prepare(android, adb, serial, output, environment)
    target = button(entries, title)
    if target is None:
        raise RuntimeError('missing SystemUI action: ' + title)
    subprocess.run([adb, '-s', serial, 'shell', 'input', 'tap', *map(str, center(target))],
                   env=environment, check=True, timeout=15)
    print('SystemUI clicked:', title, 'at', center(target), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-cli', required=True)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--device', default='emulator-5554')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--click', choices=['显示', '隐藏', '复制'])
    args = parser.parse_args()
    android = [args.android_cli, '--no-metrics']
    if args.click:
        click(android, args.adb, args.device, args.output, args.click)
    else:
        entries = prepare(android, args.adb, args.device, args.output)
        print('Observed SystemUI buttons:', [title for title in ['显示', '隐藏', '复制'] if button(entries, title)])
