package dev.astral.modforge.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end deterministic export; no build = no DLL and no game copy.
 */
public final class ForgeExporter {

    public record Result(Path directory, Path report, Path dll, boolean compiled, boolean deployed, List<String> notes) { }

    private static final Pattern NAMESPACE = Pattern.compile("\\bnamespace\\s+([A-Za-z_][\\w.]*)\\s*[;{]");
    private static final Pattern LANGUAGE = Pattern.compile("\\b(?:ModLanguageService|ModLocalization)\\s*\\.\\s*(\\w+)");
    private static final Set<String> LANGUAGE_METHODS = Set.of("Translate", "T");

    public Result export(ForgeSpec spec) throws Exception {
        if (!Files.isDirectory(spec.sourceRoot())) throw new IOException("原工程目录不存在 / Source project missing");
        CSharpIndex index = new CSharpIndex(spec.sourceRoot());
        DependencyPlanner.Plan plan = new DependencyPlanner(index).plan(spec);
        LocalizationTransformer.Result locale = new LocalizationTransformer().transform(spec.sourceRoot(), spec, plan.sources());
        List<String> errors = new ArrayList<>(plan.errors());
        errors.addAll(locale.errors());
        for (CSharpIndex.Unit u : plan.sources()) {
            Matcher m = LANGUAGE.matcher(u.code());
            while (m.find()) if (!LANGUAGE_METHODS.contains(m.group(1)))
                errors.add("翻译桥接不支持该成员 / Unsupported localization member: " + m.group(0));
        }
        if (!errors.isEmpty())
            throw new IOException("预检查失败 / Preflight failed:\n- " + String.join("\n- ", new LinkedHashSet<>(errors)));
        String namespace = sourceNamespace(spec.sourceRoot());
        Path projects = spec.destination().resolve("Exports");
        Files.createDirectories(projects);
        Path target = unusedDirectory(projects, spec.safeName());
        Files.createDirectories(target);
        StringBuilder report = new StringBuilder("# Astral Mod Forge v0.1.0 导出报告 / Export report\n\n" +
                "MOD ID: `" + spec.modId() + "`\n" +
                "源工程 / Source: `" + spec.sourceRoot() + "`\n" +
                "输出目录 / Output: `" + target + "`\n" +
                "模式 / Language: `" + spec.languageMode() + ":" + spec.locale() + "`\n\n" +
                "## 依赖来源 / Copied classes\n");
        List<CSharpIndex.Unit> sorted = new ArrayList<>(plan.sources());
        sorted.sort(Comparator.comparing(u -> index.root().relativize(u.file()).toString()));
        for (CSharpIndex.Unit unit : sorted)
            report.append("- `").append(index.root().relativize(unit.file())).append("`\n");
        report.append("\n## 生成的启动入口 / Generated entrypoints\n\n")
                .append("- Load: `").append(plan.loads().isEmpty() ? "(none)" : String.join("; ", plan.loads().stream().map(e -> e.type() + "::" + e.method()).toList())).append("`\n")
                .append("- Update: `").append(plan.updates().isEmpty() ? "(none)" : String.join("; ", plan.updates().stream().map(e -> e.type() + "::" + e.method()).toList())).append("`\n")
                .append("\n## 注意 / Diagnostics\n\n");
        List<String> notes = new ArrayList<>();
        notes.addAll(plan.warnings());
        notes.addAll(locale.warnings());
        notes.add("如果目标特性原本由旧 HotfixWatcher 调用，必须复核 Load/Update 是否覆盖全部生命周期。");
        notes.add("此工具只收集同工程中可见的类型引用；真正的遗漏依赖由 dotnet build 报告。");
        notes.add("引用 ProjectReference 的工程可能无法脱离原仓库独立重建；请将相关 SDK 打包或发布为 NuGet。");
        notes.add("不要同时运行重复功能的原 MOD 和独立导出 MOD，以免注册相同事件或重复处理。");
        for (String note : notes) report.append("- ").append(note).append("\n");
        report.append("\n## 编译 / Build\n\n");
        try {
            write(target, "MOD.csproj", ModTemplates.csproj(spec));
            write(target, "Plugin.cs", ModTemplates.plugin(spec, namespace, plan.loads(), plan.updates(), plan.configEntries()));
            write(target, "ModLocalization.cs", ModTemplates.localization(spec, namespace));
            write(target, "ModLanguageCompat.cs", ModTemplates.compatibility(namespace));
            write(target, "lang/zh_cn.json", JsonFlat.write(locale.zh()));
            write(target, "lang/en_us.json", JsonFlat.write(locale.en()));
            for (CSharpIndex.Unit unit : sorted) {
                Path relative = index.root().relativize(unit.file());
                write(target, "Source/" + relative.toString().replace('\\', '/'), locale.sources().get(unit.file()));
            }
            write(target, ".astral-mod-forge", "Astral Mod Forge 0.1.0\n" + spec.modId() + "\n");
            StringBuilder manifest = new StringBuilder("# Generated from the selected source files / 独立导出清单\n" +
                    "id=" + spec.modId() + "\n" +
                    "language=" + spec.languageMode() + "\n" +
                    "locale=" + spec.locale() + "\n" +
                    "load=" + String.join(";", plan.loads().stream().map(e -> e.type() + "::" + e.method()).toList()) + "\n" +
                    "update=" + String.join(";", plan.updates().stream().map(e -> e.type() + "::" + e.method()).toList()) + "\n");
            for (CSharpIndex.Unit unit : sorted)
                manifest.append("file=").append(index.root().relativize(unit.file()).toString().replace('\\', '/')).append('\n');
            write(target, "export-manifest.properties", manifest.toString());
            write(target, "README.md", "# " + spec.modId() + "\n\n独立导出工程。先检查 export-report.md，然后运行 `dotnet build -c Release`。\n\n" +
                    "Standalone export. Check export-report.md before calling `dotnet build -c Release`.\n" +
                    "Run from the output directory; NuGet restore needs network access.\n");
        } catch (Exception ex) {
            write(target, "export-report.md", report.append("\n生成失败 / Generation failed: ").append(ex).toString());
            throw ex;
        }
        Path dll = target.resolve("bin/Release/net6.0/" + spec.safeName() + ".dll");
        boolean built = false, deployed = false;
        if (spec.build()) {
            if (!commandAvailable("dotnet")) {
                report.append("- 未发现 dotnet SDK，未编译 DLL。/ .NET SDK not found on PATH.\n");
                notes.add("未安装 dotnet SDK / dotnet unavailable");
            } else {
                ProcessBuilder pb = new ProcessBuilder("dotnet", "build", target.resolve("MOD.csproj").toString(),
                        "-c", "Release", "--nologo", "-v", "minimal");
                pb.directory(target.toFile());
                pb.redirectErrorStream(true);
                pb.redirectOutput(target.resolve("build.log").toFile());
                Process process = pb.start();
                boolean finished = process.waitFor(300, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    report.append("- 编译超时（300s），请查看 build.log。/ Timed out.\n");
                } else if (process.exitValue() != 0)
                    report.append("- 编译失败，退出码 ").append(process.exitValue()).append("，见 build.log。/ Failed.\n");
                else if (!Files.isRegularFile(dll))
                    report.append("- dotnet 未生成预期的 DLL；检查输出路径。/ No DLL found.\n");
                else {
                    built = true;
                    report.append("- 编译成功 / Build succeeded: `").append(dll).append("`\n");
                }
            }
        } else report.append("- 用户选择仅导出源码 / Build disabled.\n");
        if (built) {
            Path distribution = target.resolve("dist");
            Files.createDirectories(distribution);
            Files.copy(dll, distribution.resolve(dll.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            // Include project-built SDK if present (never bundle BepInEx or IL2CPP runtime assemblies).
            Path sdk = dll.getParent().resolve("AstralParty.Modding.SDK.dll");
            if (Files.isRegularFile(sdk)) {
                Files.copy(sdk, distribution.resolve(sdk.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                report.append("- 包含 SDK 依赖 DLL，请检查是否与其它 MOD 的版本冲突。\n");
            }
            if (spec.copyToGame()) {
                StringBuilder reason = new StringBuilder();
                if (!GameInstaller.valid(spec.gameRoot(), reason)) {
                    report.append("- 游戏目录无效，已保留在 dist：").append(reason).append("\n");
                } else {
                    try {
                        GameInstaller.copy(spec.gameRoot(), spec.safeName(), distribution, notes);
                        deployed = true;
                        report.append("- 已复制至游戏 BepInEx/plugins/").append(spec.safeName()).append("。\n");
                    } catch (IOException ex) {
                        report.append("- 游戏目录复制失败，已保留在 dist：").append(ex.getMessage()).append("\n");
                    }
                }
            }
        } else if (spec.copyToGame()) report.append("- 未构建 DLL，已跳过游戏复制。\n");
        write(target, "export-report.md", report.toString());
        return new Result(target, target.resolve("export-report.md"), built ? dll : null, built, deployed, List.copyOf(notes));
    }

    public Result createEmpty(Path workspace, String id) throws Exception {
        ForgeSpec spec = new ForgeSpec(workspace, id, List.of(), workspace.resolve("AstralModForge"),
                ForgeSpec.LanguageMode.EMBEDDED, "zh_cn", "", "", false, false, null);
        Path destination = spec.destination().resolve("Projects").resolve(spec.safeName());
        if (Files.exists(destination))
            throw new IOException("目标已存在，拒绝覆盖 / MOD already exists: " + destination);
        Files.createDirectories(destination);
        String namespace = spec.pluginNamespace();
        DependencyPlanner.Entry load = new DependencyPlanner.Entry(namespace + ".Features.SampleFeature", "Initialize", true, false, "Load");
        write(destination, spec.safeName() + ".csproj", ModTemplates.csproj(spec));
        write(destination, "Plugin.cs", ModTemplates.plugin(spec, namespace, List.of(load), List.of(), Map.of()));
        write(destination, "ModLocalization.cs", ModTemplates.localization(spec, namespace));
        write(destination, "Features/SampleFeature.cs", "// 删除此示例后，请同步修改 Plugin.Load 的调用。\n" +
                "// Remove the sample and update Plugin.Load when starting real features.\n" +
                "namespace " + namespace + ".Features {\n" +
                "    internal static class SampleFeature {\n" +
                "        internal static void Initialize() {\n" +
                "            Plugin.ModLog.LogInfo(ModLocalization.T(\"" + id + ".sample.loaded\"));\n" +
                "        }\n" +
                "    }\n" +
                "}\n");
        write(destination, "lang/zh_cn.json", JsonFlat.write(Map.of(id + ".sample.loaded", "示例功能已初始化")));
        write(destination, "lang/en_us.json", JsonFlat.write(Map.of(id + ".sample.loaded", "Sample feature initialized")));
        write(destination, ".gitignore", "bin/\nobj/\n.idea/\n*.user\n");
        write(destination, ".astral-mod-forge", "Astral Mod Forge 0.1.0\n" + id + "\n");
        write(destination, "README.md", "# " + id + "\n\n" +
                "生成的 BepInEx Unity IL2CPP 独立 MOD 项目。使用 Rider 打开 `" + spec.safeName() + ".csproj`，执行 `dotnet build -c Release`。\n" +
                "\nGenerated standalone BepInEx IL2CPP project. Build with .NET 6 SDK.\n" +
                "\n**请核对游戏运行的 BepInEx / SNWCreations 包版本，并避免加载与原 MOD 重复的功能。**\n");
        write(destination, "export-report.md", "# New project / 新建项目\n\nMOD ID: `" + id + "`\n\n" +
                "编译：`dotnet build -c Release`；安装目标：游戏根目录/BepInEx/plugins/。\n");
        return new Result(destination, destination.resolve("export-report.md"), null, false, false, List.of());
    }

    private static String sourceNamespace(Path root) {
        try {
            Path p = root.resolve("Plugin.cs");
            if (Files.isRegularFile(p)) {
                Matcher m = NAMESPACE.matcher(CSharpIndex.maskLiteralsAndComments(Files.readString(p)));
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {
        }
        return "AstralModForge.Exported";
    }

    private static Path unusedDirectory(Path parent, String name) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path candidate = parent.resolve(name + "-" + stamp);
        int index = 1;
        while (Files.exists(candidate)) candidate = parent.resolve(name + "-" + stamp + "-" + index++);
        return candidate;
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw new IOException("拒绝目录穿越 / Path escape: " + relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static boolean commandAvailable(String name) {
        try {
            Process p = new ProcessBuilder(name, "--version").redirectErrorStream(true).start();
            return p.waitFor(8, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
