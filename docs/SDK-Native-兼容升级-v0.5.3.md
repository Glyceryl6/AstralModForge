# Astral Mod Forge v0.5.3：SDK Native 支持

适用于 AstralParty.Modding.SDK v0.10.0 / AstralProbe v0.48.0 的目录迁移。导出器不复制 `Framework/Interop`、`Framework/UI` 中已经移动的旧源码，使用 `Dependencies/Projects/AstralParty.Modding.SDK` 下的托管和 Native 两个 SDK 工程副本。编译需匹配游戏版本的 `BepInEx/interop`。

构建/导出仍然需要 `dotnet restore` 和 `dotnet build` 成功。该更新不改变静态类选择和方法裁剪规则，也不代替真实游戏载入测试。
