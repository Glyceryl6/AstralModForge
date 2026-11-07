package dev.astral.modforge.core;

import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.*;
import java.util.*;

/** Buildable standalone C# entry point + per-MOD localization. / 独立运行框架与内嵌资源模板。 */
public class ModTemplates {
    private static String escape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;"); }

    public static String csproj(ForgeSpec s) {
        String references = sourceReferences(s.sourceRoot());
        if (!references.contains("BepInEx.Unity.IL2CPP")) references += "    <PackageReference Include=\"BepInEx.Unity.IL2CPP\" Version=\"[6.0.0-be.789]\" IncludeAssets=\"compile\" />\n";
        if (!references.contains("SNWCreations.Il2CppInterop.Runtime")) references += "    <PackageReference Include=\"SNWCreations.Il2CppInterop.Runtime\" Version=\"[1.5.2-snw.4]\" />\n";
        if (!references.contains("SNWCreations.Il2CppInterop.Common")) references += "    <PackageReference Include=\"SNWCreations.Il2CppInterop.Common\" Version=\"[1.5.2-snw.4]\" />\n";
        if (!references.contains("<PackageReference Include=\"Il2CppInterop.Runtime\"")) references += "    <PackageReference Include=\"Il2CppInterop.Runtime\" Version=\"1.5.3\" ExcludeAssets=\"all\" PrivateAssets=\"all\" />\n";
        if (!references.contains("<PackageReference Include=\"Il2CppInterop.Common\"")) references += "    <PackageReference Include=\"Il2CppInterop.Common\" Version=\"1.5.3\" ExcludeAssets=\"all\" PrivateAssets=\"all\" />\n";
        return "<Project Sdk=\"Microsoft.NET.Sdk\">\n" +
                "  <PropertyGroup>\n" +
                "    <TargetFramework>net6.0</TargetFramework>\n" +
                "    <AssemblyName>" + escape(s.safeName()) + "</AssemblyName>\n" +
                "    <Version>1.0.0</Version>\n" +
                "    <LangVersion>latest</LangVersion>\n" +
                "    <Nullable>enable</Nullable>\n" +
                "    <AllowUnsafeBlocks>true</AllowUnsafeBlocks>\n" +
                "    <GenerateAssemblyInfo>true</GenerateAssemblyInfo>\n" +
                "  </PropertyGroup>\n" +
                "  <ItemGroup>\n" + references + "  </ItemGroup>\n" +
                "  <ItemGroup>\n" +
                "    <EmbeddedResource Include=\"lang/zh_cn.json\" LogicalName=\"" + escape(s.modId()) + ".lang.zh_cn.json\" />\n" +
                "    <EmbeddedResource Include=\"lang/en_us.json\" LogicalName=\"" + escape(s.modId()) + ".lang.en_us.json\" />\n" +
                "  </ItemGroup>\n" +
                "</Project>\n";
    }

    private static String sourceReferences(Path root) {
        try {
            Path project;
            try (var paths = Files.list(root)) {
                project = paths.filter(p -> p.toString().endsWith(".csproj")).findFirst().orElse(null);
            }
            if (project == null) return "";
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document d = f.newDocumentBuilder().parse(project.toFile());
            StringBuilder b = new StringBuilder();
            NodeList packages = d.getElementsByTagName("PackageReference");
            for (int i=0; i<packages.getLength(); i++) {
                Element e=(Element)packages.item(i);
                if(e.getAttribute("Include").isBlank())continue;
                b.append("    <PackageReference");
                for (String attribute : List.of("Include", "Version", "ExcludeAssets", "IncludeAssets", "PrivateAssets")) {
                    if (e.hasAttribute(attribute)) b.append(' ').append(attribute).append("=\"").append(escape(e.getAttribute(attribute).replace("$(AstralBepInExIl2CppPackageVersion)", "6.0.0-be.789"))).append('"');
                }
                b.append(" />\n");
            }
            NodeList projects = d.getElementsByTagName("ProjectReference");
            for (int i=0; i<projects.getLength(); i++) {
                Element e=(Element)projects.item(i);
                String relative=e.getAttribute("Include");
                if(relative.contains("$(") || relative.isEmpty())continue;
                Path p=root.resolve(relative.replace('\\','/')).toAbsolutePath().normalize();
                if(Files.isRegularFile(p)) b.append("    <ProjectReference Include=\"").append(escape(p.toString())).append("\" />\n");
            }
            NodeList refs = d.getElementsByTagName("Reference");
            for (int i=0; i<refs.getLength(); i++) {
                Element e=(Element)refs.item(i);
                String include=e.getAttribute("Include");
                NodeList hints=e.getElementsByTagName("HintPath");
                if(include.isBlank() || hints.getLength()==0)continue;
                String hint=hints.item(0).getTextContent();
                if(hint.contains("$("))continue;
                Path resolved=root.resolve(hint.replace('\\','/')).toAbsolutePath().normalize();
                if(Files.isRegularFile(resolved)) b.append("    <Reference Include=\"").append(escape(include)).append("\"><HintPath>")
                        .append(escape(resolved.toString())).append("</HintPath></Reference>\n");
            }
            return b.toString();
        } catch (Exception ignored) { return ""; }
    }

    public static String plugin(ForgeSpec spec, String namespace, List<DependencyPlanner.Entry> loads,
                                List<DependencyPlanner.Entry> updates) {
        Map<String,String> instances = new LinkedHashMap<>();
        List<DependencyPlanner.Entry> entries = new ArrayList<>(loads);
        entries.addAll(updates);
        for (DependencyPlanner.Entry entry : entries)
            if (!entry.isStatic()) instances.putIfAbsent(entry.type(), "_component" + instances.size());
        boolean needsTicker = !updates.isEmpty() || spec.languageMode() == ForgeSpec.LanguageMode.EMBEDDED;
        StringBuilder code = new StringBuilder("// Generated by Astral Mod Forge 0.2.0 / 请检查启动调用顺序。\n" +
                "using System;\nusing BepInEx;\nusing BepInEx.Configuration;\nusing BepInEx.Logging;\n" +
                "using BepInEx.Unity.IL2CPP;\nusing UnityEngine;\n\n" +
                "namespace " + namespace + " {\n" +
                "    [BepInPlugin(\"" + spec.modId() + "\", \"" + spec.safeName() + "\", \"1.0.0\")]\n" +
                "    public sealed class Plugin : BasePlugin {\n" +
                "        internal static Plugin Instance = null!;\n" +
                "        internal static ManualLogSource ModLog = null!;\n");
        if (!updates.isEmpty()) code.append("        private static float _nextUpdateErrorLog;\n");
        for (var instance : instances.entrySet())
            code.append("        private static ").append(instance.getKey()).append(' ').append(instance.getValue()).append(" = null!;\n");
        code.append("        internal static void SaveSettings() { Instance.Config.Save(); }\n" +
                "        public override void Load() {\n" +
                "            Instance = this; ModLog = Log;\n" +
                "            ModLocalization.Initialize(Config, Log);\n" +
                "            Log.LogInfo(\"Loaded MOD: " + spec.modId() + "\");\n");
        for (var instance : instances.entrySet())
            code.append("            ").append(instance.getValue()).append(" = new ").append(instance.getKey()).append("();\n");
        for (DependencyPlanner.Entry entry : loads) {
            String target = entry.isStatic() ? entry.type() : instances.get(entry.type());
            code.append("            try { ").append(target).append('.').append(entry.method()).append("(); }\n")
                    .append("            catch(Exception ex) { Log.LogError(\"MOD Load entry failed: ").append(entry.type()).append("::")
                    .append(entry.method()).append(" : \" + ex); throw; }\n");
        }
        if (needsTicker) code.append("            AddComponent<ForgeTickBehaviour>();\n");
        code.append("        }\n");
        if (needsTicker) {
            code.append("        internal static void OnTick() {\n");
            if (spec.languageMode() == ForgeSpec.LanguageMode.EMBEDDED) code.append("            ModLocalization.Poll();\n");
            for (DependencyPlanner.Entry entry : updates) {
                String target = entry.isStatic() ? entry.type() : instances.get(entry.type());
                code.append("            try { ").append(target).append('.').append(entry.method()).append('(')
                        .append(entry.takesTime() ? "Time.realtimeSinceStartup" : "")
                        .append("); }\n")
                        .append("            catch(Exception ex) { if(Time.realtimeSinceStartup >= _nextUpdateErrorLog) {\n")
                        .append("                _nextUpdateErrorLog = Time.realtimeSinceStartup + 15f;\n")
                        .append("                ModLog.LogError(\"MOD Update entry failed: ").append(entry.type()).append("::")
                        .append(entry.method()).append(" : \" + ex); } }\n");
            }
            code.append("        }\n    }\n" +
                    "    // BepInEx registers this managed bridge with the IL2CPP runtime.\n" +
                    "    // 每帧调用均在 Unity 主线程执行。\n" +
                    "    public sealed class ForgeTickBehaviour : MonoBehaviour {\n" +
                    "        public ForgeTickBehaviour(IntPtr pointer) : base(pointer) {}\n" +
                    "        private void Update() { Plugin.OnTick(); }\n" +
                    "    }\n");
        } else code.append("    }\n");
        return code.append("}\n").toString();
    }

    public static String localization(ForgeSpec spec, String namespace) {
        String id=spec.modId();
        return "// 每个 MOD 拥有独立的内嵌词典和可编辑 JSON。\n" +
            "// Each MOD owns embedded defaults + editable overrides under BepInEx/config/<id>/lang.\n" +
            "using System;\nusing System.IO;\nusing System.Reflection;\nusing System.Collections.Generic;\n" +
            "using System.Text.Json;\nusing BepInEx;\nusing BepInEx.Configuration;\nusing BepInEx.Logging;\n" +
            "namespace "+namespace+" {\n" +
            "    internal static class ModLocalization {\n" +
            "        private const string Id = \""+id+"\";\n" +
            "        private static ConfigEntry<string>? _selected;\n" +
            "        private static ManualLogSource? _log;\n" +
            "        private static readonly Dictionary<string, Dictionary<string,string>> Cache = new();\n" +
            "        private static readonly Dictionary<string,DateTime> Stamps = new();\n" +
            "        private static float _next;\n" +
            "        internal static void Initialize(ConfigFile config, ManualLogSource log) {\n" +
            "            _log = log;\n" +
            "            _selected = config.Bind(\"Language\", \"Locale\", \"zh_cn\", \"zh_cn / en_us\");\n" +
            "            Reload(\"zh_cn\"); Reload(\"en_us\");\n" +
            "        }\n" +
            "        public static string T(string key) => Translate(key);\n" +
            "        public static string Translate(string key) {\n" +
            "            if (string.IsNullOrEmpty(key)) return key;\n" +
            "            string lang = _selected?.Value == \"en_us\" ? \"en_us\" : \"zh_cn\";\n" +
            "            if (Cache.TryGetValue(lang, out var data) && data.TryGetValue(key, out string? value)) return value;\n" +
            "            if (Cache.TryGetValue(\"zh_cn\", out var fallback) && fallback.TryGetValue(key, out string? zh)) return zh;\n" +
            "            return key;\n" +
            "        }\n" +
            "        internal static void Poll() {\n" +
            "            if (UnityEngine.Time.realtimeSinceStartup < _next) return;\n" +
            "            _next = UnityEngine.Time.realtimeSinceStartup + 3f;\n" +
            "            Reload(\"zh_cn\"); Reload(\"en_us\");\n" +
            "        }\n" +
            "        private static void Reload(string lang) {\n" +
            "            try {\n" +
            "                string dir = Path.Combine(Paths.ConfigPath, Id, \"lang\");\n" +
            "                Directory.CreateDirectory(dir);\n" +
            "                string file = Path.Combine(dir, lang + \".json\");\n" +
            "                DateTime stamp = File.Exists(file) ? File.GetLastWriteTimeUtc(file) : DateTime.MinValue;\n" +
            "                if (Stamps.TryGetValue(lang, out DateTime old) && old == stamp) return;\n" +
            "                var embedded = new Dictionary<string,string>();\n" +
            "                using (Stream? stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(Id + \".lang.\" + lang + \".json\")) {\n" +
            "                    if (stream != null) embedded = JsonSerializer.Deserialize<Dictionary<string,string>>(stream) ?? new();\n" +
            "                }\n" +
            "                if (!File.Exists(file)) { File.WriteAllText(file, JsonSerializer.Serialize(embedded, new JsonSerializerOptions { WriteIndented = true }));\n" +
            "                    stamp = File.GetLastWriteTimeUtc(file); }\n" +
            "                string edited = File.ReadAllText(file);\n" +
            "                var overrides = JsonSerializer.Deserialize<Dictionary<string,string>>(edited) ?? new();\n" +
            "                bool filledMissing = false;\n" +
            "                foreach (var item in embedded) if (!overrides.ContainsKey(item.Key)) { overrides[item.Key] = item.Value; filledMissing = true; }\n" +
            "                if (filledMissing) { File.WriteAllText(file, JsonSerializer.Serialize(overrides, new JsonSerializerOptions { WriteIndented = true }));\n" +
            "                    stamp = File.GetLastWriteTimeUtc(file); }\n" +
            "                foreach (var item in overrides) embedded[item.Key] = item.Value;\n" +
            "                Cache[lang] = embedded; Stamps[lang] = stamp;\n" +
            "            } catch(Exception ex) { _log?.LogWarning(\"Language reload skipped: \" + ex.Message); }\n" +
            "        }\n" +
            "    }\n" +
            "}\n";
    }

    public static String compatibility(String namespace) {
        return "// Compatibility shim / 兼容独立导出时保留的 ModLanguageService 翻译调用。\n" +
                "namespace "+namespace+".Framework.Localization {\n" +
                "    internal static class ModLanguageService {\n" +
                "        internal static string Translate(string key) => global::"+namespace+".ModLocalization.Translate(key);\n" +
                "        internal static string T(string key) => global::"+namespace+".ModLocalization.T(key);\n" +
                "    }\n}\n";
    }
}
