# Astral Mod Forge

Astral Mod Forge 是面向 Unity IL2CPP / BepInEx MOD 的 Rider 辅助工具，用于创建独立 MOD 工程、分析源码依赖、生成可独立编译的 C# 工程并导出 DLL。

**当前版本：v0.5.0**

## 文档

- [v0.5.0 使用手册](docs/使用手册-v0.5.0.md)
- [v0.5.0 架构与限制](docs/架构与限制-v0.5.0.md)
- [静态方法裁剪示例：UniversalTargetMarksService](docs/静态方法裁剪示例-v0.5.0.md)
- [v0.5.0 Release 更新说明](.github/release-notes/v0.5.0.md)

## 构建

```bash
gradle coreChecks
gradle buildPlugin
gradle verifyPluginStructure
gradle standaloneCliJar
```

插件安装包由 GitHub Actions 使用真实 JetBrains Platform SDK 构建；不要使用模拟 IntelliJ API 的测试类生成可安装 JAR。发布附件与版本更新说明由 GitHub Release 工作流管理。
