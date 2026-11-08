#!/usr/bin/env python3
"""Validate that version, plugin.xml and release notes agree before release."""

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent


def check() -> str:
    properties = (ROOT / 'gradle.properties').read_text(encoding='utf-8')
    matches = re.findall(r'(?m)^modForgeVersion=([^\s]+)$', properties)
    if len(matches) != 1 or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', matches[0]):
        raise ValueError('gradle.properties 必须包含唯一的 modForgeVersion=X.Y.Z')

    version = matches[0]
    descriptor = ET.parse(ROOT / 'src/main/resources/META-INF/plugin.xml').getroot()
    if descriptor.findtext('version') != version:
        raise ValueError('plugin.xml 版本与 modForgeVersion 不一致')

    notes = ROOT / '.github/release-notes' / f'v{version}.md'
    if not notes.is_file() or len(notes.read_text(encoding='utf-8').strip()) < 200:
        raise ValueError(f'缺少对应版本的 Release 更新内容: {notes}')

    readme = (ROOT / 'README.md').read_text(encoding='utf-8')
    if f'v{version}' not in readme:
        raise ValueError('README 项目版本未更新')
    return f'v{version}'


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--print-tag', action='store_true', help='only print release tag')
    args = parser.parse_args()
    try:
        tag = check()
    except (ValueError, OSError, ET.ParseError) as exception:
        print(f'RELEASE METADATA FAIL: {exception}', file=sys.stderr)
        return 1
    print(tag if args.print_tag else f'RELEASE METADATA PASS: {tag}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
