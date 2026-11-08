package dev.astral.modforge.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** End-to-end deterministic export; no build = no DLL and no game copy. */
public class ForgeExporter {
    public record Result(Path directory, Path report, Path dll, boolean compiled, boolean deployed, List<String> notes) {}
    private static final Pattern NAMESPACE = Pattern.compile("\\bnamespace\\s+([A-Za-z_][\\w.]*)\\s*[;{]");
    private static final Pattern LANGUAGE = Pattern.compile("\\b(?:ModLanguageService|ModLocalization)\\s*\\.\\s*(\\w+)");
    private static final Set<String> LANGUAGE_METHODS = Set.of("Translate", "T", "CurrentLocale");

    public Result export(ForgeSpec spec) throws Exception {
        return export(spec, ExportObserver.silent());
    }

    /** Work is staged outside the C# workspace when source export is disabled. */
    public Result export(ForgeSpec spec, ExportObserver observer) throws Exception {
        Objects.requireNonNull(observer, "observer");
        if (!Files.isDirectory(spec.sourceRoot())) {
            throw new IOException(ForgeText.select("原工程目录不存在 / Source project missing", observer.language()));
        }
        emit(observer, "phase", "检查依赖 / Resolving dependencies");
        CSharpIndex index = new CSharpIndex(spec.sourceRoot());
        DependencyPlanner.Plan plan = new DependencyPlanner(index).plan(spec);
        LocalizationTransformer.Result locale = new LocalizationTransformer().transform(
                spec.sourceRoot(), spec, plan.sources(), plan.rewrittenSources());
        List<String> errors = new ArrayList<>(plan.errors());
        errors.addAll(locale.errors());
        for (CSharpIndex.Unit unit : plan.sources()) {
            Matcher m = LANGUAGE.matcher(plan.rewrittenSources().get(unit.file()));
            while (m.find()) if (!LANGUAGE_METHODS.contains(m.group(1)))
                errors.add("翻译桥接不支持该成员 / Unsupported localization member: " + m.group(0));
        }
        if (!errors.isEmpty()) {
            String heading = ForgeText.select("预检查失败 / Preflight failed", observer.language());
            List<String> localized = errors.stream()
                    .map(error -> ForgeText.select(error, observer.language())).distinct().toList();
            throw new IOException(heading + ":\n- " + String.join("\n- ", localized));
        }
        if (observer.isCancelled()) {
            throw new IOException(ForgeText.select("用户取消导出 / Export cancelled", observer.language()));
        }

        String namespace = sourceNamespace(spec.sourceRoot());
        Path projects = spec.destination().resolve("Exports");
        Files.createDirectories(projects);
        Path target = unusedDirectory(projects, spec.safeName());
        Files.createDirectories(target);
        Path stage = spec.exportSource() ? target : Files.createTempDirectory("astral-mod-forge-build-");
        StringBuilder report = new StringBuilder("# Astral Mod Forge v0.5.2 导出报告 / Export report\n\n" +
                "MOD ID: `" + spec.modId() + "`\n" +
                "源工程 / Source: `" + spec.sourceRoot() + "`\n" +
                "输出目录 / Output: `" + target + "`\n" +
                "导出源码 / Export source: `" + spec.exportSource() + "`\n" +
                "模式 / Language: `" + spec.languageMode() + ":" + spec.locale() + "`\n" +
                "DLL 名称 / Assembly: `" + spec.assemblyName() + ".dll`\n" +
                "内嵌默认配置 / Embed defaults: `" + spec.embedDefaultConfig() + "`\n\n" +
                "## 依赖来源 / Copied classes\n");
        List<CSharpIndex.Unit> sorted = new ArrayList<>(plan.sources());
        sorted.sort(Comparator.comparing(u -> index.root().relativize(u.file()).toString()));
        for (CSharpIndex.Unit unit : sorted)
            report.append("- `").append(index.root().relativize(unit.file())).append("`\n");
        report.append("\n## 语言资源 / Localization resources\n\n")
                .append("- zh_cn: ").append(locale.zh().size()).append(" keys\n")
                .append("- en_us: ").append(locale.en().size()).append(" keys\n")
                .append("- 仅包含所选功能引用的键；无法推断的动态键需要显式声明。 / ")
                .append("Only selected feature keys are embedded.\n");
        report.append("\n## 生成的启动入口 / Generated entrypoints\n\n")
                .append("- Load: `").append(plan.loads().isEmpty() ? "(none)" : String.join("; ",
                        plan.loads().stream().map(e -> e.type()+"::"+e.method()).toList())).append("`\n")
                .append("- Update: `").append(plan.updates().isEmpty() ? "(none)" : String.join("; ",
                        plan.updates().stream().map(e -> e.type()+"::"+e.method()).toList())).append("`\n")
                .append("\n## 注意 / Diagnostics\n\n");
        List<String> notes = new ArrayList<>();
        notes.addAll(plan.warnings()); notes.addAll(locale.warnings());
        StandaloneBuildSupport.Plan buildSupport = new StandaloneBuildSupport().plan(spec);
        notes.addAll(buildSupport.notes());
        notes.add("开关在独立 MOD 中默认开启；不复制宿主的 Settings 与 Configuration 类。");
        if (spec.embedDefaultConfig())
            notes.add("内嵌默认配置：语言与已识别的设置值直接编入 DLL，不生成 .cfg 或默认 JSON；已存在的旧配置不会自动删除。");
        notes.add("请验证原 HotfixWatcher 生命周期调用是否全部移植，特别是 Harmony 或事件注册。");
        notes.add("C# 类型依赖收集是静态启发式分析，编译与游戏加载才是最终验证。");
        notes.add("避免与原 MOD 同时启用重复功能；源项目 SDK 依赖可能需要单独部署。");
        for (String note : notes) report.append("- ").append(note).append("\n");
        report.append("\n## 编译 / Build\n\n");
        Path outputDll = target.resolve("dist/" + spec.assemblyName() + ".dll");
        boolean built = false, deployed = false;
        try {
            emit(observer, "phase", "生成独立工程 / Generating standalone project");
            write(stage, "MOD.csproj", ModTemplates.csproj(spec, buildSupport.references()));
            write(stage, "NuGet.Config", buildSupport.nugetConfig());
            write(stage, "ForgePaths.props", buildSupport.pathsProps());
            for (var copy : buildSupport.filesToCopy().entrySet()) {
                Path dest = stage.resolve(copy.getKey()).normalize();
                if (!dest.startsWith(stage)) {
                    throw new IOException("Unsafe dependency path: " + copy.getKey());
                }
                Files.createDirectories(dest.getParent());
                Files.copy(copy.getValue(), dest, StandardCopyOption.REPLACE_EXISTING);
            }
            write(stage, "Plugin.cs", ModTemplates.plugin(spec, namespace, plan.loads(), plan.updates()));
            write(stage, "ModLocalization.cs", ModTemplates.localization(spec, namespace));
            write(stage, "ModLanguageCompat.cs", ModTemplates.compatibility(namespace));
            if (spec.languageMode() == ForgeSpec.LanguageMode.EMBEDDED) {
                write(stage, "lang/zh_cn.json", JsonFlat.write(locale.zh()));
                write(stage, "lang/en_us.json", JsonFlat.write(locale.en()));
            }
            for (CSharpIndex.Unit unit : sorted) {
                Path relative = index.root().relativize(unit.file());
                write(stage, "Source/" + relative.toString().replace('\\', '/'), locale.sources().get(unit.file()));
            }
            if (spec.exportSource()) {
                write(stage, ".astral-mod-forge", "Astral Mod Forge 0.5.2\n" + spec.modId() + "\n");
                write(stage, "README.md", "# " + spec.modId() + "\n\n" +
                        "独立导出工程。检查 export-report.md 以及 ForgePaths.props 中的 ForgeInteropDir；运行 `dotnet restore --configfile NuGet.Config`、`dotnet build --no-restore -c Release`。\n\n" +
                        "Check export-report.md and ForgePaths.props. Restore with NuGet.Config, then build.\n");
            }
            StringBuilder manifest = new StringBuilder("# Astral Mod Forge v0.5.2 / 导出清单\n" +
                    "id=" + spec.modId() + "\n" + "language=" + spec.languageMode() + "\n" +
                    "locale=" + spec.locale() + "\n" + "source=" + spec.exportSource() + "\n" +
                    "embeddedDefaults=" + spec.embedDefaultConfig() + "\n" +
                    "load=" + String.join(";", plan.loads().stream().map(e -> e.type()+"::"+e.method()).toList()) + "\n" +
                    "update=" + String.join(";", plan.updates().stream().map(e -> e.type()+"::"+e.method()).toList()) + "\n");
            for (CSharpIndex.Unit unit : sorted)
                manifest.append("file=").append(index.root().relativize(unit.file()).toString().replace('\\', '/')).append('\n');
            write(target, "export-manifest.properties", manifest.toString());
            if (observer.isCancelled()) throw new IOException("用户取消导出 / Export cancelled");
            if (spec.build()) {
                emit(observer, "phase", "还原 NuGet 依赖 / Restoring NuGet packages");
                if (!commandAvailable(dotnetCommand())) {
                    throw new IOException(ForgeText.select(
                            "未找到 dotnet SDK，请安装 .NET SDK，或取消编译仅导出源码 / "
                                    + "dotnet SDK unavailable. Install the .NET SDK, or export sources without building.",
                            observer.language()));
                }
                if (buildSupport.interopDir() == null) {
                    throw new IOException(ForgeText.select(
                            "找不到游戏 BepInEx/interop 目录。请在 ForgePaths.props 设置 ForgeInteropDir，"
                                    + "并确保存在 Il2Cppmscorlib.dll、UnityEngine.CoreModule.dll 和 UnityEngine.InputLegacyModule.dll / "
                                    + "Game interop directory is missing. Set ForgeInteropDir in ForgePaths.props, "
                                    + "with Il2Cppmscorlib.dll, UnityEngine.CoreModule.dll and UnityEngine.InputLegacyModule.dll.",
                            observer.language()));
                }

                Path buildLog = target.resolve("build.log");
                List<String> restore = List.of(dotnetCommand(), "restore", stage.resolve("MOD.csproj").toString(),
                        "--configfile", stage.resolve("NuGet.Config").toString(), "--nologo", "-v", "minimal");
                int restoreExit = runCommand(restore, stage, buildLog, observer);
                if (restoreExit != 0) {
                    throw new IOException(ForgeText.select(
                            "NuGet 还原失败（退出码 " + restoreExit + "），请查看 build.log；"
                                    + "确认 NuGet.Config 中 BepInEx 官方源可访问 / "
                                    + "NuGet restore failed (exit code " + restoreExit + "). Check build.log and "
                                    + "BepInEx feed access in NuGet.Config.", observer.language()));
                }

                emit(observer, "phase", "开始编译 / Running dotnet build");
                List<String> build = List.of(dotnetCommand(), "build", stage.resolve("MOD.csproj").toString(),
                        "-c", "Release", "--no-restore", "--nologo", "-v", "minimal");
                int buildExit = runCommand(build, stage, buildLog, observer);
                if (buildExit != 0) {
                    throw new IOException(ForgeText.select(
                            "C# 编译失败（退出码 " + buildExit + "），请查看 build.log 和导出的工程 / "
                                    + "C# compilation failed (exit code " + buildExit + "). See build.log and generated project.",
                            observer.language()));
                }

                Path dll = stage.resolve("bin/Release/net6.0/" + spec.assemblyName() + ".dll");
                if (!Files.isRegularFile(dll) || Files.size(dll) == 0L) {
                    throw new IOException(ForgeText.select(
                            "编译未生成有效 DLL，请查看 build.log / "
                                    + "Build completed without a nonempty DLL. Check build.log.", observer.language()));
                }

                Files.createDirectories(outputDll.getParent());
                Files.copy(dll, outputDll, StandardCopyOption.REPLACE_EXISTING);
                // Ship both managed and native SDK libraries when referenced. The installer
                // already copies every DLL in the distribution; never copy Unity proxy DLLs.
                // 若独立 MOD 引用了 SDK Native，必须同时部署对应的原生辅助 DLL。
                for (String library : List.of("AstralParty.Modding.SDK.dll",
                        "AstralParty.Modding.SDK.Native.dll")) {
                    Path sdk = dll.getParent().resolve(library);
                    if (Files.isRegularFile(sdk)) {
                        Files.copy(sdk, outputDll.getParent().resolve(sdk.getFileName()),
                                StandardCopyOption.REPLACE_EXISTING);
                        report.append("- 已包含 SDK 依赖 / Bundled SDK dependency: ")
                                .append(library).append('\n');
                    }
                }
                built = true;
                report.append("- 编译成功 / Build succeeded: `").append(outputDll).append("`\n");
            } else {
                report.append("- 已导出源码，未执行编译 / Source-only export.\n");
            }
            if (built && spec.copyToGame()) {
                emit(observer, "phase", "复制到游戏 / Deploying DLL");
                StringBuilder reason = new StringBuilder();
                if (!GameInstaller.valid(spec.gameRoot(), reason)) {
                    report.append("- 游戏目录无效，已保留在 dist：").append(reason).append("\n");
                } else try {
                    GameInstaller.copy(spec.gameRoot(), spec.safeName(), outputDll.getParent(), notes);
                    deployed = true; report.append("- 已复制至游戏 BepInEx/plugins/" + spec.safeName() + "。\n");
                } catch (IOException ex) {
                    report.append("- 复制失败，已保留在 dist：").append(ex.getMessage()).append("\n");
                }
            } else if (spec.copyToGame()) report.append("- 无 DLL，未复制游戏目录 / No DLL, deployment skipped.\n");
            write(target, "export-report.md", report.toString());
            emit(observer, "phase", built ? "编译完成 / Build complete" : "源码导出完成 / Source export complete");
            return new Result(target, target.resolve("export-report.md"), built ? outputDll : null,
                    built, deployed, List.copyOf(notes));
        } catch (Exception ex) {
            report.append("\n## 错误 / Error\n\n").append(ex.getMessage()).append('\n');
            report.append("\n编译未成功；未复制 DLL 到游戏。 / Build did not succeed; no DLL deployed.\n");
            Path failureReport = target.resolve("export-report.md");
            write(target, "export-report.md", report.toString());
            throw new IOException(ex.getMessage() + "\n" +
                    ForgeText.select("导出诊断 / Export report", observer.language())
                    + ": " + failureReport, ex);
        } finally {
            if (!spec.exportSource()) deleteTree(stage);
        }
    }

    private static void emit(ExportObserver observer, String kind, String message) {
        observer.onEvent(kind, ForgeText.select(message, observer.language()));
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public Result createEmpty(Path workspace, String id) throws Exception {
        Files.createDirectories(workspace);
        Path base = workspace.resolve("AstralModForge");
        // Prevent new project's .cs files from being glob-compiled into the host MOD project.
        // 若 Rider 打开的是 .csproj 根目录，新项目默认输出到同级目录。
        try (var paths = Files.list(workspace)) {
            if (paths.anyMatch(p -> p.toString().endsWith(".csproj")))
                base = workspace.toAbsolutePath().normalize().resolveSibling("AstralModForge-Projects");
        }
        ForgeSpec spec = new ForgeSpec(workspace, id, List.of(), base,
                ForgeSpec.LanguageMode.EMBEDDED, "zh_cn", "", "", false, false, null);
        Path destination = spec.destination().resolve("Projects").resolve(spec.safeName());
        if (Files.exists(destination)) throw new IOException("目标已存在，拒绝覆盖 / MOD already exists: " + destination);
        Files.createDirectories(destination);
        String namespace=spec.pluginNamespace();
        DependencyPlanner.Entry load = new DependencyPlanner.Entry(namespace+".Features.SampleFeature", "Initialize", true, false, "Load");
        StandaloneBuildSupport.Plan support = new StandaloneBuildSupport().plan(spec);
        write(destination, spec.safeName()+".csproj", ModTemplates.csproj(spec, support.references()));
        write(destination, "NuGet.Config", support.nugetConfig());
        write(destination, "ForgePaths.props", support.pathsProps());
        for (var copy : support.filesToCopy().entrySet()) {
            Path path = destination.resolve(copy.getKey()).normalize();
            if (!path.startsWith(destination)) {
                throw new IOException("Unsafe dependency path: " + copy.getKey());
            }
            Files.createDirectories(path.getParent());
            Files.copy(copy.getValue(), path, StandardCopyOption.REPLACE_EXISTING);
        }
        write(destination, "Plugin.cs", ModTemplates.plugin(spec, namespace, List.of(load), List.of()));
        write(destination, "ModLocalization.cs", ModTemplates.localization(spec, namespace));
        write(destination, "Features/SampleFeature.cs", "// 删除此示例后，请同步修改 Plugin.Load 的调用。\n" +
            "// Remove the sample and update Plugin.Load when starting real features.\n" +
            "namespace "+namespace+".Features {\n" +
            "    internal static class SampleFeature {\n" +
            "        internal static void Initialize() {\n" +
            "            Plugin.ModLog.LogInfo(ModLocalization.T(\""+id+".sample.loaded\"));\n" +
            "        }\n" +
            "    }\n" +
            "}\n");
        write(destination, "lang/zh_cn.json", JsonFlat.write(Map.of(id+".sample.loaded", "示例功能已初始化")));
        write(destination, "lang/en_us.json", JsonFlat.write(Map.of(id+".sample.loaded", "Sample feature initialized")));
        write(destination, ".gitignore", "bin/\nobj/\n.idea/\n*.user\n");
        write(destination, ".astral-mod-forge", "Astral Mod Forge 0.5.2\n"+id+"\n");
        write(destination, "README.md", "# " + id + "\n\n" +
            "生成的 BepInEx Unity IL2CPP 独立 MOD 项目。使用 Rider 打开 `"+spec.safeName()+".csproj`，先在 ForgePaths.props 配置游戏的 BepInEx/interop 路径，再执行 `dotnet restore --configfile NuGet.Config` 和 `dotnet build -c Release --no-restore`。\n" +
            "\nGenerated standalone BepInEx IL2CPP project. Build with .NET 6 SDK.\n" +
            "\n**请核对游戏运行的 BepInEx / SNWCreations 包版本，并避免加载与原 MOD 重复的功能。**\n");
        write(destination, "export-report.md", "# New project / 新建项目\n\nMOD ID: `"+id+"`\n\n" +
            "配置：`ForgePaths.props`；还原：`dotnet restore --configfile NuGet.Config`；编译：`dotnet build --no-restore -c Release`。\n");
        return new Result(destination, destination.resolve("export-report.md"), null, false, false, List.of());
    }

    private static String sourceNamespace(Path root) {
        try {
            Path p = root.resolve("Plugin.cs");
            if (Files.isRegularFile(p)) {
                Matcher m = NAMESPACE.matcher(CSharpIndex.maskLiteralsAndComments(Files.readString(p)));
                if (m.find()) return m.group(1);
            }
        } catch(Exception ignored) {}
        return "AstralModForge.Exported";
    }

    private static Path unusedDirectory(Path parent, String name) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path candidate = parent.resolve(name + "-" + stamp); int index = 1;
        while (Files.exists(candidate)) candidate = parent.resolve(name + "-" + stamp + "-" + index++);
        return candidate;
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw new IOException("拒绝目录穿越 / Path escape: " + relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String dotnetCommand() {
        // Allows a non-PATH SDK and isolated integration tests without modifying project settings.
        return System.getProperty("astral.modforge.dotnet", "dotnet");
    }

    /** Runs restore and compile separately; both phases append to a single actionable build.log. */
    private static int runCommand(List<String> command, Path directory, Path buildLog,
                                  ExportObserver observer) throws IOException, InterruptedException {
        try (BufferedWriter log = Files.newBufferedWriter(buildLog, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            String header = "$ " + String.join(" ", command);
            log.write(header);
            log.newLine();
            log.flush();
            observer.onEvent("log", header);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        Thread reader = Thread.ofVirtual().name("astral-forge-dotnet-output").start(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter log = Files.newBufferedWriter(buildLog, StandardCharsets.UTF_8,
                         StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                String line;
                while ((line = lines.readLine()) != null) {
                    log.write(line);
                    log.newLine();
                    log.flush();
                    observer.onEvent("log", line);
                }
            } catch (IOException exception) {
                emit(observer, "warning", "构建日志读取失败 / Build output error: " + exception.getMessage());
            }
        });
        boolean completed = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300);
        try {
            while (!(completed = process.waitFor(1, TimeUnit.SECONDS))) {
                if (observer.isCancelled()) {
                    throw new IOException(ForgeText.select("用户取消编译 / Build cancelled", observer.language()));
                }
                if (System.nanoTime() >= deadline) {
                    throw new IOException(ForgeText.select("编译超时（300秒） / Build timed out after 300 seconds",
                            observer.language()));
                }
            }
            return process.exitValue();
        } finally {
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            reader.join(3000);
        }
    }

    private static boolean commandAvailable(String name) {
        try {
            Process p = new ProcessBuilder(name, "--version").redirectErrorStream(true).start();
            return p.waitFor(8, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }
}
