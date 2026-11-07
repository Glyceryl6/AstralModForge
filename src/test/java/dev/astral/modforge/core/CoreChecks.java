package dev.astral.modforge.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Self-contained offline regression suite / 不依赖 Rider SDK 或 .NET 的离线验证。 */
public final class CoreChecks {
    private static int passed;
    public static void main(String[] args) throws Exception {
        Path base=Files.createTempDirectory("astral-forge-tests-");
        try {
            check(JsonFlat.parse("{\"a\":\"中\\n文\",\"b\":\"\\u4e2d\"}").get("a").equals("中\n文"),"json escaping");
            boolean duplicate = false;
            try {JsonFlat.parse("{\"a\":\"1\",\"a\":\"2\"}");} catch(IOException ex){duplicate=true;}
            check(duplicate,"duplicate resource keys blocked");
            check(JsonFlat.parse(JsonFlat.write(Map.of("x","A\\B\"C\n"))).get("x").equals("A\\B\"C\n"),"roundtrip JSON");
            boolean invalid=false;
            try {new ForgeSpec(base,";evil",List.of(),null, ForgeSpec.LanguageMode.EMBEDDED,"zh_cn","","",false,false,null);}catch(IllegalArgumentException ex){invalid=true;}
            check(invalid,"mod ID sanitization");

            Path src=base.resolve("source");Files.createDirectories(src.resolve("Features"));
            Files.writeString(src.resolve("Source.csproj"),"<Project Sdk=\"Microsoft.NET.Sdk\"><ItemGroup><Compile Remove=\"Features/Ignore.cs\" /></ItemGroup></Project>");
            Files.createDirectories(src.resolve("localization-templates"));
            Files.writeString(src.resolve("localization-templates/zh_cn.json"),"{\"astralprobe.caption\":\"中文 \\n说明\",\"astralprobe.label\":\"标签\"}");
            Files.writeString(src.resolve("localization-templates/en_us.json"),"{\"astralprobe.caption\":\"English caption\",\"astralprobe.label\":\"Label\"}");
            Files.writeString(src.resolve("Plugin.cs"),"namespace Sample { public class Plugin {} }");
            Files.writeString(src.resolve("Features/Ignore.cs"),"namespace Sample { class IgnoredService {} }");
            Files.writeString(src.resolve("Features/Alpha.cs"),"""
                    using Sample;
                    namespace Sample.Features {
                      internal sealed partial class Alpha {
                        // Beta mentions a name in a comment: BogusClass
                        private readonly Beta _beta = new();
                        internal void Tick(float now) { var caption = ModLanguageService.Translate("astralprobe.caption"); }
                      }
                    }
                    """);
            Files.writeString(src.resolve("Features/Alpha.Part.cs"),"""
                    namespace Sample.Features { internal sealed partial class Alpha { internal void Helper() { } } }
                    """);
            Files.writeString(src.resolve("Features/Beta.cs"),"namespace Sample.Features { internal class Beta { } }");
            CSharpIndex index=new CSharpIndex(src);
            check(!index.units().containsKey(src.resolve("Features/Ignore.cs")),"msbuild compile excludes");
            check(CSharpIndex.maskLiteralsAndComments("// Beta\nvar q=\"Beta\"; Beta f;").contains("Beta f;"),"safe lexing");
            ForgeSpec embed=new ForgeSpec(src,"dev.test.alpha",List.of(Path.of("Features/Alpha.cs")),base.resolve("out"),
                    ForgeSpec.LanguageMode.EMBEDDED,"zh_cn","","",false,false,null);
            DependencyPlanner.Plan plan=new DependencyPlanner(index).plan(embed);
            check(plan.ready(),"dependency plan ready " + plan.errors());
            check(plan.sources().size()==3,"type closure + partial parts + referenced Beta: " + plan.sources().size());
            check(plan.updates().size()==1 && plan.updates().get(0).takesTime(),"automatic update binding");
            LocalizationTransformer.Result resources=new LocalizationTransformer().transform(src,embed,plan.sources());
            check(resources.errors().isEmpty(),"complete localization resources");
            check(resources.zh().get("dev.test.alpha.caption").equals("中文 \n说明"),"isolated translation keys");
            ForgeExporter.Result exp=new ForgeExporter().export(embed);
            check(Files.isRegularFile(exp.directory().resolve("Source/Features/Alpha.Part.cs")),"copied partial source");
            check(Files.isRegularFile(exp.directory().resolve("Source/Features/Beta.cs")),"copied transitive source");
            check(Files.readString(exp.directory().resolve("Plugin.cs")).contains(".Tick(Time.realtimeSinceStartup)"),"generated Update invocation");
            check(Files.readString(exp.directory().resolve("Source/Features/Alpha.cs")).contains("dev.test.alpha.caption"),"key rewritten");
            check(Files.readString(exp.directory().resolve("MOD.csproj")).contains("EmbeddedResource"),"embedded language resources");
            ForgeSpec inline=new ForgeSpec(src,"dev.test.fixed",List.of(Path.of("Features/Alpha.cs")),base.resolve("out"),
                    ForgeSpec.LanguageMode.INLINE,"en_us","","",false,false,null);
            ForgeExporter.Result fixed=new ForgeExporter().export(inline);
            check(Files.readString(fixed.directory().resolve("Source/Features/Alpha.cs")).contains("\"English caption\""),"inline text");

            Files.writeString(src.resolve("Features/Second.cs"),
                    "namespace Sample.Features { internal class Second { internal void Tick(float now) {} internal void Initialize() {} } }");
            ForgeSpec multi=new ForgeSpec(src,"dev.test.multi",List.of(Path.of("Features/Alpha.cs"),Path.of("Features/Second.cs")),
                    base.resolve("out"),ForgeSpec.LanguageMode.EMBEDDED,"zh_cn","","",false,false,null);
            DependencyPlanner.Plan multiPlan=new DependencyPlanner(new CSharpIndex(src)).plan(multi);
            check(multiPlan.updates().size()==2 && multiPlan.loads().size()==1, "multiple lifecycle entries");
            ForgeExporter.Result multiple=new ForgeExporter().export(multi);
            String pluginCode=Files.readString(multiple.directory().resolve("Plugin.cs"));
            check(pluginCode.contains("_component0.Tick(Time.realtimeSinceStartup)") &&
                  pluginCode.contains("_component1.Tick(Time.realtimeSinceStartup)"),"multiple generated Update calls");
            check(pluginCode.contains(".Initialize();"),"generated Load for selected class");
            check(Files.isRegularFile(multiple.directory().resolve("export-manifest.properties")), "reproducible export manifest");
            Files.writeString(src.resolve("Features/Dynamic.cs"),"namespace Sample.Features { class Dynamic { internal static void Initialize() { string key = \"x\"; string text = ModLanguageService.Translate(key); } } }");
            try {
                new ForgeExporter().export(new ForgeSpec(src,"dev.test.dynamic",List.of(Path.of("Features/Dynamic.cs")),base.resolve("out"),
                    ForgeSpec.LanguageMode.INLINE,"en_us","","",false,false,null));
                throw new AssertionError("dynamic localization should fail");
            } catch(IOException ok) {check(ok.getMessage().contains("动态"),"dynamic hardcode blocked");}
            Files.writeString(src.resolve("Features/Bad.cs"),"namespace Sample.Features { class Bad { internal static void Initialize() { Plugin.Secret(); } } }");
            try {
                new ForgeExporter().export(new ForgeSpec(src,"dev.test.bad",List.of(Path.of("Features/Bad.cs")),base.resolve("out"),
                    ForgeSpec.LanguageMode.EMBEDDED,"zh_cn","","",false,false,null));
                throw new AssertionError("unsupported Plugin API should fail");
            } catch(IOException ok) {check(ok.getMessage().contains("Plugin"),"host-only APIs blocked");}
            // v0.2.0: feature toggles are constant true; UI/config classes never enter the export.
            Files.writeString(src.resolve("Plugin.cs"), """
                    using BepInEx.Configuration;
                    namespace Sample { public class Plugin {
                      internal static ConfigEntry<bool> AutoPraisePlayers => throw new System.Exception();
                    } }
                    """);
            Files.createDirectories(src.resolve("Features/Configuration"));
            Files.createDirectories(src.resolve("Features/Settings"));
            Files.writeString(src.resolve("Features/Configuration/AutoConfiguration.cs"), """
                    namespace Sample.Features.Configuration {
                      class AutoConfiguration {
                        internal static ConfigEntry<bool> Enabled;
                      }
                    }
                    """);
            Files.writeString(src.resolve("Features/Settings/AutoSettingsGroup.cs"),
                    "namespace Sample.Features.Settings { class AutoSettingsGroup {} }");
            Files.writeString(src.resolve("Features/AutoSwitch.cs"), """
                    using Sample.Features.Configuration;
                    namespace Sample.Features {
                      internal class AutoSwitch {
                        internal void Tick(float now) {
                          if (!Plugin.AutoPraisePlayers.Value) return;
                          if (!AutoConfiguration.Enabled.Value) return;
                        }
                      }
                    }
                    """);
            CSharpIndex settingsIndex = new CSharpIndex(src);
            ClassSelectionModel switchTree = new ClassSelectionModel(settingsIndex);
            check(!switchTree.eligible().contains(src.resolve("Features/Settings/AutoSettingsGroup.cs")),
                    "host UI settings not selectable");
            check(!switchTree.eligible().contains(src.resolve("Features/Configuration/AutoConfiguration.cs")),
                    "host config not selectable");
            ForgeSpec auto = new ForgeSpec(src,"dev.test.auto",List.of(Path.of("Features/AutoSwitch.cs")),
                    base.resolve("out"),ForgeSpec.LanguageMode.EMBEDDED,"zh_cn","","",false,false,null,true);
            DependencyPlanner.Plan autoPlan = new DependencyPlanner(settingsIndex).plan(auto);
            check(autoPlan.ready(), "original Plugin getter config recognized: " + autoPlan.errors());
            check(autoPlan.sources().size() == 1, "settings and config not copied");
            String detached = autoPlan.rewrittenSources().get(src.resolve("Features/AutoSwitch.cs"));
            check(detached.contains("if (!true) return") && !detached.contains("AutoConfiguration"),
                    "both bool settings changed to true; discarded configuration using");
            ForgeExporter.Result autoExport = new ForgeExporter().export(auto);
            check(Files.readString(autoExport.directory().resolve("Source/Features/AutoSwitch.cs"))
                    .contains("!true"), "exported source switch always enabled");
            check(!Files.exists(autoExport.directory().resolve("Source/Features/Configuration")),
                    "no config source in exported project");
            check(!Files.readString(autoExport.directory().resolve("Plugin.cs")).contains("Config.Bind(\"ExportedFeature\""),
                    "no BepInEx toggle registration generated");

            Files.writeString(src.resolve("Features/WriteSwitch.cs"), """
                    namespace Sample.Features {
                      class WriteSwitch { internal static void Initialize() { Plugin.AutoPraisePlayers.Value = false; } }
                    }
                    """);
            ForgeSpec writeToggle = new ForgeSpec(src,"dev.test.write",List.of(Path.of("Features/WriteSwitch.cs")),
                    base.resolve("out"),ForgeSpec.LanguageMode.EMBEDDED,"zh_cn","","",false,false,null,true);
            check(!new DependencyPlanner(new CSharpIndex(src)).plan(writeToggle).ready(),
                    "writing host toggle must fail instead of silently rewriting");

            Files.writeString(src.resolve("Features/Helper.cs"),
                    "namespace Sample.Features { class Helper {} }");
            Files.writeString(src.resolve("Features/UsesHelper.cs"),
                    "namespace Sample.Features { class UsesHelper { Helper value; internal void Initialize() {} } }");
            Files.writeString(src.resolve("Features/UsesHelperAgain.cs"),
                    "namespace Sample.Features { class UsesHelperAgain { Helper value; internal void Initialize() {} } }");
            Files.writeString(src.resolve("Features/Standalone.cs"),
                    "namespace Sample.Features { class Standalone { internal void Initialize() {} } }");
            Files.writeString(src.resolve("Features/OuterType.cs"), """
                    namespace Sample.Features {
                      class OuterType { private struct PrivateColor {} }
                    }
                    """);
            Files.writeString(src.resolve("Features/OtherOuterType.cs"), """
                    namespace Sample.Features {
                      class OtherOuterType { private struct PrivateColor {} }
                    }
                    """);
            CSharpIndex nestedIndex = new CSharpIndex(src);
            check(!nestedIndex.unit(src.resolve("Features/OuterType.cs")).declared().contains("PrivateColor"),
                    "nested private C# types are not top-level candidates");
            ClassSelectionModel graph = new ClassSelectionModel(new CSharpIndex(src));
            Path helper = src.resolve("Features/Helper.cs");
            Path first = src.resolve("Features/UsesHelper.cs");
            Path second = src.resolve("Features/UsesHelperAgain.cs");
            Path standalone = src.resolve("Features/Standalone.cs");
            graph.checkAll(List.of(first, second, standalone));
            check(graph.checked().contains(helper), "checking features auto-checks dependency");
            check(graph.requested().size() == 3 && graph.checked().size() == 4,
                    "implicit dependencies are not manually selected entrypoints");
            graph.uncheck(helper);
            check(graph.checked().equals(Set.of(standalone)),
                    "unchecking dependency removes every dependent, retains unrelated feature");
            graph.check(first);
            check(graph.checked().contains(helper) && graph.checked().contains(first),
                    "rechecking feature restores required dependency");

            // Framework helpers automatically checked by the tree should be pruned if unused.
            // UI 中因 using 引入的框架类不应在导出的独立 MOD 中产生无用源码。
            Files.createDirectories(src.resolve("Framework/Helpers"));
            Files.writeString(src.resolve("Framework/Helpers/UnusedHelper.cs"),
                    "namespace Sample.Framework.Helpers { internal class UnusedHelper {} }");
            Files.writeString(src.resolve("Features/UnusedFrameworkImport.cs"), """
                    using Sample.Framework.Helpers;
                    namespace Sample.Features { class UnusedFrameworkImport {
                      internal static void Initialize() { System.Console.WriteLine("ready"); }
                    } }
                    """);
            Files.writeString(src.resolve("Framework/Helpers/NeededHelper.cs"),
                    "namespace Sample.Framework.Helpers { internal class NeededHelper { internal static void Run() {} } }");
            Files.writeString(src.resolve("Features/NeedsFramework.cs"), """
                    using Sample.Framework.Helpers;
                    namespace Sample.Features { class NeedsFramework {
                      internal static void Initialize() { NeededHelper.Run(); }
                    } }
                    """);
            CSharpIndex frameworkIndex = new CSharpIndex(src);
            ClassSelectionModel frameworkTree = new ClassSelectionModel(frameworkIndex);
            frameworkTree.checkAll(List.of(Path.of("Features/UnusedFrameworkImport.cs"),
                    Path.of("Framework/Helpers/UnusedHelper.cs")));
            check(frameworkTree.checked().contains(src.resolve("Framework/Helpers/UnusedHelper.cs")),
                    "checkbox tree can contain additional Framework helpers");
            ForgeSpec prunedFramework = new ForgeSpec(src, "dev.test.framework-prune",
                    List.of(Path.of("Features/UnusedFrameworkImport.cs"), Path.of("Framework/Helpers/UnusedHelper.cs")),
                    base.resolve("out"), ForgeSpec.LanguageMode.EMBEDDED, "zh_cn", "", "", false, false, null);
            DependencyPlanner.Plan prunedPlan = new DependencyPlanner(frameworkIndex).plan(prunedFramework);
            check(prunedPlan.ready() && prunedPlan.sources().stream().noneMatch(
                    u -> u.file().toString().contains("Framework/Helpers")),
                    "unused and explicitly checked Framework files removed from export");
            check(prunedPlan.warnings().stream().anyMatch(w -> w.contains("Pruned unused Framework")),
                    "preflight reports pruned Framework files");
            ForgeSpec requiredFramework = new ForgeSpec(src, "dev.test.framework-needed",
                    List.of(Path.of("Features/NeedsFramework.cs")),base.resolve("out"),
                    ForgeSpec.LanguageMode.EMBEDDED, "zh_cn", "", "", false, false, null);
            DependencyPlanner.Plan requiredPlan = new DependencyPlanner(frameworkIndex).plan(requiredFramework);
            check(requiredPlan.ready() && requiredPlan.sources().stream().anyMatch(
                    u -> u.file().equals(src.resolve("Framework/Helpers/NeededHelper.cs"))),
                    "Framework helper referenced by executable code is retained");

            Files.writeString(src.resolve("Framework/Helpers/StaticNeeded.cs"), """
                    namespace Sample.Framework.Helpers {
                      internal static class StaticNeeded { internal static void Run() {} }
                    }
                    """);
            Files.writeString(src.resolve("Features/UsesStaticFramework.cs"), """
                    using static Sample.Framework.Helpers.StaticNeeded;
                    namespace Sample.Features { class UsesStaticFramework {
                      internal static void Initialize() { Run(); }
                    } }
                    """);
            ForgeSpec staticFramework = new ForgeSpec(src,"dev.test.framework-static",
                    List.of(Path.of("Features/UsesStaticFramework.cs")),base.resolve("out"),
                    ForgeSpec.LanguageMode.EMBEDDED,"zh_cn","","",false,false,null);
            DependencyPlanner.Plan staticPlan = new DependencyPlanner(new CSharpIndex(src)).plan(staticFramework);
            check(staticPlan.ready() && staticPlan.sources().stream().anyMatch(
                    u -> u.file().equals(src.resolve("Framework/Helpers/StaticNeeded.cs"))),
                    "using static Framework helper is retained even without a qualified invocation");
            // v0.3.0: embed checked defaults into C# literals, including comma-separated fields.
            Files.writeString(src.resolve("Features/Configuration/DisplayConfiguration.cs"), """
                    using BepInEx.Configuration;
                    namespace Sample.Features.Configuration {
                      internal static class DisplayConfiguration {
                        internal static ConfigEntry<float> PanelWidth = null!, PanelHeight = null!;
                        internal static ConfigEntry<int> LabelSize = null!;
                        internal static ConfigEntry<string> LabelColor = null!;
                        internal static void Bind(ConfigFile config) {
                          PanelWidth = config.Bind("UI", "Width", 640f, new ConfigDescription("size", new AcceptableValueRange<float>(320f, 900f)));
                          PanelHeight = config.Bind("UI", "Height", -1f);
                          LabelSize = config.Bind("UI", "LabelSize", 32);
                          LabelColor = config.Bind("UI", "LabelColor", "ABCD12");
                        }
                      }
                    }
                    """);
            Files.writeString(src.resolve("Features/UsesDefaults.cs"), """
                    using Sample.Features.Configuration;
                    namespace Sample.Features {
                      class UsesDefaults { internal static void Initialize() {
                        float w = DisplayConfiguration.PanelWidth.Value;
                        float h = DisplayConfiguration.PanelHeight.Value;
                        int n = DisplayConfiguration.LabelSize.Value;
                        string c = DisplayConfiguration.LabelColor.Value;
                      } }
                    }
                    """);
            CSharpIndex configIndex = new CSharpIndex(src);
            ForgeSpec embedConfig = new ForgeSpec(src, "dev.test.embed-config", List.of(Path.of("Features/UsesDefaults.cs")),
                    base.resolve("out"),ForgeSpec.LanguageMode.EMBEDDED,"en_us","","",false,false,null,true,true);
            DependencyPlanner.Plan embedConfigPlan = new DependencyPlanner(configIndex).plan(embedConfig);
            check(embedConfigPlan.ready(),"embedded literal configuration should pass: " + embedConfigPlan.errors());
            String inlineDefaults = embedConfigPlan.rewrittenSources().get(src.resolve("Features/UsesDefaults.cs"));
            check(inlineDefaults.contains("w = 640f") && inlineDefaults.contains("h = -1f"),
                    "comma-separated float defaults replaced with literals");
            check(inlineDefaults.contains("n = 32") && inlineDefaults.contains("c = \"ABCD12\""),
                    "integer and string defaults replaced with literals");
            check(embedConfigPlan.sources().stream().noneMatch(u -> u.file().toString().contains("DisplayConfiguration.cs")),
                    "configuration class excluded when defaults are embedded");
            String generated = ModTemplates.plugin(embedConfig,"Sample",List.of(),List.of());
            String localization = ModTemplates.localization(embedConfig,"Sample");
            check(!generated.contains("Config.Save()") && generated.contains("ModLocalization.Initialize(Log)") &&
                    !generated.contains("Config.Bind") && !generated.contains("AddComponent<ForgeTickBehaviour>"),
                    "embedded-default plugin does not register or save configuration");
            check(localization.contains("GetManifestResourceStream") && localization.contains("Locale = \"en_us\"") &&
                    !localization.contains("WriteAllText") && !localization.contains("CreateDirectory") &&
                    !localization.contains("Config.Bind"),
                    "embedded language reads compiled JSON without creating config directories");
            ForgeExporter.Result embeddedExport = new ForgeExporter().export(embedConfig);
            check(Files.readString(embeddedExport.directory().resolve("export-manifest.properties"))
                    .contains("embeddedDefaults=true"), "embedded config policy recorded in manifest");
            check(Files.readString(embeddedExport.directory().resolve("MOD.csproj"))
                    .contains("EmbeddedResource Include=\"lang/en_us.json\""),
                    "embedded resources included in exported C# assembly");
            ForgeSpec persistentConfig = new ForgeSpec(src,"dev.test.embed-off", List.of(Path.of("Features/UsesDefaults.cs")),
                    base.resolve("out"), ForgeSpec.LanguageMode.EMBEDDED,"zh_cn","","",false,false,null,true,false);
            check(!new DependencyPlanner(configIndex).plan(persistentConfig).ready(),
                    "non-boolean configuration remains unsupported without embed option");
            Files.writeString(src.resolve("Features/WritesConfig.cs"),"""
                    namespace Sample.Features { class WritesConfig { internal static void Initialize() {
                      var x = new BepInEx.Configuration.ConfigFile("generated.cfg", true);
                    } } }
                    """);
            ForgeSpec writesFile = new ForgeSpec(src,"dev.test.config-write",List.of(Path.of("Features/WritesConfig.cs")),
                    base.resolve("out"),ForgeSpec.LanguageMode.EMBEDDED,"zh_cn","","",false,false,null,true,true);
            check(new DependencyPlanner(new CSharpIndex(src)).plan(writesFile).errors().stream()
                    .anyMatch(x -> x.contains("Persistent configuration")), "known runtime configuration writes blocked");
            Files.writeString(src.resolve("Features/Configuration/DisplayConfiguration.cs"),
                    Files.readString(src.resolve("Features/Configuration/DisplayConfiguration.cs"))
                            .replace("PanelWidth = config.Bind(\"UI\", \"Width\", 640f,", "PanelWidth = config.Bind(\"UI\", \"Width\", GetDefault(),"));
            check(!new DependencyPlanner(new CSharpIndex(src)).plan(embedConfig).ready(),
                    "complex non-literal defaults rejected instead of guessing");

            boolean protectedOutput = false;
            try { new ForgeSpec(src,"dev.test.badpath",List.of(Path.of("Features/AutoSwitch.cs")),
                    src.resolve("local-output"),ForgeSpec.LanguageMode.EMBEDDED,"zh_cn","","",true,false,null,true); }
            catch (IllegalArgumentException ok) { protectedOutput = true; }
            check(protectedOutput,"source export blocked under active .csproj root");
            ForgeSpec noSource = new ForgeSpec(src,"dev.test.auto",List.of(Path.of("Features/AutoSwitch.cs")),
                    null,ForgeSpec.LanguageMode.EMBEDDED,"zh_cn","","",true,false,null,false);
            check(noSource.destination().startsWith(src) && !noSource.exportSource(),
                    "DLL-only export uses controlled in-project artifacts folder");
            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
                Path fakeSdk = base.resolve("fake-dotnet.sh");
                Files.writeString(fakeSdk, """
                        #!/bin/sh
                        if [ "$1" = "--version" ]; then echo 8.0.0; exit 0; fi
                        mkdir -p bin/Release/net6.0
                        printf fake > bin/Release/net6.0/dev_test_auto.dll
                        echo MockBuild succeeded
                        """);
                fakeSdk.toFile().setExecutable(true);
                System.setProperty("astral.modforge.dotnet", fakeSdk.toString());
                try {
                    List<String> events = new ArrayList<>();
                    ForgeExporter.Result built = new ForgeExporter().export(noSource,
                            (kind, message) -> events.add(kind + ":" + message));
                    check(built.compiled() && Files.isRegularFile(built.dll()),
                            "mock DLL-only build preserves dist DLL");
                    try(Stream<Path> paths = Files.walk(built.directory())) {
                        check(paths.noneMatch(p -> p.toString().endsWith(".cs") || p.toString().endsWith(".csproj")),
                                "no generated C# source survives DLL-only build");
                    }
                    check(events.stream().anyMatch(x -> x.contains("MockBuild succeeded")),
                            "live build output observer receives compiler lines");
                    check(Files.readString(built.directory().resolve("build.log")).contains("MockBuild succeeded"),
                            "complete build log retained alongside dist and report");
                } finally { System.clearProperty("astral.modforge.dotnet"); }
            }

            ForgeExporter.Result fresh=new ForgeExporter().createEmpty(base,"dev.test.blank");
            check(Files.isRegularFile(fresh.directory().resolve("dev_test_blank.csproj")),"new standalone project .csproj");
            check(Files.readString(fresh.directory().resolve("Features/SampleFeature.cs")).contains("Initialize()"),"new project activation");
            StringBuilder reason=new StringBuilder();check(!GameInstaller.valid(base,reason),"invalid game directory blocked");
            Path game=base.resolve("game");Files.createDirectories(game.resolve("BepInEx/plugins"));Files.createDirectories(game.resolve("BepInEx/core"));
            Files.createDirectories(game.resolve("AstralParty_Data"));
            check(GameInstaller.valid(game,new StringBuilder()),"valid BepInEx game layout");
            Path distribution=base.resolve("dist");Files.createDirectories(distribution);Files.writeString(distribution.resolve("dev_test_alpha.dll"),"hello");
            GameInstaller.copy(game,"dev_test_alpha",distribution,new ArrayList<>());
            check(Files.isRegularFile(game.resolve("BepInEx/plugins/dev_test_alpha/dev_test_alpha.dll")),"safe game copy");
            GameInstaller.copy(game,"dev_test_alpha",distribution,new ArrayList<>());
            try(Stream<Path> content=Files.list(game.resolve("BepInEx/plugins/dev_test_alpha"))) {
                check(content.anyMatch(p->p.getFileName().toString().contains("backup-")),"old DLL backup");
            }
            System.out.println("CoreChecks PASS: " + passed + " checks / 检查全部通过");
        } finally { try(Stream<Path> paths=Files.walk(base)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);} }
    }
    private static void check(boolean good, String name) {if(!good)throw new AssertionError("FAIL: "+name);passed++;}
}
