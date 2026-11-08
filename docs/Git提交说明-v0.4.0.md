# 星航 MOD 工坊 v0.4.0 · Git 提交与交付说明

**补丁基线：v0.3.0 完整 Java 源码项目。** 这四个补丁只包含工具项目的更新，不涉及 AstralProbe MOD、SDK 和附属包。Git 补丁 ZIP 与完整 Java 项目 ZIP **分开交付**。

## 按顺序提交

### 0001 `fix(ide): 拦截错误的Rider接口字节码，禁止发布模拟API构建包`

新增文件：

- `scripts/verify-rider-linkage.py`：检查 Gradle 输出的插件 ZIP，阻止把真实 Rider 的 `Project` 接口错误编译为类调用，并检查 IDE API 类未混进安装包。
- `scripts/test-rider-linkage.py`：接口、错误类调用和打包伪 API 的三种离线回归用例。

### 0002 `style(java): 整理方法空行与大括号，成员访问优先使用this`

新增文件：

- `.editorconfig`：统一 Java 方法的空行、大括号和缩进设置。

修改文件：

- `src/main/java/dev/astral/modforge/ide/BuildLogService.java`
- `src/main/java/dev/astral/modforge/ide/ClassSelectionTree.java`
- `src/main/java/dev/astral/modforge/ide/ExportDialog.java`
- `src/main/java/dev/astral/modforge/ide/ExportModAction.java`
- `src/main/java/dev/astral/modforge/ide/ForgeToolWindowFactory.java`
- `src/main/java/dev/astral/modforge/ide/I18n.java`
- `src/main/java/dev/astral/modforge/ide/NewModAction.java`
- `src/main/java/dev/astral/modforge/ide/ProjectRoots.java`
- `src/main/java/dev/astral/modforge/cli/Main.java`
- `src/main/java/dev/astral/modforge/core/CSharpIndex.java`
- `src/main/java/dev/astral/modforge/core/ClassSelectionModel.java`
- `src/main/java/dev/astral/modforge/core/DependencyPlanner.java`
- `src/main/java/dev/astral/modforge/core/ExportObserver.java`
- `src/main/java/dev/astral/modforge/core/ForgeSpec.java`
- `src/main/java/dev/astral/modforge/core/GameInstaller.java`
- `src/main/java/dev/astral/modforge/core/JsonFlat.java`
- `src/main/java/dev/astral/modforge/core/LocalizationTransformer.java`
- `src/main/java/dev/astral/modforge/core/ModTemplates.java`

其中对 `GameInstaller`、`JsonFlat` 移除了不必要的 `final` 和空构造方法。代码格式的整理不应改变独立 MOD 的功能逻辑。

### 0003 `feat(release): 推送后自动构建，版本更新时发布安装包和更新说明`

新增文件：

- `.github/release-notes/v0.4.0.md`：将本次更新内容准备为 GitHub Release 的正文，包含中英文说明。
- `scripts/check-release-metadata.py`：检查版本、插件描述和 Release 内容一致。

修改文件：

- `.github/workflows/ci.yml`：每次更新构建、发布前兼容性验证、SHA256 文件、公开 Release。
- `build.gradle`：版本从统一属性读取，添加独立 CLI JAR 的构建任务，继续要求真实 Rider SDK。
- `gradle.properties`：新增工具版本 `modForgeVersion=0.4.0`。
- `src/main/resources/META-INF/plugin.xml`：升级至 0.4.0。
- `src/main/java/dev/astral/modforge/cli/Main.java`：更新命令行版本文本。
- `src/main/java/dev/astral/modforge/core/ForgeExporter.java`：同步更新导出报告和生成工程中的工具版本文本。

### 0004 `docs(release): 更新v0.4.0使用说明，明确安装和自动发布流程`

新增文件：

- `docs/GitHub自动发布-v0.4.0.md`
- `docs/使用手册-v0.4.0.md`
- `docs/架构与限制-v0.4.0.md`
- `docs/Git提交说明-v0.4.0.md`

修改文件：

- `README.md`：仅保留功能与安装说明、Release 下载入口，不再堆积每次更新记录。

## 应用补丁

从**没有本次改动的 v0.3.0 Git 工作树**开始，先确认所有改动已提交或备份，然后：

```bash
# 解压补丁 ZIP 后，在现有 Java 项目根目录运行
 git am 0001-*.patch 0002-*.patch 0003-*.patch 0004-*.patch
```

不要先覆盖 v0.4.0 完整源码再应用同一批补丁。补丁包含全部本次新增、修改内容，每个补丁的 Git 邮件头带提交作者与中文提交标题。

## 验证及交付限制

- 已验证 Java 21 的 **69 项核心检查**，接口校验器的 **3 项专项回归**，并确认该校验器拒绝此前出错的 v0.3.0 安装候选包。
- 离线使用接口形式的测试替身进行了 IDE 源码**语法编译**，这不等于使用真实 Rider SDK 构建。
- 本环境不能访问 JetBrains Maven/Gradle 下载源，因此**没有生成能保证安装的 Rider 插件 ZIP，也没有实际推送到 GitHub 或创建线上 Release**。首次推送后需在 GitHub Actions 查看真正的构建结果。
- 公开仓库的 `main` 推送自动构建；只有 `modForgeVersion` 改为新的版本且提供对应的 Release 文本后才新建 Release。现有版本不会被覆盖。
- 安装完成后，建议在 Rider 中依次打开“新建 MOD”和“导出选中类”界面，验证工具窗口、树形选择及导出日志。
