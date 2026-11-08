# 星航 MOD 工坊 / Astral Mod Forge · v0.4.0

**Astral Mod Forge 是独立的 Java / JetBrains Rider MOD 开发辅助插件，不属于 AstralProbe MOD 的版本序列。**

从 Unity IL2CPP / HybridCLR + BepInEx 工程中勾选 C# 功能类，生成独立 MOD 的加载入口、依赖工程及可选择的本地化资源；也可以输入 MOD ID 创建空工程。工具支持依赖检查、源码可选导出、内嵌默认配置、构建日志及编译成功后复制 DLL 到游戏目录。

**[获取已正式构建的版本与更新说明 / GitHub Releases](../../releases)**

> 以前标记为 `preview-install.zip` 的安装包使用了模拟的 JetBrains API，可能在 Rider 中触发 `IncompatibleClassChangeError`。不要再安装那些预览包。只使用 GitHub Actions 通过**真实 Rider SDK** 编译且经字节码验证的 Release 附件。当前工程需要联网的 Gradle / Rider SDK 才能构建正式安装包。

## 开发环境

| 项目 | 版本 |
|---|---|
| IDE | Rider 2025.2.x（Gradle 构建目标 Rider 2025.2.3） |
| JDK | 21 |
| Gradle | 9.0+，IntelliJ Platform Gradle Plugin 2.20.0 |
| MOD DLL | `net6.0`，需要游戏对应的 BepInEx IL2CPP / SNWCreations 依赖 |

```bash
gradle coreChecks                         # Java 核心回归检查
gradle buildPlugin verifyPluginStructure  # 使用真正的 Rider SDK 构建与验证
gradle standaloneCliJar                   # 创建可独立运行的 CLI JAR
python3 scripts/test-rider-linkage.py     # 离线检查字节码拒绝规则
python3 scripts/verify-rider-linkage.py build/distributions/<实际安装包>.zip
```

`buildPlugin` 的安装 ZIP 位于 `build/distributions/`。只能在真实 SDK 构建完成后安装；不要用模拟 API 编译 IDE 代码。联网时也可运行 `gradle runIde` 在沙盒 Rider 中实际打开导出界面。

无法下载 Gradle 和 Rider SDK 时，可以先运行 `scripts/offline-core-checks.sh` 或 `.ps1` 进行基础检查，**这不等于插件兼容性验证**。

## 使用

在 Rider 的 **Tools → Astral Mod Forge** 中选择 **Create New MOD** 或 **Export Selected Classes**（选项文本跟随 IDE 语言）。

1. 在可折叠文件树中勾选目标 C# 类；勾选时自动补齐可识别的依赖，取消时同步取消依赖它的功能。导出前可检查依赖报告。
2. 输入独立 MOD ID，确认初始化入口和 Tick 调用。翻译方式可选内嵌 JSON 或固定文本。
3. 选择是否内嵌默认配置、是否保留生成的 C# 源码（默认关闭）、是否编译 DLL，以及是否复制到已验证的游戏目录。
4. 通过底部的构建日志观察进度；只有产生成功编译的 DLL 才允许部署。

未选择源码导出时，编译中间目录采用临时路径；选择源码导出时，要求目标位置**位于原 C# 工程外部**，避免重复类定义。

静态分析不是完整的 ReSharper / Roslyn 语义模型。反射、Harmony 注册、IL2CPP 运行时行为及游戏加载仍需要人工验证。复杂配置和不安全的自动重写会在导出前给出错误，而不是默默修改功能行为。

## 自动构建和公开发布

仓库包含 `.github/workflows/ci.yml`：每次推送 `main` 都使用真实 Rider SDK 构建并做兼容性检查；当 `gradle.properties` 中的 `modForgeVersion` **升级为新版本**并准备 `.github/release-notes/vX.Y.Z.md` 后，工作流自动创建对应 GitHub Release。该 Release 包含 Rider 安装 ZIP、CLI JAR 和 SHA256 文件，**更新内容放在 Release 正文，不放在 README 中**。

只有将仓库设置为 **Public**，未登录 GitHub 的用户才可以直接下载公开的 Release 文件。PR 只构建不发布；已经存在的版本不会被覆盖。详细步骤见 [GitHub 发布说明](docs/GitHub自动发布-v0.4.0.md)。

## 命令行

```bash
java -jar AstralModForge-v0.4.0-standalone-cli.jar new \
  --id=dev.example.hello --project=/path/to/workspace

java -jar AstralModForge-v0.4.0-standalone-cli.jar export \
  --id=dev.example.praise --project=/path/to/AstralProbe \
  --classes=Features/Automation/AutoPraiseService.cs --embed-default-config
```

其它选项：`--lang=embedded|inline`、`--locale=zh_cn|en_us`、`--load=Namespace.Type::Initialize`、`--update=Namespace.Type::Tick`、`--output=...`、`--export-source`、`--no-build`、`--copy --game=...`。

## 文档

- [使用手册 v0.4.0](docs/使用手册-v0.4.0.md)
- [架构与限制 v0.4.0](docs/架构与限制-v0.4.0.md)
- [GitHub 自动发布 v0.4.0](docs/GitHub自动发布-v0.4.0.md)
- 版本更新日志：**[GitHub Releases](../../releases)**，或查看其维护来源 `.github/release-notes/vX.Y.Z.md`。

官方参考：[JetBrains Gradle Plugin 2.x](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)、[Plugin Verifier](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)、[GitHub Release CLI](https://cli.github.com/manual/gh_release_create)。
