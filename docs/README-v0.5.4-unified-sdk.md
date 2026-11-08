# Astral Mod Forge v0.5.4 — 单 DLL SDK 兼容

SDK v0.11.0 将 `Native/` 源文件编入主 SDK 项目；导出工程复制完整 SDK 根项目，并将 `ForgeInteropDir` 传给根 SDK 的 `ProjectReference`。

- 导出时只需 `AstralParty.Modding.SDK.dll`。
- 旧 SDK v0.10.x 的双项目引用仍可处理，避免已有导出配置立即失效。
- 请在 Rider 中检查 `ForgePaths.props` 的游戏代理程序集目录；该路径必须指向与游戏匹配的 `BepInEx/interop`。

Java 离线验证：`./scripts/offline-core-checks.sh`。正式 Rider 插件安装包仍由真实 JetBrains SDK 构建。
