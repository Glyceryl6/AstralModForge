# Astral Mod Forge v0.5.1 Git 提交说明

本次只修复 Astral Mod Forge Java 插件工程，**不修改 AstralProbe MOD、SDK 或附属包版本**。

完整工程与增量补丁单独打包。四份补丁以 v0.5.0 为基线；不要在已经覆盖了 v0.5.1 完整源码的工程上重复应用。

## 0001 — `fix(project): 修复NuGet包源，补全游戏和SDK引用`

- **新增** `src/main/java/dev/astral/modforge/core/StandaloneBuildSupport.java`
- **修改** `src/main/java/dev/astral/modforge/core/ModTemplates.java`

生成独立的 `NuGet.Config` 和 `ForgePaths.props`，读取有效 NuGet 引用，跳过宿主专用构建包；补充 Unity IL2CPP 代理 DLL 引用，将相邻 SDK C# 项目作为相对路径副本加入导出工程。

## 0002 — `fix(build): 区分源码导出与DLL编译，失败时保留诊断`

- **修改** `src/main/java/dev/astral/modforge/core/ForgeExporter.java`

导出时写入新的包源与引用配置；先还原 NuGet 包，再执行 C# 编译。找不到 SDK、NuGet 失败、编译失败或未得到有效 DLL 时明确报错，不执行游戏目录部署，保留报告与日志。创建空项目时也包含相同配置。

## 0003 — `test(export): 补充依赖还原、编译失败和示例工程检查`

- **修改** `src/test/java/dev/astral/modforge/core/CoreChecks.java`
- **修改** `examples/dev_example_hello/.astral-mod-forge`
- **修改** `examples/dev_example_hello/Plugin.cs`
- **修改** `examples/dev_example_hello/README.md`
- **修改** `examples/dev_example_hello/dev_example_hello.csproj`
- **新增** `examples/dev_example_hello/NuGet.Config`
- **新增** `examples/dev_example_hello/ForgePaths.props`

新增包源、SDK 项目引用、游戏代理、分阶段构建、失败诊断等回归场景；更新空 MOD 示例。

## 0004 — `docs(release): 升级v0.5.1，补充独立工程编译说明`

- **修改** `README.md`
- **修改** `gradle.properties`
- **修改** `src/main/java/dev/astral/modforge/cli/Main.java`
- **修改** `src/main/resources/META-INF/plugin.xml`
- **新增** `.github/release-notes/v0.5.1.md`
- **新增** `docs/独立工程还原与编译-v0.5.1.md`
- **新增** `docs/Git提交说明-v0.5.1.md`

升级 Java 插件到 v0.5.1，更新 CLI 版本和说明，补充 Rider 重新索引、NuGet 包还原、互操作 DLL 路径和错误诊断指南。更新内容记录在 GitHub Release 的版本说明中，而不在 README 中堆积变更日志。

## 应用方法

将四份 `.patch` 解压在同一目录，在 Git 仓库的 **v0.5.0** 提交基础上顺序执行：

```bash
git am 0001-*.patch 0002-*.patch 0003-*.patch 0004-*.patch
```

补丁只包含这次更新；没有混入编译输出、游戏文件或 SDK DLL。若原仓库已含后续自行修改，先创建分支并备份工作目录，必要时使用 `git am --abort` 回退中途失败的补丁。

## 测试说明

- 核心 Java 离线检查：92 项通过。
- 使用 AstralProbe v0.47.0 源码生成的 `UniversalTargetMarksService` 工程，确认了独立 NuGet 配置与相对 SDK 引用。
- 使用模拟的 dotnet 命令验证包还原、编译、失败日志和无 DLL 不部署等流程。
- 此环境没有真实 .NET SDK、游戏互操作程序集与 Rider SDK，尚不能确认实际 DLL 编译、IDE 索引或游戏内加载成功。

构建正式 Rider 插件必须使用真实 JetBrains Rider SDK，在仓库中运行 GitHub Actions 或 Gradle `buildPlugin`；不要安装模拟 IDE API 打包的候选包。
