#!/usr/bin/env python3
"""拒绝使用模拟 IntelliJ API 编译的 Rider 安装包。

Reject a Rider plugin distribution if its Project interface calls were compiled
against a stub class or if IntelliJ API classes were bundled into the plugin.
Run only *after* a real Gradle buildPlugin; this is not a substitute for Plugin Verifier.
"""

import argparse
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


PROJECT_METHOD = re.compile(
    r'^\s*\d+:\s+(?P<opcode>invoke\w+)\s+.*//\s+'
    r'(?P<kind>InterfaceMethod|Method)\s+'
    r'com/intellij/openapi/project/Project\.(?P<method>[\w$]+):',
    re.MULTILINE,
)
EXPECTED_ACTIONS = (
    'dev.astral.modforge.ide.ExportModAction',
    'dev.astral.modforge.ide.NewModAction',
)


def check(distribution: Path) -> None:
    if not distribution.is_file():
        raise ValueError(f'安装包不存在 / Distribution not found: {distribution}')

    with zipfile.ZipFile(distribution) as archive:
        plugin_jars = [entry for entry in archive.namelist()
                       if re.fullmatch(r'[^/]+/lib/[^/]+\.jar', entry)]
        if len(plugin_jars) != 1:
            raise ValueError(f'预期恰好一个插件主 JAR / Expected one plugin JAR: {plugin_jars}')
        jar_bytes = archive.read(plugin_jars[0])

    with tempfile.TemporaryDirectory(prefix='amf-linkage-') as folder:
        jar_path = Path(folder) / 'plugin.jar'
        jar_path.write_bytes(jar_bytes)

        with zipfile.ZipFile(jar_path) as plugin:
            names = plugin.namelist()
            forbidden = [name for name in names if name.endswith('.class') and
                         (name.startswith('com/intellij/') or
                          name.startswith('org/jetbrains/annotations/'))]
            if forbidden:
                raise ValueError(f'禁止打包 IntelliJ SDK 替身 / Bundled IDE API: {forbidden[:5]}')
            if 'META-INF/plugin.xml' not in names:
                raise ValueError('缺少插件描述 / Missing META-INF/plugin.xml')
            classes = sorted({name[:-6].replace('/', '.') for name in names
                              if name.startswith('dev/astral/modforge/ide/') and
                              name.endswith('.class')})

        missing = sorted(set(EXPECTED_ACTIONS) - set(classes))
        if missing:
            raise ValueError(f'缺少动作类 / Missing action classes: {missing}')

        result = subprocess.run(
            ['javap', '-c', '-p', '-classpath', str(jar_path), *classes],
            text=True, capture_output=True, check=False,
        )
        if result.returncode:
            raise ValueError(f'javap 检查失败 / javap failed: {result.stderr}')

        calls = list(PROJECT_METHOD.finditer(result.stdout))
        for match in calls:
            if match['opcode'] != 'invokeinterface' or match['kind'] != 'InterfaceMethod':
                raise ValueError(
                    'Project 接口调用使用了错误的字节码：'
                    f'{match.group(0).strip()}\n'
                    '必须使用真实 Rider SDK 重新执行 gradle buildPlugin；'
                    '不要发布模拟 API 构建出的预览 JAR。'
                )
        base_calls = [call for call in calls if call['method'] == 'getBasePath']
        if len(base_calls) < 2:
            raise ValueError(
                '找不到预期的 Project.getBasePath() 接口调用；'
                '请检查导出／新建动作或重新审核本脚本。'
            )
        print(f'PASS: {distribution.name}，{len(classes)} IDE 类，'
              f'{len(calls)} 次 Project 接口调用，均采用 invokeinterface。')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('distribution', type=Path,
                        help='由真实 Gradle buildPlugin 输出的 ZIP 安装包')
    args = parser.parse_args()
    try:
        check(args.distribution)
        return 0
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f'FAIL: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
