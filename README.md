# 星航 MOD 工坊 / Astral Mod Forge · v0.1.0

> **独立的 Rider 开发工具，不属于 AstralProbe 的 MOD 版本。**
>
> **Standalone development tool; does not change AstralProbe's version.**

面向《吉星派对》Unity IL2CPP / HybridCLR、BepInEx 与 SNWCreations 修复版 Il2CppInterop 的 Java Rider 插件原型。提供独立 MOD 创建、选中类导出、保守的源码依赖闭包、部分生命周期接线、内嵌双语资源或固定文本导出、编译检查，以及可选的游戏目录部署。

**重要：本次交付包含安装候选包，但未在真实 Rider SDK / .NET 编译工具链 / 游戏环境验证。** 本项目的 Java 代码已通过离线单元检查；发布候选 JAR 使用临时 API 签名替身进行编译，没有把这些替身打入 JAR。正式使用前应在 Rider/IntelliJ 导入项目，用实际 Rider SDK 执行 `gradle buildPlugin` 后以新 ZIP 替换预构建候选包。

## 名称与定位

- 中文：**星航 MOD 工坊**；English: **Astral Mod Forge**。
- 插件 ID：`dev.astral.modforge`；版本：`v0.1.0`；Java 21。
- Gradle：IntelliJ Platform Gradle Plugin **2.20.0**，Rider SDK **2025.2.3**（对应 since-build `252`，可在 `build.gradle` 中调整）。
- 目标 MOD：`net6.0`，独立的 `BasePlugin.Load()`，包版本参考现有 AstralProbe 工程。
- 所有功能通过 Rider 的 **Tools → 星航 MOD 工坊 / Astral Mod Forge** 菜单启动。

## 安装 / Installation

1. 推荐做法：在 Rider 或 IntelliJ IDEA 打开本 Java 工程，使用 **JDK 21 / Gradle 9.0+** 同步 Gradle 项目；执行 `gradle buildPlugin`。
2. 官方的 Gradle 插件任务会在 `build/distributions/` 生成可安装 ZIP。Rider 的 **Settings → Plugins → 齿轮 → Install Plugin from Disk** 中选择该 ZIP，重启 IDE。
3. 另附 `AstralModForge-v0.1.0-preview-install.zip` 可用于初步界面试装，但因缺少真实 Rider SDK 验证，**不保证所有 Rider 版本兼容**。建议以 Gradle 正式构建产物为准。

本项目未内附 Gradle Wrapper JAR。可使用 Rider 中的 Gradle 集成或系统安装的 Gradle 9+，正常首次同步需要下载 JetBrains Rider SDK。插件在运行时调用本机的 `.NET SDK`：确保 Rider/系统 `dotnet --info` 可用，并能还原相应 NuGet 包。**没有 dotnet 时仍可创建/导出 C# 工程，但不会声称已编译 DLL。**

## 功能 / Features

**一、新建空 MOD / New MOD Project**

只需输入例如 `dev.example.hello` 这样的 MOD ID。自动生成可打开的 `.csproj`、`Plugin.cs`、`ModLocalization.cs`、`Features/SampleFeature.cs`、中英文 JSON、README 和 Git 忽略文件，输出到：

```text
<当前工程>/AstralModForge/Projects/dev_example_hello/
```

`Plugin.Load()` 将初始化示例模块；BepInEx GUID 使用原始 ID。配置写入该 MOD 对应的 BepInEx cfg，本地化覆盖文件位于 `BepInEx/config/<MOD ID>/lang/`，避免与 AstralProbe 共用一套文件。

**二、导出选中类 / Export Selected Classes**

从当前工程的 `.cs` 文件列表中多选（会预选 Rider 项目视图当前选中的源码文件），输入 MOD ID，可填写多个初始化、每帧入口（用英文分号 `;` 分隔），然后选择本地化模式：

- **内嵌 JSON（推荐）**：内嵌 `zh_cn` / `en_us` 默认资源；生成的 MOD 可以在独立配置文件选择语言，也可在游戏外修改 `lang/*.json`；受支持的 `astralprobe.*` 翻译键会转换成新 MOD ID 前缀。
- **固定文本（可选）**：把明确形如 `ModLanguageService.Translate("literal.key")` 或 `ModLocalization.T("literal.key")` 的调用替换成指定语言的 C# 字符串字面量；动态键、未知键、直接传给 UI 的翻译键将停止导出，避免错误替换。

依赖收集按选中文件的类型标识追踪同工程依赖，并自动包含同一个类的其他 `partial` 分部。只自动调用**所选类**中可明确识别的 `Init/Initialize/Install`、`Tick()` / `Tick(float)`，不会擅自运行依赖工具类的启动方法。可手动填写：

```text
初始化入口：Some.Namespace.ClassA::Initialize;Some.Namespace.ClassB::Install
更新入口：Some.Namespace.ClassA::Tick;Some.Namespace.ClassB::Tick
```

输出结构：

```text
<当前工程>/AstralModForge/Exports/<MOD ID 安全目录名>-<日期时间>/
├─ MOD.csproj
├─ Plugin.cs
├─ ModLocalization.cs
├─ ModLanguageCompat.cs
├─ Source/                 # 本次闭包 C# 文件，不回写原工程
├─ lang/zh_cn.json
├─ lang/en_us.json
├─ export-manifest.properties
├─ export-report.md
├─ build.log                 # 如已运行 dotnet build
└─ dist/                     # 仅在真正产生 DLL 后生成
```

**三、部署 / Copy to game**

勾选后提供游戏根目录，仅当 `BepInEx/core`、`BepInEx/plugins` 和 Unity 游戏标记存在且编译成功，才复制到 `BepInEx/plugins/<MOD>/`。旧同名 DLL 会先备份；无效游戏路径或编译失败时不复制，只保留输出工程／成功构建的 `dist`。拒绝符号链接绕出游戏目录。

## 兼容性范围和必须复查的部分

这是开发效率工具，**不是完整 ReSharper/Roslyn 的调用图重写器**。Rider 的 C# 语义服务运行于 ReSharper 后端，纯 Java 前端不能准确获知所有间接引用和 Unity 生命周期。以下情况需要用户审查、或预先将类改造成可独立初始化的模块：

- 多态、反射、字符串定位类型、Harmony Patch 特性、事件注册、动态调用、额外的 `Awake/OnDestroy/OnEnable`；不会自动复制或绑定所有这些调用链。
- 原 `HotfixWatcher`/`Plugin` 的私有服务及 SDK 注册顺序。内建兼容层仅提供基础日志、`SaveSettings()`、部分 `ConfigEntry<T>`；其它宿主专属引用会阻止导出或导致编译诊断。生成的配置项初始值为保守值，**不等于原 MOD 的用户配置**。
- 原 `.csproj` 中的项目引用会用源计算机上的绝对路径保留；跨设备迁移要改为 NuGet 包或随包构建 SDK。引用了共享 SDK 的输出可能包含额外 DLL，请检查多个 MOD 之间是否存在版本冲突。
- 已有代码直接传给 SDK UI 的翻译键、硬编码 `AstralProbe` 配置路径等不能保证自动转换，预检报告会提示。
- Unity、IL2CPP 和 FairyGUI 必须在游戏主线程访问；生成的 `Update` 组件在主线程调度，但不会自动补齐游戏状态检查、卸载逻辑或事件反注册。
- **不要同时启用原版 AstralProbe 和复制了同一功能的独立 MOD**，以免重复订阅、重复 UI 或抢占热键。
- 源工程保存后再导出；导出读取磁盘文件，不读取 Rider 未保存的编辑缓冲区。

## 源码／测试

```text
src/main/java/dev/astral/modforge/core/   核心功能：无 JetBrains 依赖
src/main/java/dev/astral/modforge/ide/    Rider 菜单与 Swing 面板
src/main/java/dev/astral/modforge/cli/    共用核心的命令行入口
src/main/resources/META-INF/plugin.xml  插件声明
src/test/java/                         Java 离线回归检查
examples/dev_example_hello/            生成的空 MOD 示例
```

源码可以直接在 Rider 里导入 Gradle。没有 IDE SDK 时可独立运行 `scripts/offline-core-checks.sh`，或直接用 `javac` 编译 `core/`、`cli/` 并执行 `CoreChecks`。本次验证包含 **29 项纯 Java 检查**、真实 AstralProbe `AutoPraiseService` 选择导出推演（11 个 C# 文件）。**未做 .NET 编译、游戏加载、JetBrains 插件验证器检查。**

## CLI（运行插件 JAR）

```bash
java -jar AstralModForge.jar new --id=dev.example.mod --project=/path/to/workspace
java -jar AstralModForge.jar export --id=dev.example.mod --project=/path/to/AstralProbe \
  --classes=Features/Automation/AutoPraiseService.cs --no-build
java -jar AstralModForge.jar export --id=dev.example.mod --project=/path/to/AstralProbe \
  --classes=Features/SomeA.cs,Features/SomeB.cs \
  '--load=MyNamespace.SomeA::Initialize;MyNamespace.SomeB::Install' \
  --lang=embedded --copy --game=/path/to/game
```

更多细节见 `docs/使用手册-v0.1.0.md`、`docs/架构与限制-v0.1.0.md`。
