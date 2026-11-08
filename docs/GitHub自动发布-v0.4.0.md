# 星航 MOD 工坊 v0.4.0 · GitHub 自动构建与公开发布

## 为什么要更改发布方式？

v0.3.0 的离线安装候选包是针对模拟 IntelliJ API 编译的。它把 `Project.getBasePath()` 编译成 `invokevirtual`，而真实 Rider 中的 `Project` 是一个接口，最终导致 `IncompatibleClassChangeError`。本版不再用模拟 SDK 产出用户可安装 ZIP。**只有通过真实 Rider SDK 编译、字节码检查、ZIP 结构检查的安装包才允许发布。**

## 初次设置

1. 创建 GitHub 仓库，并将仓库可见性设置为 **Public**，方便没有账号的用户浏览 Release 和下载附件。如果使用私有仓库，访问者通常需要权限。
2. 将完整的 Java 源码工程推送到默认分支 `main`。如果你的主分支名称不同，请同步修改 `.github/workflows/ci.yml` 的 `on.push.branches` 和发布条件。
3. 在仓库 **Settings → Actions → General** 中检查是否允许执行 GitHub Actions。该工作流的发布作业单独声明了 `contents: write`，用于自动创建 Release；无需将个人访问令牌写进仓库。
4. 提交新版本前修改 `gradle.properties`：`modForgeVersion=0.4.0`；同步 `src/main/resources/META-INF/plugin.xml` 中的版本，并建立 `.github/release-notes/v0.4.0.md`（中英文都可以）。
5. 在 Rider 或本地 Gradle 9+ 上先运行 `gradle coreChecks`。如果已下载 Rider SDK，还应运行 `gradle buildPlugin verifyPluginStructure` 并执行兼容性校验。

## 推送后会发生什么

```text
push main / workflow_dispatch (main)
    │
    ├── 校验版本与本次 Release 说明
    ├── 运行离线字节码测试（正确的接口调用应通过，旧预览包同类调用应拒绝）
    ├── 通过真正的 Rider 2025.2.3 SDK 编译 Java 插件
    ├── 运行 69 项核心检查，验证插件 ZIP 结构
    ├── 检查最终 JAR 对 Project 的接口调用，拒绝模拟 API 类
    ├── 生成正式安装 ZIP、CLI JAR 和 SHA256SUMS.txt
    └── 构建成功时：
          ├── vX.Y.Z 已有 Release → 不重复创建，保留当前发行物
          ├── vX.Y.Z 存在未发布标签 → 拒绝有歧义的发布
          └── vX.Y.Z 是全新版本 → 创建 Release，上传文件与更新说明
```

Pull Request 只做构建检查，不创建 Release。每次向 `main` 推送都执行构建，但不会用同一版本号覆盖先前通过验证的安装包。**下一次正式更新请先增加版本号**。

Release 会附带：

- `AstralModForge-<version>.zip`（Gradle `buildPlugin` 生成的实际文件名为准）：Rider 可安装包。
- `AstralModForge-v<version>-standalone-cli.jar`：无需 Rider 的导出命令行工具。
- `SHA256SUMS.txt`：两份产物的 SHA256。可运行 `sha256sum -c SHA256SUMS.txt` 验证。

## 更新说明应放在哪里？

README 只写项目定位、核心用法、构建方法及 Releases 下载入口。每次更新的变更内容编写在 `.github/release-notes/vX.Y.Z.md` 中；工作流通过 `gh release create --notes-file` 将它写入 GitHub **Release 正文**。不必在 README 追加一长串按版本排列的更新日志。

## 常见问题

**推送后没有发布？** 看 GitHub Actions 构建是否失败；若版本号对应的 Release 已存在，会正常跳过发布；如果新的版本文件或 Release 说明不存在，检查阶段会失败。只有 `main` 的推送和 `main` 的手动运行可以发布。

**提示 incompatible `Project`？** 不要把之前的 `preview-install.zip` 再打包上传。请安装由真正 SDK 构建的 ZIP；脚本 `scripts/verify-rider-linkage.py` 会在打包阶段拦截错误的字节码，构建成功仍需在 Rider 沙盒中打开导出界面实际测试。

**构建需要 GitHub 登录吗？** 运行 Actions 需要仓库拥有者的授权；**下载公开仓库的 Release 附件不需要登录**。

**如何本地安装正式包？** 在联网环境执行 `gradle buildPlugin`，然后对 `build/distributions/*.zip` 执行字节码检查，在 Rider `Settings → Plugins → Install Plugin from Disk` 安装 ZIP 并重启。

**仓库用的不是 `main` 分支？** 修改工作流中分支条件，避免误发布其它分支。

## 官方参考

- [IntelliJ Platform Gradle Plugin 2.x](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [Plugin Verifier](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)
- [Gradle buildPlugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html)
- [GitHub CLI — gh release create](https://cli.github.com/manual/gh_release_create)
