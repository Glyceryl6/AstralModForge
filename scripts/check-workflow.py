#!/usr/bin/env python3
"""Smoke check release workflow wiring without requiring third-party YAML packages.

This is a structural gate, NOT a substitute for an actual GitHub Actions run.
结构校验不能代替 GitHub 上的真实构建和权限验证。
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github' / 'workflows' / 'ci.yml'


def check() -> None:
    if not WORKFLOW.is_file():
        raise AssertionError('GitHub Actions workflow must live in .github/workflows/')

    content = WORKFLOW.read_text(encoding='utf-8')
    required = {
        'push:': 'push trigger',
        "branches: ['**']": 'all-branch CI trigger',
        'pull_request:': 'PR checks',
        'workflow_dispatch:': 'manual run option',
        'github.event.repository.default_branch': 'default branch release gate',
        'coreChecks buildPlugin verifyPluginStructure standaloneCliJar': 'official Gradle build',
        'verify-rider-linkage.py': 'Rider binary compatibility gate',
        'needs: build': 'only publish verified artifacts',
        'contents: write': 'release permission',
        'gh release create': 'GitHub Release creation',
        '--notes-file "$NOTES"': 'versioned Release notes',
        'sha256sum -c SHA256SUMS.txt': 'distribution integrity check',
    }
    missing = [name for token, name in required.items() if token not in content]
    if missing:
        raise AssertionError(f'Workflow steps missing: {missing}')
    if "github.ref == 'refs/heads/main'" in content:
        raise AssertionError('Release must not assume the default branch is main')
    if 'uses: actions/checkout@' not in content:
        raise AssertionError('Missing code checkout')
    print('PASS: GitHub Actions triggers, release permissions and asset gates')


if __name__ == '__main__':
    check()
