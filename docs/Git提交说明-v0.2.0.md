# 星航 MOD 工坊 v0.2.0 · Git 提交说明

**基线**：独立 Java 工具 Astral Mod Forge v0.1.0；本次更新至 v0.2.0。**不涉及 AstralProbe、SDK、附属包源码**。这份说明与补丁独立交付，用于将现有 v0.1.0 仓库逐项升级并推送至 GitHub。

## 按顺序应用的中文提交

### 0001 · `feat(export): 默认开启独立功能，移除原MOD设置依赖，增加源码导出选项`

- **修改** `src/main/java/dev/astral/modforge/core/CSharpIndex.java`：整理依赖识别，减少同名类误匹配。
- **新增** `src/main/java/dev/astral/modforge/core/ClassSelectionModel.java`：提取独立的勾选与调用关系模型。
- **修改** `src/main/java/dev/astral/modforge/core/DependencyPlanner.java`：忽略原设置文件，检查入口和依赖。
- **新增** `src/main/java/dev/astral/modforge/core/ExportObserver.java`：提供导出过程和取消通知。
- **修改** `src/main/java/dev/astral/modforge/core/ForgeExporter.java`：加入独立工程处理、构建日志、源码可选输出和清理。
- **修改** `src/main/java/dev/astral/modforge/core/ForgeSpec.java`：新增源码导出开关，检查危险输出目录。
- **修改** `src/main/java/dev/astral/modforge/core/LocalizationTransformer.java`：使用完成设置清理后的源码进行本地化转换。
- **修改** `src/main/java/dev/astral/modforge/core/ModTemplates.java`：独立入口与项目模板不再生成原 MOD 的开关。
- **新增** `src/main/java/dev/astral/modforge/core/SettingsDetacher.java`：将可确认的布尔开关默认设为开启，遇到无法安全处理的设置时停止导出。
- **修改** `src/main/java/dev/astral/modforge/cli/Main.java`：支持 `--export-source`，默认不导出源码。

### 0002 · `feat(ui): 文件改为勾选树，自动关联依赖类`

- **新增** `src/main/java/dev/astral/modforge/ide/ClassSelectionTree.java`：增加类似文件浏览器的可展开复选框目录。
- **修改** `src/main/java/dev/astral/modforge/ide/ExportDialog.java`：改为勾选选择，补充源码输出、依赖预检查、构建和部署选项。

### 0003 · `feat(ui): 显示构建过程，新增工具窗口图标，界面支持中英文`

- **新增** `src/main/java/dev/astral/modforge/ide/BuildLogService.java`：收集构建日志和阶段信息。
- **修改** `src/main/java/dev/astral/modforge/ide/ExportModAction.java`：在后台构建，并将日志发往工具窗口。
- **新增** `src/main/java/dev/astral/modforge/ide/ForgeToolWindowFactory.java`：提供可清空、复制的构建控制台。
- **修改** `src/main/java/dev/astral/modforge/ide/I18n.java`：跟随 Rider 语言读取中英文资源。
- **修改** `src/main/java/dev/astral/modforge/ide/NewModAction.java`：调整界面文字。
- **修改** `src/main/resources/META-INF/plugin.xml`：配置工具窗口、菜单动作与语言资源。
- **修改** `src/main/resources/dev/astral/modforge/i18n/Messages.properties`：英文界面文字。
- **修改** `src/main/resources/dev/astral/modforge/i18n/Messages_zh_CN.properties`：中文界面文字。
- **新增** `src/main/resources/icons/toolWindowIcon.svg`：工具窗口图标。
- **新增** `src/main/resources/icons/toolWindowIcon_dark.svg`：深色主题图标。
- **新增** `src/main/resources/icons/toolWindowIcon@20x20.svg`：20 像素图标。
- **新增** `src/main/resources/icons/toolWindowIcon@20x20_dark.svg`：深色主题 20 像素图标。

### 0004 · `chore(release): 升级v0.2.0，增加检查，补充构建与使用说明`

- **新增** `.github/workflows/ci.yml`：GitHub Actions 上执行检查并生成正式插件 ZIP。
- **修改** `build.gradle`：版本号调整为 `0.2.0`。
- **修改** `README.md`：新版功能、使用方式和验证范围。
- **删除** `docs/使用手册-v0.1.0.md`、`docs/架构与限制-v0.1.0.md`：旧版文档路径。
- **新增** `docs/使用手册-v0.2.0.md`、`docs/架构与限制-v0.2.0.md`：新版文档。
- **新增** `docs/更新记录-v0.2.0.md`：更新内容清单。
- **新增** `docs/Git提交说明-v0.2.0.md`：当前文件，记录每个提交的新增与修改。
- **修改** `examples/dev_example_hello/.astral-mod-forge`、`examples/dev_example_hello/Plugin.cs`：同步模板和示例版本。
- **修改属性** `scripts/offline-core-checks.sh`：保留可执行权限。
- **修改** `src/test/java/dev/astral/modforge/core/CoreChecks.java`：检查增加至 49 项，覆盖设置清理、勾选关联、源码输出、日志与编译失败情况。

## 补丁包与使用方法

Git 补丁 ZIP 内直接按 `0001`～`0004` 放置 `.patch` 文件，不包含任何重复的 Java 源码。按照序号依次应用到 **v0.1.0 对应仓库**：

```bash
# 在已有 v0.1.0 Git 仓库中（先确认工作区干净）
git status --short
# 解压补丁包到 /path/to/patches（不要把补丁解压到 src/ 下）
git am /path/to/patches/0001-*.patch /path/to/patches/0002-*.patch \
       /path/to/patches/0003-*.patch /path/to/patches/0004-*.patch

git log -4 --oneline
gradle coreChecks
gradle buildPlugin

# 检查无误后，按你的仓库分支策略推送
git push origin HEAD
```

注意：请**二选一**，不要先覆盖完整 v0.2.0 源码再对同一目录应用 `.patch`。完整源码 ZIP 适合新仓库初始化；Git 补丁适合已有 v0.1.0 仓库增量更新。补丁的原基线、提交日期、作者字段均用于识别本次离线交付，不代表已推送任何远端仓库。

## 测试边界

- Java 21 核心：49 项检查通过；典型 AstralProbe `AutoPraiseService` 导出推演通过，原设置类没有被复制。
- Git：各补丁能够在干净 v0.1.0 基线依次 `git am` 应用，应用后的文件与完整更新包一致。
- 真实 Rider SDK / .NET SDK / 游戏：**当前环境无法验证**；请通过 `gradle buildPlugin`、Rider 安装、`dotnet build`、BepInEx 运行日志完成最后确认。
