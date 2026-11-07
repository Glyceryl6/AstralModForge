# 星航 MOD 工坊 v0.3.0 · Git 提交说明

**基线**：Astral Mod Forge `v0.2.0` 完整 Java 项目；**目标**：`v0.3.0`。本次只修改 Java 插件工程，没有变更 AstralProbe MOD、SDK、ContentPreview 附属包。

补丁包仅包含以下 4 个顺序排列的 `.patch` 文件。**完整源码 ZIP 与 Git 补丁 ZIP 分开交付**；不要将补丁文件解压到 `src` 目录中。每个补丁仅包含当前提交的文件差异。

## 0001 · `fix(ide): 修复构建日志空指针，补充服务注册与兼容处理`

- **修改** `src/main/java/dev/astral/modforge/ide/BuildLogService.java`：改为 XML 注册的项目服务；缺失服务时安全回退到按项目保存的日志对象。
- **修改** `src/main/java/dev/astral/modforge/ide/ExportModAction.java`：不再直接解引用可能为 `null` 的服务。
- **修改** `src/main/java/dev/astral/modforge/ide/ForgeToolWindowFactory.java`：工具窗口初始化和清空按钮统一使用安全获取方法。
- **修改** `src/main/resources/META-INF/plugin.xml`：注册 `BuildLogService` 项目服务。

## 0002 · `fix(export): 清理不再使用的 Framework 文件，保留必要依赖`

- **修改** `src/main/java/dev/astral/modforge/core/DependencyPlanner.java`：在设置值改写前后检查 Framework 文件是否还需要打包；报告保留和剔除情况。
- **新增** `src/main/java/dev/astral/modforge/core/FrameworkPruner.java`：移除无活跃调用的 Framework 类，保留仍被引用的辅助类。
- **修改** `src/test/java/dev/astral/modforge/core/CoreChecks.java`：增加冗余 Framework 文件剔除、必要依赖和 `using static` 保留的验证。

## 0003 · `feat(config): 支持内嵌默认设置，避免生成独立MOD配置文件`

- **修改** `src/main/java/dev/astral/modforge/cli/Main.java`：新增 `--embed-default-config` 命令行选项。
- **新增** `src/main/java/dev/astral/modforge/core/ConfigDefaultResolver.java`：解析可确认的 C# 配置默认值。
- **新增** `src/main/java/dev/astral/modforge/core/ConfigPersistenceGuard.java`：拦截仍会创建或保存配置文件的常见调用。
- **修改** `src/main/java/dev/astral/modforge/core/DependencyPlanner.java`：接入内嵌配置和持久化检查。
- **修改** `src/main/java/dev/astral/modforge/core/ForgeExporter.java`：将配置模式写入导出报告与清单。
- **修改** `src/main/java/dev/astral/modforge/core/ForgeSpec.java`：保存内嵌配置选择，兼容旧版构造方式。
- **修改** `src/main/java/dev/astral/modforge/core/ModTemplates.java`：生成不写 `.cfg` 或默认语言文件的独立入口，补充语言兼容属性。
- **修改** `src/main/java/dev/astral/modforge/core/SettingsDetacher.java`：扩展安全默认值识别，支持数字和字符串常量。
- **修改** `src/main/java/dev/astral/modforge/ide/ExportDialog.java`：加入“内嵌默认配置”复选项。
- **修改** `src/main/resources/dev/astral/modforge/i18n/Messages.properties`：增加英文说明。
- **修改** `src/main/resources/dev/astral/modforge/i18n/Messages_zh_CN.properties`：增加中文说明。
- **修改** `src/test/java/dev/astral/modforge/core/CoreChecks.java`：检查默认值读取、语言资源、危险配置写入等场景。

## 0004 · `chore(release): 升级v0.3.0，更新文档与构建检查`

- **修改** `build.gradle`：版本号升级至 0.3.0。
- **修改** `src/main/resources/META-INF/plugin.xml`：插件描述版本升级。
- **修改** `src/main/java/dev/astral/modforge/cli/Main.java`：CLI 帮助版本同步。
- **修改** `src/main/java/dev/astral/modforge/core/ForgeExporter.java`：报告及工程标识版本同步。
- **修改** `src/main/java/dev/astral/modforge/core/ModTemplates.java`：生成源码头版本同步。
- **修改** `src/test/java/dev/astral/modforge/core/CoreChecks.java`：增加服务注册和模拟部署检查，累计 69 项。
- **修改** `.github/workflows/ci.yml`：GitHub Actions 产物版本同步。
- **修改** `README.md`：完善升级使用与验证说明。
- **修改** `examples/dev_example_hello/.astral-mod-forge`、`examples/dev_example_hello/Plugin.cs`：同步示例生成版本。
- **移动并修改** `docs/使用手册-v0.2.0.md` → `docs/使用手册-v0.3.0.md`：说明新配置模式与导出流程。
- **移动并修改** `docs/架构与限制-v0.2.0.md` → `docs/架构与限制-v0.3.0.md`：更新服务注册、依赖裁剪与安全边界。
- **新增** `docs/更新记录-v0.3.0.md`：本次更新记录。
- **新增** `docs/Git提交说明-v0.3.0.md`：中文分类提交清单。

原有 `docs/更新记录-v0.2.0.md` 与 `docs/Git提交说明-v0.2.0.md` 保留作历史记录；不重复放进当前补丁。

## GitHub 操作

在**原始 v0.2.0** 的干净工作区中，解压补丁包到仓库外的文件夹：

```bash
git status --short
git am /path/to/patches/0001-*.patch /path/to/patches/0002-*.patch \
       /path/to/patches/0003-*.patch /path/to/patches/0004-*.patch

gradle coreChecks
gradle buildPlugin

git log -4 --oneline
# 请在本机验证后自行推送：
git push origin HEAD
```

如果已经覆盖了 v0.3.0 完整源码，请**不要**再应用这组补丁。新仓库可以直接从完整 Java 工程初始化；已有 v0.2.0 仓库用补丁升级。

## 验证范围

- 已通过 JDK 21 的 **69 项核心检查**，包括 `Framework` 清理、默认配置内嵌、生成代码不写配置、模拟 `dotnet` 产物部署与项目服务描述文件检查。
- 实际从 AstralProbe v0.47.0 的 `AutoPraiseService` 生成了独立源码工程；输出保留了必要的 Framework 依赖，未复制原设置界面。
- 当前环境未安装真实 Rider SDK、可用的 `dotnet` 构建链，也没有游戏运行环境，因此还不能确认真实插件安装、DLL 编译和游戏加载。
- 不会从旧版本配置目录主动删除任何文件；若源功能本身存在复杂文件 I/O，应进行人工审查。
