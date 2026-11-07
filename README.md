# 星航 MOD 工坊 / Astral Mod Forge · v0.2.0

**独立的 Java / JetBrains Rider 开发工具，不属于 AstralProbe 的 MOD 版本。**

星航 MOD 工坊用于从 Unity IL2CPP / HybridCLR、BepInEx MOD 工程中提取部分 C# 功能，生成独立 MOD 的入口、依赖工程与本地化，并尝试通过 `dotnet build` 生成 DLL。也能只输入 MOD ID 创建空 MOD 项目。

> **交付状态：开发预览版。** Java 核心的 49 项离线回归检查已通过；Rider 前端已在模拟的 IntelliJ API 签名环境下完成语法编译。由于当前环境没有真实 Rider SDK、Gradle 依赖下载能力及 .NET 编译环境，附带的 Rider 安装 ZIP **不能视为已经通过 Rider 安装／运行验证的正式构建**。请按本文使用 `gradle buildPlugin` 生成正式插件 ZIP，并自行在游戏里验证独立 DLL。

## 本次 v0.2.0 变化

- **设置开关默认开启**：可明确解析的 `ConfigEntry<bool>` 原宿主开关读取（如 `Plugin.AutoPraisePlayers.Value`）在导出的功能源码中变成 `true`；不会为它生成新的设置按钮，也不复制 `Framework.Settings`、`Framework.Configuration`、旧 `Plugin.cs` 等宿主文件。非布尔设置、动态对象引用、对开关写入等无法安全处理的情形会提示失败，而不是悄悄改变行为。
- **可选是否导出 C# 源码**：默认关闭；使用系统临时目录编译，仅在输出目录保留成功的 DLL、构建日志、导出报告和清单，不会把生成类放在原工程下。勾选源码时，默认将独立工程输出到**原 C# 项目的同级目录**，并阻止自定义输出路径落在原工程内部，以防 SDK 风格项目自动扫描产生重复定义。
- **文件浏览器式勾选树**：可展开、折叠目录；勾选功能时自动补齐可静态识别的调用依赖和 `partial` 类；取消一个依赖文件时会撤销依赖它的已选功能。支持从 Rider 文件视图带入预选项，无需 Shift 多选。
- **界面真正区分中英文**：`Messages.properties` 与 `Messages_zh_CN.properties`，通过 JetBrains `DynamicBundle` 跟随 IDE 语言；不再把所有选项机械地显示成中英双语合并字符串。工具窗口有深浅色 SVG 图标（16px、20px）。
- **构建过程可见**：工具窗口实时展示构建阶段与 `dotnet build` 输出；同时将日志写到 `build.log`，能取消后台导出。编译成功后才允许复制到游戏目录。
- **Java 代码约定**：除 JetBrains `@Service` 轻量服务强制要求 `final` 外，不增加没有必要的 `final` 类和空构造方法。

## 环境与构建

| 项目 | 要求 |
|---|---|
| IDE | JetBrains Rider 2025.2 系列；Gradle 项目目标 SDK 2025.2.3，其他版本须验证 |
| Java | JDK 21 |
| Gradle | **9.0.0 或更新**，IntelliJ Platform Gradle Plugin 2.20.0 |
| MOD DLL | 支持 `net6.0` 的 .NET SDK、可用的 NuGet 依赖、兼容的 BepInEx IL2CPP 游戏环境 |

```bash
gradle coreChecks         # Java 核心 49 项回归检查
gradle buildPlugin        # 用真正的 Rider SDK 生成 build/distributions/*.zip
gradle runIde             # 在 IDE 沙盒中试运行；正式使用前务必验证
```

本仓库未提交 Gradle Wrapper JAR，可通过 Rider 的 Gradle 集成或系统安装的 Gradle 9+ 构建。首次构建需要下载 Rider SDK；如果无法访问网络，可执行 `scripts/offline-core-checks.sh`（Java 核心测试，不代表 Rider 兼容性验证）。

官方资料：

- [Rider 插件开发](https://plugins.jetbrains.com/docs/intellij/rider.html)
- [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [工具窗口](https://plugins.jetbrains.com/docs/intellij/tool-windows.html)
- [本地化](https://plugins.jetbrains.com/docs/intellij/internationalization.html)
- [轻量服务的限制](https://plugins.jetbrains.com/docs/intellij/plugin-services.html)

## Rider 中使用

在 **Tools → Astral Mod Forge / 星航 MOD 工坊** 菜单中：

1. **新建独立 MOD**：输入 `dev.example.hello` 这样的 MOD ID，工具输出 `.csproj`、`Plugin.cs`、功能示例、独立本地化 JSON。若当前目录是 C# 项目根目录，新项目会自动写到同级的 `AstralModForge-Projects`，避免被原工程编译。
2. **导出选中类**：输入新 MOD ID，在可展开的文件树中勾选功能类；所需依赖自动勾选。检查初始化／每帧入口，点击“检查依赖”。可选择内嵌 JSON 或固定语言文本。
3. **编译／源码／部署**：默认“编译 DLL”开启、“导出 C# 源码”关闭；也可以开启源码，保存可继续开发的工程。可勾选成功后复制到游戏目录（需要有效游戏根目录）。
4. **查看日志**：展开下方 Astral Mod Forge 工具窗口查看实时阶段、构建输出；交付文件中仍保留 `build.log` 与 `export-report.md`。

**只导出 DLL（默认）**：输出 `./AstralModForge/Exports/<ID>-<时间>/dist/*.dll`、`build.log`、`export-report.md`、`export-manifest.properties`；临时 C# 工程随后删除。**导出源码**：默认使用 `<原项目>-ForgeExports/Exports/...` 保存完整工程、语言文件和可能生成的 `dist`。

`Framework.Settings` 相关配置被剥离并不意味着可安全丢弃任何设置逻辑：只自动处理明确的布尔开关**读取**。对于非布尔配置或写入引用，需拆分业务逻辑后重试。**不自动重新生成 MOD 设置页面。**

## CLI（独立 JAR）

```bash
java -jar AstralModForge-v0.2.0-standalone-cli.jar new \
  --id=dev.example.hello --project=/path/to/workspace

# 默认只尝试编译 DLL，不导出 C# 文件
java -jar AstralModForge-v0.2.0-standalone-cli.jar export \
  --id=dev.example.autopraise --project=/path/to/AstralProbe \
  --classes=Features/Automation/AutoPraiseService.cs

# 需要分析或二次开发时，可只输出源码（不会调用 dotnet）
java -jar AstralModForge-v0.2.0-standalone-cli.jar export \
  --id=dev.example.autopraise --project=/path/to/AstralProbe \
  --classes=Features/Automation/AutoPraiseService.cs --export-source --no-build
```

可选参数：`--lang=embedded|inline`、`--locale=zh_cn|en_us`、`--load=Namespace.Type::Initialize`、`--update=Namespace.Type::Tick`、`--output=/safe/path`、`--copy --game=/game/root`。不构建时必须指定 `--export-source`；需要保存源码时禁止输出到现有 C# 项目内部。

## 项目结构

```text
src/main/java/dev/astral/modforge/core/   导出与依赖、设置拆分、勾选关系模型（不依赖 Rider）
src/main/java/dev/astral/modforge/ide/    Rider 动作、Swing 勾选树、工具窗口、语言资源
src/main/java/dev/astral/modforge/cli/    独立命令行
src/main/resources/icons/              工具窗口 SVG 图标
src/main/resources/dev/astral/modforge/i18n/  中英文 Properties
src/test/java/                         49 项核心检查
examples/dev_example_hello/            新建 MOD 示例
docs/                                 使用手册、边界说明、中文 Git 提交清单
.github/workflows/ci.yml               GitHub Actions 验证并构建插件 ZIP
```

**不做的事**：本工具无法仅靠 Java 获得 Rider ReSharper 的完整 C# 语义调用图；无法自动重建所有 Harmony 注册、Unity 事件订阅、反射和动态热更新生命周期。编译通过也不意味着游戏一定能加载。请查看 [使用手册](docs/使用手册-v0.2.0.md) 和 [架构与限制](docs/架构与限制-v0.2.0.md)。

**发布到 GitHub**：完整源码工程直接作为仓库；另提供相对于 v0.1.0 的分类 `git format-patch`，按编号使用 `git am`。提交说明见 [Git 提交说明](docs/Git提交说明-v0.2.0.md)。
