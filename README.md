# Astral Mod Forge

Astral Mod Forge 是面向 Unity IL2CPP / BepInEx MOD 的 Rider 辅助工具，用于创建独立 MOD 工程、分析源码依赖、生成可独立编译的 C# 工程并导出 DLL。

**当前版本：v0.5.3**

## 下载与发布

- 插件正式 ZIP、CLI JAR 和校验文件：请从 GitHub 仓库的 **Releases** 页面获取。
- 仓库默认分支的新版提交通过真实 Rider SDK 构建后，GitHub Actions 才会创建新 Release。
- [自动构建与 Release 排查](docs/导出优化与自动发布-v0.5.2.md)

## 文档

- [v0.5.3 SDK Native 兼容迁移说明](docs/SDK-Native-兼容升级-v0.5.3.md)
- [v0.5.3 Release 更新说明](.github/release-notes/v0.5.3.md)

- [v0.5.2 独立工程还原与编译排查](docs/独立工程还原与编译-v0.5.2.md)
- [v0.5.2 Release 更新说明](.github/release-notes/v0.5.2.md)

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
