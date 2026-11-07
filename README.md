# 星航 MOD 工坊 / Astral Mod Forge · v0.3.0

**独立的 Java / JetBrains Rider 开发工具，不属于 AstralProbe MOD 本体的版本号。**

从 Unity IL2CPP / HybridCLR + BepInEx 工程中勾选 C# 功能类，生成独立 MOD 的 `Plugin.cs`、依赖工程及本地化资源，并尝试编译、导出或部署 DLL；也支持只输入 MOD ID 创建空工程。

> **验证状态**：Java 21 离线核心测试 69 项通过，并用 AstralProbe v0.47.0 的 `AutoPraiseService` 做了独立工程导出检查。当前运行环境没有真实 JetBrains Rider SDK 和 .NET 构建工具，因此**不能宣称已经完成 Rider 插件安装验证或游戏 DLL 编译／加载验证**。建议在本机使用 `gradle buildPlugin` 生成正式插件安装 ZIP。

## v0.3.0 的更新

- **修复打开工具窗口时的空指针异常**：`BuildLogService` 在 `plugin.xml` 显式注册为项目服务。若旧 IDE 环境暂时不能返回该服务，则使用项目级备用日志实例；工具窗口与导出按钮共用同一个安全入口。
- **尝试清理多余 Framework 文件**：导出阶段在配置开关改写前后进行依赖检查，将没有被保留功能引用的 `Framework/` 源码移出导出集合。仍被功能实际调用（包括 `using static`）的 Framework 辅助类保留并在报告中列出，不能为减小体积而把必需类型强行删除。
- **内嵌默认配置（新选项，默认关闭）**：已知布尔功能开关继续固定为 `true`；勾选后，还可把具有安全字面量默认值的 `ConfigEntry<int/float/double/string>` 读取替换为常量。生成的 `ModLocalization` 使用 DLL 内嵌的 JSON，语言由导出选项固定，不生成新的 MOD 专属 `.cfg` 或默认 JSON；源功能直接写配置文件时停止导出，避免承诺失效。已有配置文件不会被自动删除。
- **本地化兼容**：增加 `ModLanguageService.CurrentLocale` 的独立出口映射。
- **测试与版本**：补充服务注册、Framework 清理、默认值解析和部署模拟检查，69 项通过，版本升级为 `0.3.0`。

## 运行环境

| 项目 | 说明 |
|---|---|
| IDE | JetBrains Rider 2025.2.x，Gradle 目标 Rider SDK 2025.2.3 |
| Java | JDK 21 |
| Gradle | 9.0+，IntelliJ Platform Gradle Plugin 2.20.0 |
| MOD DLL | 支持 `net6.0` 的 .NET SDK、目标游戏可用的 BepInEx IL2CPP / SNWCreations 依赖 |

```bash
gradle coreChecks       # 69 项 Java 核心检查
gradle buildPlugin      # 使用真实 Rider SDK 构建 build/distributions/*.zip
gradle runIde           # 在 IDE 沙盒中验证工具窗口和菜单
```

无法下载 Gradle / Rider SDK 时，可先运行 `scripts/offline-core-checks.sh` 或 `scripts/offline-core-checks.ps1`，**这不等于实际 Rider 兼容性验证**。本仓库未附带 Gradle Wrapper 的二进制 JAR；请通过 Rider 的 Gradle 集成或本地 Gradle 9+ 执行。

## 基本使用

在 Rider 的 **Tools → Astral Mod Forge** 中选择 **Create New MOD** 或 **Export Selected Classes**（菜单文本跟随 IDE 的中英文语言）。

1. 勾选要作为独立功能的 C# 类；文件树可展开／折叠目录，会联动勾选类型依赖。
2. 输入独立 MOD ID；检查加载入口与 Tick；运行 **Check dependencies** 预检。
3. 选择翻译方式（内嵌 JSON 或固定文本）、是否**内嵌默认配置**、是否导出源码（默认关闭）、是否编译 DLL 和复制到游戏目录。
4. 在底部构建日志窗口查看阶段与编译输出。只有真正产生 DLL 后才会部署到有效的 BepInEx 游戏目录。

不勾选“导出源码”时，C# 工程生成在系统临时目录，完成后删除；交付目录只保留报告、清单、日志和实际编译成功的 DLL。勾选源码时，会阻止输出到原 C# 工程内部，以免触发重复类定义。

**默认配置内嵌不是万能的自动重构**：仅处理安全的字段读取和字面量默认值；自定义文件 I/O、Harmony 注册、反射、非字面量或可变配置对象仍需人工处理。`BasePlugin` 本身可在内存中构造 `ConfigFile`，但不代表会自动写入配置文件；本选项会避免生成的插件调用 `Config.Bind` 或 `Config.Save`。已存在的旧 `.cfg` 不会被清除。

## CLI

```bash
java -jar AstralModForge-v0.3.0-standalone-cli.jar new --id=dev.example.hello --project=/path/to/workspace

java -jar AstralModForge-v0.3.0-standalone-cli.jar export \
  --id=dev.example.praise --project=/path/to/AstralProbe \
  --classes=Features/Automation/AutoPraiseService.cs --embed-default-config

# 仅导出可编辑 C# 工程，不执行 dotnet build
java -jar AstralModForge-v0.3.0-standalone-cli.jar export \
  --id=dev.example.praise --project=/path/to/AstralProbe \
  --classes=Features/Automation/AutoPraiseService.cs --embed-default-config \
  --export-source --no-build
```

额外选项：`--lang=embedded|inline`、`--locale=zh_cn|en_us`、`--load=Namespace.Type::Initialize`、`--update=Namespace.Type::Tick`、`--output=/safe/output`、`--copy --game=/game/root`。

## 文档与贡献

- [使用手册 v0.3.0](docs/使用手册-v0.3.0.md)
- [架构与限制 v0.3.0](docs/架构与限制-v0.3.0.md)
- [更新记录 v0.3.0](docs/更新记录-v0.3.0.md)
- [中文 Git 提交说明 v0.3.0](docs/Git提交说明-v0.3.0.md)

本次提供**完整 Java 工程**与**相对于 v0.2.0 的分类 `git format-patch`** 两份独立 ZIP，适合先在本地验证再推送 GitHub。不要在已覆盖新工程的仓库中再次应用补丁。没有修改 AstralProbe MOD、SDK 或 ContentPreview 附属包。

官方开发参考：[JetBrains 项目服务](https://plugins.jetbrains.com/docs/intellij/plugin-services.html)、[JetBrains 工具窗口](https://plugins.jetbrains.com/docs/intellij/tool-windows.html)、[BepInEx 配置](https://docs.bepinex.dev/master/articles/dev_guide/plugin_tutorial/4_configuration.html)。
