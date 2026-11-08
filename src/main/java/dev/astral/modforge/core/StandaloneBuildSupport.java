package dev.astral.modforge.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Creates the build-time files missing from a copied .csproj.
 * 生成独立工程自己的 NuGet 源、游戏代理引用和 SDK 工程副本；不继承宿主的构建脚本。
 */
public class StandaloneBuildSupport {
    private static final String NUGET_ORG = "https://api.nuget.org/v3/index.json";
    private static final String BEPINEX_FEED = "https://nuget.bepinex.dev/v3/index.json";
    private static final String DEFAULT_BEPINEX_VERSION = "6.0.0-be.789";
    private static final String DEFAULT_SNW_VERSION = "1.5.2-snw.4";
    private static final List<String> REQUIRED_PROXIES = List.of(
            "Il2Cppmscorlib", "UnityEngine.CoreModule", "UnityEngine.InputLegacyModule");
    private static final List<String> OPTIONAL_PROXIES = List.of(
            "Il2CppSystem", "Il2CppSystem.Core", "UnityEngine.TextRenderingModule",
            "UnityEngine.ScreenCaptureModule", "Assembly-CSharp");

    public record Plan(String references, String nugetConfig, String pathsProps,
                       Map<String, Path> filesToCopy, Path interopDir, List<String> notes) {
    }

    public Plan plan(ForgeSpec spec) throws IOException {
        Path root = spec.sourceRoot();
        Document source = findSourceProject(root);
        Map<String, Path> supportingFiles = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        StringBuilder references = new StringBuilder();
        Set<String> packages = new LinkedHashSet<>();
        String bepVersion = property(source, "AstralBepInExIl2CppPackageVersion", DEFAULT_BEPINEX_VERSION);

        if (source != null) {
            NodeList listed = source.getElementsByTagName("PackageReference");
            for (int i = 0; i < listed.getLength(); i++) {
                Element item = (Element) listed.item(i);
                String name = item.getAttribute("Include");
                if (name.isBlank() || !packages.add(name.toLowerCase(java.util.Locale.ROOT))) {
                    continue;
                }

                // The generated Plugin has a literal [BepInPlugin] attribute; the host's build-only
                // PluginInfo generator cannot contribute anything, and its private NuGet feed was lost.
                // 导出入口已经直接声明插件 ID，跳过原工程只在构建时使用的 PluginInfoProps。
                if (name.equalsIgnoreCase("BepInEx.PluginInfoProps")
                        || name.equalsIgnoreCase("BepInEx.Analyzers")) {
                    notes.add("跳过宿主专用构建包 / Skipped host-only build package: " + name);
                    continue;
                }
                String version = item.getAttribute("Version");
                if (version.isBlank()) {
                    NodeList children = item.getElementsByTagName("Version");
                    if (children.getLength() > 0) {
                        version = children.item(0).getTextContent().trim();
                    }
                }
                version = version.replace("$(AstralBepInExIl2CppPackageVersion)", bepVersion);
                if (version.isBlank() || version.contains("$(")) {
                    throw new IOException("无法确定 NuGet 包版本 / Unresolved package version: "
                            + name + " (" + version + "). Check the original .csproj.");
                }
                references.append("    <PackageReference Include=\"").append(xml(name))
                        .append("\" Version=\"").append(xml(version)).append('"');
                for (String attribute : List.of("ExcludeAssets", "IncludeAssets", "PrivateAssets")) {
                    if (item.hasAttribute(attribute)) {
                        references.append(' ').append(attribute).append("=\"")
                                .append(xml(item.getAttribute(attribute))).append('"');
                    }
                }
                references.append(" />\n");
            }

            copyProjectReferences(root, source, references, supportingFiles, notes);
            copyAssemblyReferences(root, source, references, supportingFiles);
        }

        appendFallbackPackage(references, packages, "BepInEx.Unity.IL2CPP", "[" + bepVersion + "]", " IncludeAssets=\"compile\"");
        appendFallbackPackage(references, packages, "SNWCreations.Il2CppInterop.Runtime", "[" + DEFAULT_SNW_VERSION + "]", "");
        appendFallbackPackage(references, packages, "SNWCreations.Il2CppInterop.Common", "[" + DEFAULT_SNW_VERSION + "]", "");
        appendFallbackPackage(references, packages, "Il2CppInterop.Runtime", "1.5.3", " ExcludeAssets=\"all\" PrivateAssets=\"all\"");
        appendFallbackPackage(references, packages, "Il2CppInterop.Common", "1.5.3", " ExcludeAssets=\"all\" PrivateAssets=\"all\"");

        Path interop = locateInterop(spec);
        addProxyReferences(references, interop);
        if (interop == null) {
            notes.add("未找到游戏互操作目录。打开 ForgePaths.props 设置 ForgeInteropDir；编译需要 BepInEx/interop。"
                    + " / Game interop directory not found. Set ForgeInteropDir in ForgePaths.props.");
        } else {
            notes.add("游戏代理 DLL 由 ForgeInteropDir 引用，不复制到 MOD 发行目录。 / Game proxy DLLs remain external: "
                    + interop);
        }
        return new Plan(references.toString(), nugetConfig(root), pathsProps(interop),
                Map.copyOf(supportingFiles), interop, List.copyOf(notes));
    }

    /** Build-time proxies are game-owned; they must not be deployed with the MOD. */
    private static void addProxyReferences(StringBuilder references, Path interop) throws IOException {
        Set<String> names = new LinkedHashSet<>(REQUIRED_PROXIES);
        names.addAll(OPTIONAL_PROXIES);
        if (interop != null) {
            try (Stream<Path> files = Files.list(interop)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".dll")) {
                        continue;
                    }
                    String assembly = name.substring(0, name.length() - 4);
                    if (!hostRuntimeAssembly(assembly)) {
                        names.add(assembly);
                    }
                }
            }
        }
        for (String name : names) {
            references.append("    <Reference Include=\"").append(xml(name))
                    .append("\" Condition=\"Exists('$(ForgeInteropDir)/")
                    .append(xml(name)).append(".dll')\">\n")
                    .append("      <HintPath>$(ForgeInteropDir)/").append(xml(name)).append(".dll</HintPath>\n")
                    .append("      <Private>false</Private>\n")
                    .append("    </Reference>\n");
        }
    }

    private static boolean hostRuntimeAssembly(String name) {
        return name.equalsIgnoreCase("mscorlib") || name.equalsIgnoreCase("netstandard")
                || name.equalsIgnoreCase("0Harmony") || name.startsWith("System.")
                || name.startsWith("Microsoft.") || name.startsWith("BepInEx.")
                || name.startsWith("Il2CppInterop.") || name.startsWith("Mono.");
    }

    private static void appendFallbackPackage(StringBuilder out, Set<String> already,
                                              String name, String version, String extra) {
        if (already.add(name.toLowerCase(java.util.Locale.ROOT))) {
            out.append("    <PackageReference Include=\"").append(name)
                    .append("\" Version=\"").append(version).append('"')
                    .append(extra).append(" />\n");
        }
    }

    private static void copyProjectReferences(Path root, Document source, StringBuilder out,
                                              Map<String, Path> files, List<String> notes) throws IOException {
        NodeList projectRefs = source.getElementsByTagName("ProjectReference");
        for (int i = 0; i < projectRefs.getLength(); i++) {
            Element e = (Element) projectRefs.item(i);
            String include = e.getAttribute("Include");
            if (include.isBlank() || include.contains("$(")) {
                throw new IOException("未支持的项目引用 / Unsupported ProjectReference: " + include);
            }
            Path original = root.resolve(include.replace('\\', '/')).toAbsolutePath().normalize();
            if (!Files.isRegularFile(original)) {
                throw new IOException("关联 SDK 工程不存在 / Referenced C# project missing: " + original);
            }
            // An exported SDK is an independent source copy, not an absolute reference back into the host MOD.
            // 保留 SDK 工程及源码；避免生成绝对路径 ProjectReference 导致 Rider 异常或移动后失效。
            // The native SDK is a nested C# project that depends on the managed SDK.
            // Copy their shared repository root once, preserving ProjectReference relative paths.
            // 原生 SDK 是托管 SDK 的子工程，两者必须保留原本的相对目录关系。
            Path projectRoot = original.getParent();
            boolean nativeSdk = original.getFileName().toString()
                    .equals("AstralParty.Modding.SDK.Native.csproj")
                    && projectRoot.getFileName().toString().equals("Native");
            boolean managedSdk = original.getFileName().toString()
                    .equals("AstralParty.Modding.SDK.csproj")
                    && projectRoot.getFileName().toString().equals("AstralParty.Modding.SDK");
            if (nativeSdk) {
                projectRoot = projectRoot.getParent();
                if (!projectRoot.getFileName().toString().equals("AstralParty.Modding.SDK")) {
                    throw new IOException("SDK Native project is not next to its managed SDK: " + original);
                }
            }
            String relativeDir = "Dependencies/Projects/" +
                    (nativeSdk || managedSdk ? "AstralParty.Modding.SDK"
                            : safeFolder(projectRoot.getFileName().toString()));
            if (!projectRoot.toRealPath().startsWith(root.getParent().toRealPath())) {
                throw new IOException("不复制工程目录以外的源码 / ProjectReference outside workspace: " + original);
            }
            try (Stream<Path> entries = Files.walk(projectRoot)) {
                for (Path file : entries.filter(Files::isRegularFile).toList()) {
                    Path relative = projectRoot.relativize(file);
                    if (excludedSupportFile(relative)) {
                        continue;
                    }
                    String name = relative.toString().replace('\\', '/');
                    if (name.endsWith(".cs") || name.endsWith(".csproj") || name.endsWith(".resx")
                            || name.endsWith(".props") || name.endsWith(".targets") || name.endsWith(".json")) {
                        String destination = relativeDir + "/" + name;
                        Path previous = files.putIfAbsent(destination, file);
                        if (previous != null && !previous.equals(file)) {
                            throw new IOException("重复 SDK 文件 / Duplicate support file: " + destination);
                        }
                    }
                }
            }
            String relativeProject = projectRoot.relativize(original).toString().replace('\\', '/');
            out.append("    <ProjectReference Include=\"").append(xml(relativeDir)).append('/')
                    .append(xml(relativeProject)).append('"');
            if (nativeSdk) {
                // Re-exported native dependencies use the exported project's ForgePaths.props.
                // 保证从 Rider 打开生成工程时 Native SDK 也能找到同一份游戏代理。
                out.append(" AdditionalProperties=\"AstralInteropDir=$(ForgeInteropDir)\"");
            }
            out.append(" />\n");
            notes.add("独立包含项目依赖 / Bundled referenced C# project: " + original.getFileName());
        }
    }

    private static boolean excludedSupportFile(Path relative) {
        for (Path element : relative) {
            String part = element.toString();
            if (Set.of("bin", "obj", "tests", ".git", ".idea", ".vs").contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static String safeFolder(String input) {
        return input.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void copyAssemblyReferences(Path root, Document source, StringBuilder out,
                                               Map<String, Path> files) throws IOException {
        NodeList assemblies = source.getElementsByTagName("Reference");
        for (int i = 0; i < assemblies.getLength(); i++) {
            Element e = (Element) assemblies.item(i);
            String name = e.getAttribute("Include");
            NodeList hints = e.getElementsByTagName("HintPath");
            if (name.isBlank() || hints.getLength() == 0) {
                continue;
            }
            String hint = hints.item(0).getTextContent().trim();
            if (hint.contains("$(")) {
                // Game proxy assemblies are reconstructed using ForgeInteropDir.
                continue;
            }
            Path target = root.resolve(hint.replace('\\', '/')).toAbsolutePath().normalize();
            if (!Files.isRegularFile(target)) {
                throw new IOException("DLL 引用不存在 / Referenced DLL missing: " + target);
            }
            String destination = "Dependencies/Assemblies/" + safeFolder(name) + ".dll";
            files.put(destination, target);
            out.append("    <Reference Include=\"").append(xml(name)).append("\"><HintPath>")
                    .append(xml(destination)).append("</HintPath><Private>false</Private></Reference>\n");
        }
    }

    public static Path locateInterop(ForgeSpec spec) {
        List<Path> candidates = new ArrayList<>();
        if (spec.gameRoot() != null) {
            candidates.add(spec.gameRoot().resolve("BepInEx/interop"));
        }
        candidate(candidates, System.getenv("ASTRAL_PARTY_INTEROP_DIR"));
        String game = System.getenv("ASTRAL_PARTY_GAME_DIR");
        if (game != null && !game.isBlank()) {
            candidates.add(Path.of(game).resolve("BepInEx/interop"));
        }
        candidates.add(spec.sourceRoot().resolve("lib/interop"));
        Path sourceProps = spec.sourceRoot().resolve("AstralLocal.props");
        if (Files.isRegularFile(sourceProps)) {
            try {
                Document props = readXml(sourceProps);
                candidate(candidates, property(props, "AstralInteropDir", ""));
                String sourceGame = property(props, "AstralGameDir", "");
                if (!sourceGame.isBlank()) {
                    candidates.add(Path.of(sourceGame).resolve("BepInEx/interop"));
                }
            } catch (Exception ignored) {
                // Invalid local props should not prevent editing the generated ForgePaths.props.
            }
        }
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate) && Files.isRegularFile(candidate.resolve("Il2Cppmscorlib.dll"))
                    && Files.isRegularFile(candidate.resolve("UnityEngine.CoreModule.dll"))
                    && Files.isRegularFile(candidate.resolve("UnityEngine.InputLegacyModule.dll"))) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static void candidate(List<Path> candidates, String value) {
        if (value != null && !value.isBlank() && !value.contains("$(")) {
            candidates.add(Path.of(value));
        }
    }

    public static String pathsProps(Path interop) {
        StringBuilder out = new StringBuilder("<Project>\n" +
                "  <!-- Edit ForgeInteropDir if the game was moved. / 游戏目录变化时修改 ForgeInteropDir。 -->\n" +
                "  <PropertyGroup>\n" +
                "    <ForgeInteropDir Condition=\"'$(ForgeInteropDir)' == '' and '$(ASTRAL_PARTY_INTEROP_DIR)' != ''\">$(ASTRAL_PARTY_INTEROP_DIR)</ForgeInteropDir>\n" +
                "    <ForgeInteropDir Condition=\"'$(ForgeInteropDir)' == '' and '$(ASTRAL_PARTY_GAME_DIR)' != ''\">$(ASTRAL_PARTY_GAME_DIR)/BepInEx/interop</ForgeInteropDir>\n" +
                "    <ForgeInteropDir Condition=\"'$(ForgeInteropDir)' == '' and Exists('$(MSBuildProjectDirectory)/lib/interop/Il2Cppmscorlib.dll')\">$(MSBuildProjectDirectory)/lib/interop</ForgeInteropDir>\n");
        if (interop != null) {
            out.append("    <ForgeInteropDir Condition=\"'$(ForgeInteropDir)' == ''\">")
                    .append(xml(interop.toString().replace('\\', '/'))).append("</ForgeInteropDir>\n");
        }
        return out.append("  </PropertyGroup>\n</Project>\n").toString();
    }

    public static String nugetConfig(Path sourceRoot) {
        LinkedHashMap<String, String> sources = new LinkedHashMap<>();
        sources.put("nuget.org", NUGET_ORG);
        sources.put("BepInEx", BEPINEX_FEED);
        for (String name : List.of("NuGet.Config", "nuget.config", "NuGet.config")) {
            Path file = sourceRoot.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                Document configuration = readXml(file);
                NodeList groups = configuration.getElementsByTagName("packageSources");
                if (groups.getLength() > 0) {
                    NodeList elements = groups.item(0).getChildNodes();
                    for (int i = 0; i < elements.getLength(); i++) {
                        Node element = elements.item(i);
                        if (element instanceof Element item && item.getTagName().equals("add")) {
                            String url = item.getAttribute("value");
                            if (url.startsWith("https://")) {
                                sources.put(item.getAttribute("key"), url);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Always keep the two public feeds; never copy credentials from a source config.
            }
            break;
        }
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<configuration>\n  <packageSources>\n    <clear />\n");
        sources.forEach((key, url) -> out.append("    <add key=\"").append(xml(key))
                .append("\" value=\"").append(xml(url)).append("\" />\n"));
        return out.append("  </packageSources>\n</configuration>\n").toString();
    }

    private static Document findSourceProject(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> files = Files.list(root)) {
            Path file = files.filter(p -> p.toString().endsWith(".csproj"))
                    .sorted().findFirst().orElse(null);
            return file == null ? null : readXml(file);
        }
    }

    private static Document readXml(Path file) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(file.toFile());
        } catch (Exception ex) {
            throw new IOException("C# 工程 XML 无法读取 / Invalid project XML: " + file, ex);
        }
    }

    private static String property(Document doc, String name, String fallback) {
        if (doc != null) {
            NodeList matches = doc.getElementsByTagName(name);
            if (matches.getLength() > 0) {
                String text = matches.item(0).getTextContent().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return fallback;
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", "&apos;");
    }
}
