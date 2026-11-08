#!/usr/bin/env python3
"""Self-contained offline regression test for verify-rider-linkage.py."""

import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

VERIFIER = Path(__file__).with_name('verify-rider-linkage.py')


def fixture(root: Path, kind: str, include_sdk: bool = False) -> Path:
    source = root / ('source-' + kind)
    output = root / ('classes-' + kind)
    package = source / 'dev/astral/modforge/ide'
    sdk = source / 'com/intellij/openapi/project'
    package.mkdir(parents=True, exist_ok=True)
    sdk.mkdir(parents=True, exist_ok=True)
    project_type = 'interface' if kind == 'interface' else 'class'
    content = ('public interface Project { String getBasePath(); }' if kind == 'interface'
               else 'public class Project { public String getBasePath() { return ""; } }')
    (sdk / 'Project.java').write_text(f'package com.intellij.openapi.project; {content}')
    for name in ('ExportModAction', 'NewModAction'):
        (package / (name + '.java')).write_text(
            'package dev.astral.modforge.ide; '
            'public class ' + name + ' { '
            'public String path(com.intellij.openapi.project.Project project) {'
            'return project.getBasePath(); } }'
        )
    subprocess.run(['javac', '--release', '21', '-d', str(output),
                    *map(str, source.rglob('*.java'))], check=True)
    distribution = root / (kind + ('-bundled' if include_sdk else '') + '.zip')
    jar_path = root / (kind + '.jar')
    with zipfile.ZipFile(jar_path, 'w') as jar:
        jar.writestr('META-INF/plugin.xml', '<idea-plugin/>')
        for file in output.rglob('*.class'):
            relative = file.relative_to(output).as_posix()
            if not include_sdk and relative.startswith('com/intellij/'):
                continue
            jar.write(file, relative)
    with zipfile.ZipFile(distribution, 'w') as archive:
        archive.write(jar_path, 'AstralModForge/lib/AstralModForge.jar')
    return distribution


def main() -> int:
    with tempfile.TemporaryDirectory(prefix='amf-linkage-tests-') as directory:
        root = Path(directory)
        for kind, bundle, should_pass in (
            ('interface', False, True),
            ('class', False, False),
            ('interface', True, False),
        ):
            distribution = fixture(root, kind, bundle)
            result = subprocess.run([sys.executable, str(VERIFIER), str(distribution)],
                                    text=True, capture_output=True)
            if (result.returncode == 0) != should_pass:
                raise AssertionError(f'{distribution}: {result.stdout}\n{result.stderr}')
            print(f'PASS: {distribution.name} {"accepted" if should_pass else "rejected"}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
