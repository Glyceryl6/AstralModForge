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
            check(ForgeText.select("检查依赖 / Resolving dependencies", "zh_CN").equals("检查依赖"),
                    "Chinese export log language");
            check(ForgeText.select("检查依赖 / Resolving dependencies", "en_US").equals("Resolving dependencies"),
                    "English export log language");
            ForgeSpec embed=new ForgeSpec(src,"dev.test.alpha",List.of(Path.of("Features/Alpha.cs")),base.resolve("out"),
                    ForgeSpec.LanguageMode.EMBEDDED,"zh_cn","","",false,false,null);
            DependencyPlanner.Plan plan=new DependencyPlanner(index).plan(embed);
            check(plan.ready(),"dependency plan ready " + plan.errors());
            check(plan.sources().size()==3,"type closure + partial parts + referenced Beta: " + plan.sources().size());
            check(plan.updates().size()==1 && plan.updates().get(0).takesTime(),"automatic update binding");
            LocalizationTransformer.Result resources=new LocalizationTransformer().transform(src,embed,plan.sources());
            check(resources.errors().isEmpty(),"complete localization resources");
            check(resources.zh().get("dev.test.alpha.caption").equals("中文 \n说明"),"isolated translation keys");
            check(resources.zh().size() == 1 && resources.en().size() == 1,
                    "only referenced literal translation key is embedded");
            check(embed.assemblyName().equals("Dev.Test.Alpha") &&
                    new ForgeSpec(src, "dev.custom.some-mod", List.of(), base.resolve("out"),
                            ForgeSpec.LanguageMode.EMBEDDED, "zh_cn", "", "", false, false, null)
                            .assemblyName().equals("Dev.Custom.SomeMod"),
                    "assembly names use readable dot-separated names instead of underscores");

            Files.writeString(src.resolve("localization-templates/zh_cn.json"), """
                    {"astralprobe.caption":"中文说明","astralprobe.label":"标签",
                     "astralprobe.marks.self":"自己的标记", "astralprobe.marks.other":"另一种标记",
                     "astralprobe.unused":"其它功能"}
                    """);
            Files.writeString(src.resolve("localization-templates/en_us.json"), """
                    {"astralprobe.caption":"English caption","astralprobe.label":"Label",
                     "astralprobe.marks.self":"Self mark", "astralprobe.marks.other":"Other mark",
                     "astralprobe.unused":"Unrelated feature"}
                    """);
            Files.writeString(src.resolve("Features/DynamicMarks.cs"), """
                    namespace Sample.Features {
                        class DynamicMarks {
                            private readonly struct Choice {
                                internal readonly string NameKey;
                                internal Choice(string key) { NameKey = key; }
                            }
                            private static readonly Choice[] Choices = {
                                new("astralprobe.marks.self"),
                                new("astralprobe.marks.other")
                            };
                            internal void Initialize() {
                                var choice = Choices[0];
                                var label = ModLocalization.Translate(choice.NameKey);
                            }
                        }
                    }
                    """);
            ForgeSpec marksSpec = new ForgeSpec(src,"dev.test.marks",
                    List.of(Path.of("Features/DynamicMarks.cs")),base.resolve("out"),
                    ForgeSpec.LanguageMode.EMBEDDED,"zh_cn","","",false,false,null);
            ForgeExporter.Result marks = new ForgeExporter().export(marksSpec);
            Map<String, String> markTranslations = JsonFlat.parse(
                    Files.readString(marks.directory().resolve("lang/zh_cn.json")));
            check(markTranslations.size() == 2 && markTranslations.containsKey("astralprobe.marks.self") &&
                    !markTranslations.containsKey("astralprobe.unused"),
                    "dynamic NameKey list collects only keys present in selected source");
            check(!Files.readString(marks.directory().resolve("lang/en_us.json")).contains("Unrelated feature"),
                    "irrelevant localization never enters exported JSON");
            check(Files.readString(marks.directory().resolve("export-report.md")).contains("zh_cn: 2 keys"),
                    "export report includes exact localization count");

            Files.writeString(src.resolve("Features/UnknownDynamic.cs"), """
                    namespace Sample.Features {
                        class UnknownDynamic {
                            internal static string GetKey() => "unrelated";
                            internal void Initialize() {
                                string key = GetKey();
                                var label = ModLocalization.Translate(key);
                            }
                        }
                    }
                    """);
            ForgeSpec unknownSpec = new ForgeSpec(src,"dev.test.unknown",
                    List.of(Path.of("Features/UnknownDynamic.cs")), base.resolve("out"),
                    ForgeSpec.LanguageMode.EMBEDDED,"zh_cn","","",false,false,null);
            try {
                new ForgeExporter().export(unknownSpec);
                throw new AssertionError("unresolved dynamic keys must fail preflight");
            } catch (IOException expected) {
                check(expected.getMessage().contains("localization-keys.txt"),
                        "unknown dynamic translations fail without embedding the entire dictionary");
            }
            Files.writeString(src.resolve("localization-keys.txt"), "astralprobe.marks.*\n");
            ForgeExporter.Result scoped = new ForgeExporter().export(unknownSpec);
            check(JsonFlat.parse(Files.readString(scoped.directory().resolve("lang/zh_cn.json"))).size() == 2,
                    "manifest can explicitly scope otherwise-unresolved dynamic keys");
            Files.delete(src.resolve("localization-keys.txt"));

            ForgeExporter.Result exp=new ForgeExporter().export(embed);
            check(Files.isRegularFile(exp.directory().resolve("Source/Features/Alpha.Part.cs")),"copied partial source");
            check(Files.isRegularFile(exp.directory().resolve("Source/Features/Beta.cs")),"copied transitive source");
            check(Files.readString(exp.directory().resolve("Plugin.cs")).contains(".Tick(Time.realtimeSinceStartup)"),"generated Update invocation");
            check(Files.readString(exp.directory().resolve("Source/Features/Alpha.cs")).contains("dev.test.alpha.caption"),"key rewritten");
            check(Files.readString(exp.directory().resolve("MOD.csproj")).contains("EmbeddedResource"),"embedded language resources");
            check(Files.readString(exp.directory().resolve("MOD.csproj")).contains("<AssemblyName>Dev.Test.Alpha</AssemblyName>"),
                    "MSBuild AssemblyName matches the readable DLL name");
            ForgeSpec inline=new ForgeSpec(src,"dev.test.fixed",List.of(Path.of("Features/Alpha.cs")),base.resolve("out"),
                    ForgeSpec.LanguageMode.INLINE,"en_us","","",false,false,null);
            ForgeExporter.Result fixed=new ForgeExporter().export(inline);
            check(Files.readString(fixed.directory().resolve("Source/Features/Alpha.cs")).contains("\"English caption\""),"inline text");
            check(!Files.exists(fixed.directory().resolve("lang/zh_cn.json")) &&
                    !Files.readString(fixed.directory().resolve("MOD.csproj")).contains("EmbeddedResource Include=\"lang/"),
                    "fixed-text export embeds no JSON dictionaries");

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
            // v0.5.0: method-level dependency pruning keeps only the static helper call chain.
            Path methodSrc = base.resolve("method-source");
            Files.createDirectories(methodSrc.resolve("Features"));
            Files.createDirectories(methodSrc.resolve("Framework/Interop"));
            Files.createDirectories(methodSrc.resolve("Framework/UI"));
            Files.createDirectories(methodSrc.resolve("Framework/Settings"));
            Files.writeString(methodSrc.resolve("Source.csproj"), "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>net6.0</TargetFramework></PropertyGroup></Project>");
            Files.writeString(methodSrc.resolve("Plugin.cs"), "namespace MethodSample { public class Plugin {} }");
            Files.writeString(methodSrc.resolve("Framework/Settings/ModSettingsLayout.cs"), """
                    namespace MethodSample.Framework.Settings {
                      internal static class ModSettingsLayout {
                        private static readonly int[] Options = {
                          I("HomeTextColor", 16761384, 0, 16777215),
                          I("ActionButtonFontSize", 20, 8, 48)
                        };
                        private static int I(string key, int value, int min, int max) => value;
                        private static float Number(string key) => 0;
                        internal static uint HomeTextColor => (uint)Number(nameof(HomeTextColor));
                        internal static int ActionButtonFontSize => (int)Number(nameof(ActionButtonFontSize));
                      }
                    }
                    """);
            Files.writeString(methodSrc.resolve("Features/MarksService.cs"), """
                    using MethodSample.Framework.Interop;
                    using MethodSample.Framework.Settings;
                    namespace MethodSample.Features {
                      internal sealed class MarksService {
                        internal void Initialize() {
                          NativeActionButton.StretchNativeArtwork();
                          uint color = ModSettingsLayout.HomeTextColor;
                          OpaqueStaticHelper.Used();
                        }
                      }
                    }
                    """);
            Files.writeString(methodSrc.resolve("Framework/Interop/NativeActionButton.cs"), """
                    using MethodSample.Framework.Settings;
                    using MethodSample.Framework.UI;
                    using static MethodSample.Framework.UI.StableNativeAction;
                    using ActionTools = MethodSample.Framework.UI.StableNativeAction;
                    namespace MethodSample.Framework.Interop {
                      internal static class NativeActionButton {
                        internal static void StretchNativeArtwork() {
                          Scale();
                          ActionTools.Scale();
                        }
                        internal static void Create() {
                          NativeColor color = new NativeColor();
                          int size = ModSettingsLayout.ActionButtonFontSize;
                          StableNativeAction.Prepare();
                        }
                        private struct NativeColor { internal int Value; }
                      }
                    }
                    """);
            Files.writeString(methodSrc.resolve("Framework/UI/StableNativeAction.cs"), """
                    namespace MethodSample.Framework.UI {
                      internal static class StableNativeAction {
                        internal static void Scale() { }
                        internal static void Prepare() { NativeMailActionButton.DetachSizeGears(); }
                      }
                    }
                    """);
            Files.writeString(methodSrc.resolve("Framework/UI/OpaqueStaticHelper.cs"), """
                    namespace MethodSample.Framework.UI {
                      internal static class OpaqueStaticHelper {
                        private static readonly int State = new System.Random().Next();
                        internal static void Used() { int state = State; }
                        internal static void Unused() {
                          int size = MethodSample.Framework.Settings.ModSettingsLayout.ActionButtonFontSize;
                        }
                      }
                    }
                    """);
            Files.writeString(methodSrc.resolve("Framework/UI/NativeMailActionButton.cs"), """
                    using MethodSample.Framework.Settings;
                    namespace MethodSample.Framework.UI {
                      internal static class NativeMailActionButton {
                        private static readonly int Package = new System.Random().Next();
                        internal static void DetachSizeGears() { Fit(); }
                        private static void Fit() { int size = ModSettingsLayout.ActionButtonFontSize; }
                      }
                    }
                    """);
            CSharpIndex methodIndex = new CSharpIndex(methodSrc);
            ForgeSpec methodSpec = new ForgeSpec(methodSrc, "dev.test.method-prune",
                    List.of(Path.of("Features/MarksService.cs")), base.resolve("out"),
                    ForgeSpec.LanguageMode.EMBEDDED, "zh_cn", "", "", false, false, null, true, false);
            DependencyPlanner.Plan methodPlan = new DependencyPlanner(methodIndex).plan(methodSpec);
            check(methodPlan.ready(), "static method chain can be exported without host settings: " + methodPlan.errors());
            String nativeActionSource = methodPlan.rewrittenSources().get(methodSrc.resolve("Framework/Interop/NativeActionButton.cs"));
            String stableActionSource = methodPlan.rewrittenSources().get(methodSrc.resolve("Framework/UI/StableNativeAction.cs"));
            check(nativeActionSource != null && nativeActionSource.contains("StretchNativeArtwork") &&
                    !nativeActionSource.contains("void Create") && !nativeActionSource.contains("ModSettingsLayout") &&
                    !nativeActionSource.contains("NativeColor"),
                    "unused static methods and private nested types removed from NativeActionButton");
            check(stableActionSource != null && stableActionSource.contains("void Scale") &&
                    !stableActionSource.contains("void Prepare"),
                    "transitive static helper method retained while unrelated method is removed");
            String opaqueHelperSource = methodPlan.rewrittenSources().get(methodSrc.resolve("Framework/UI/OpaqueStaticHelper.cs"));
            check(opaqueHelperSource != null && opaqueHelperSource.contains("void Used") &&
                    opaqueHelperSource.contains("void Unused"),
                    "static helper with stateful initialization is kept intact");
            check(methodPlan.sources().stream().noneMatch(unit ->
                    unit.file().getFileName().toString().equals("NativeMailActionButton.cs") ||
                            unit.file().getFileName().toString().equals("ModSettingsLayout.cs")),
                    "unused static dependency and host settings class excluded");
            check(methodPlan.rewrittenSources().get(methodSrc.resolve("Features/MarksService.cs"))
                    .contains("16761384u"), "host layout property replaced with its declared default");

            Files.writeString(src.resolve("Features/Dynamic.cs"),"namespace Sample.Features { class Dynamic { internal static void Initialize() { string key = \"x\"; string text = ModLanguageService.Translate(key); } } }");
            try {
                new ForgeExporter().export(new ForgeSpec(src,"dev.test.dynamic",List.of(Path.of("Features/Dynamic.cs")),base.resolve("out"),
                    ForgeSpec.LanguageMode.INLINE,"en_us","","",false,false,null));
                throw new AssertionError("dynamic localization should fail");
            } catch(IOException ok) {check(ok.getMessage().contains("动态") || ok.getMessage().contains("Dynamic translation"),
                    "dynamic hardcode blocked in selected log language");}
            List<String> localizedErrors = new ArrayList<>();
            try {
                new ForgeExporter().export(new ForgeSpec(src,"dev.test.dynamic.zh",List.of(Path.of("Features/Dynamic.cs")),base.resolve("out"),
                        ForgeSpec.LanguageMode.INLINE,"en_us","","",false,false,null), new ExportObserver() {
                    @Override
                    public void onEvent(String kind, String message) {
                        localizedErrors.add(message);
                    }

                    @Override
                    public String language() {
                        return "zh_CN";
                    }
                });
                throw new AssertionError("localized dynamic localization should fail");
            } catch (IOException expected) {
                check(expected.getMessage().contains("预检查失败") && !expected.getMessage().contains("Preflight failed"),
                        "preflight exception follows selected language");
            }
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
            // The generated project needs the same IL2CPP proxy references as the host,
            // but never copies the host's PluginInfoProps package or settings targets.
            // 测试缺失的游戏代理引用及 BepInEx 独立 NuGet 源。
            Path interop = src.resolve("lib/interop");
            Files.createDirectories(interop);
            for (String name : List.of("Il2Cppmscorlib", "UnityEngine.CoreModule",
                    "UnityEngine.InputLegacyModule", "UnityEngine.TextRenderingModule", "Assembly-CSharp")) {
                Files.writeString(interop.resolve(name + ".dll"), "test proxy");
            }
            StandaloneBuildSupport.Plan existing = new StandaloneBuildSupport().plan(noSource);
            check(existing.interopDir() != null && existing.interopDir().equals(interop),
                    "recognizes existing game interop proxy directory");
            check(existing.references().contains("<Reference Include=\"UnityEngine.CoreModule\"")
                    && existing.references().contains("<Reference Include=\"Assembly-CSharp\"")
                    && existing.references().contains("<Private>false</Private>"),
                    "references explicit Unity and game assemblies without copying them");
            check(existing.nugetConfig().contains("https://nuget.bepinex.dev/v3/index.json")
                    && existing.nugetConfig().contains("https://api.nuget.org/v3/index.json"),
                    "export restores packages from independent NuGet feeds");

            Path linked = base.resolve("linked");
            Files.createDirectories(linked);
            Path sdkProject = base.resolve("IndependentSdk/IndependentSdk.csproj");
            Files.createDirectories(sdkProject.getParent());
            Files.writeString(sdkProject, "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup>"
                    + "<TargetFramework>net6.0</TargetFramework></PropertyGroup></Project>");
            Files.writeString(sdkProject.getParent().resolve("Code.cs"),
                    "namespace IndependentSdk { public class Code {} }");
            Files.writeString(linked.resolve("Linked.csproj"), """
                    <Project Sdk="Microsoft.NET.Sdk"><PropertyGroup>
                      <TargetFramework>net6.0</TargetFramework>
                      <AstralBepInExIl2CppPackageVersion>6.0.0-be.789</AstralBepInExIl2CppPackageVersion>
                    </PropertyGroup><ItemGroup>
                      <PackageReference Include="BepInEx.PluginInfoProps" Version="2.*" />
                      <PackageReference Include="BepInEx.Unity.IL2CPP" Version="[$(AstralBepInExIl2CppPackageVersion)]" IncludeAssets="compile" />
                      <ProjectReference Include="../IndependentSdk/IndependentSdk.csproj" />
                    </ItemGroup></Project>
                    """);
            Files.writeString(linked.resolve("NuGet.Config"), """
                    <configuration><packageSources><clear />
                    <add key="extra" value="https://packages.example.com/v3/index.json" />
                    </packageSources><packageSourceCredentials><account><add key="ClearTextPassword" value="SECRET" /></account></packageSourceCredentials>
                    </configuration>
                    """);
            ForgeSpec linkedSpec = new ForgeSpec(linked, "dev.test.linked", List.of(), base.resolve("out"),
                    ForgeSpec.LanguageMode.EMBEDDED, "zh_cn", "", "", false, false, null);
            StandaloneBuildSupport.Plan linkedPlan = new StandaloneBuildSupport().plan(linkedSpec);
            String linkedProject = ModTemplates.csproj(linkedSpec, linkedPlan.references());
            check(!linkedProject.contains("PluginInfoProps") && linkedProject.contains("BepInEx.Unity.IL2CPP")
                    && !linkedProject.contains("$(AstralBepInExIl2CppPackageVersion)"),
                    "host-only PluginInfoProps removed, original pinned BepInEx version resolved");
            check(linkedPlan.nugetConfig().contains("packages.example.com")
                    && !linkedPlan.nugetConfig().contains("SECRET")
                    && linkedPlan.nugetConfig().contains("nuget.bepinex.dev"),
                    "source NuGet feeds retained without copying credentials");
            check(linkedProject.contains("Dependencies/Projects/IndependentSdk/IndependentSdk.csproj")
                    && !linkedProject.contains(sdkProject.toString())
                    && linkedProject.contains("<DefaultItemExcludes>"),
                    "SDK reference is portable and host excludes the SDK's sources");
            check(linkedPlan.filesToCopy().containsKey("Dependencies/Projects/IndependentSdk/Code.cs"),
                    "standalone SDK copy includes referenced project sources");

            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
                Path fakeSdk = base.resolve("fake-dotnet.sh");
                Files.writeString(fakeSdk, """
                        #!/bin/sh
                        if [ "$1" = "--version" ]; then echo 8.0.0; exit 0; fi
                        if [ "$1" = "restore" ]; then
                          echo "$@" | grep -q -- '--configfile' || exit 40
                          test -f NuGet.Config || exit 41
                          echo MockRestore succeeded
                          exit 0
                        fi
                        if [ "$1" = "build" ]; then
                          echo "$@" | grep -q -- '--no-restore' || exit 42
                          mkdir -p bin/Release/net6.0
                          printf fake > bin/Release/net6.0/Dev.Test.Auto.dll
                          echo MockBuild succeeded
                          exit 0
                        fi
                        exit 43
                        """);
                fakeSdk.toFile().setExecutable(true);
                System.setProperty("astral.modforge.dotnet", fakeSdk.toString());
                try {
                    List<String> events = new ArrayList<>();
                    ExportObserver chineseObserver = new ExportObserver() {
                        @Override
                        public void onEvent(String kind, String message) {
                            events.add(kind + ":" + message);
                        }

                        @Override
                        public String language() {
                            return "zh_CN";
                        }
                    };
                    ForgeExporter.Result built = new ForgeExporter().export(noSource, chineseObserver);
                    check(built.compiled() && Files.isRegularFile(built.dll()),
                            "mock DLL-only build preserves dist DLL");
                    try(Stream<Path> paths = Files.walk(built.directory())) {
                        check(paths.noneMatch(p -> p.toString().endsWith(".cs") || p.toString().endsWith(".csproj")),
                                "no generated C# source survives DLL-only build");
                    }
                    check(events.stream().anyMatch(x -> x.contains("MockBuild succeeded")),
                            "live build output observer receives compiler lines");
                    check(events.contains("phase:检查依赖") && events.stream().noneMatch(x -> x.contains("Resolving dependencies")),
                            "export progress messages follow the IDE language without bilingual duplicates");
                    String buildLog = Files.readString(built.directory().resolve("build.log"));
                    check(buildLog.contains("MockRestore succeeded")
                                    && buildLog.contains("MockBuild succeeded")
                                    && buildLog.indexOf("MockRestore succeeded") < buildLog.indexOf("MockBuild succeeded"),
                            "complete restore and build log retained in execution order");
                } finally { System.clearProperty("astral.modforge.dotnet"); }
            }

            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
                Path failDotnet = base.resolve("fake-dotnet-restore-failure.sh");
                Files.writeString(failDotnet, """
                        #!/bin/sh
                        if [ "$1" = "--version" ]; then echo 8.0.0; exit 0; fi
                        if [ "$1" = "restore" ]; then echo "NU1101: package is missing"; exit 37; fi
                        echo "ERROR: should never run build after failed restore"
                        exit 38
                        """);
                failDotnet.toFile().setExecutable(true);
                ForgeSpec failing = new ForgeSpec(src, "dev.test.restore-error",
                        List.of(Path.of("Features/AutoSwitch.cs")), base.resolve("failed-out"),
                        ForgeSpec.LanguageMode.EMBEDDED, "zh_cn", "", "", true, false, null, true, false);
                System.setProperty("astral.modforge.dotnet", failDotnet.toString());
                boolean failedClearly = false;
                try {
                    new ForgeExporter().export(failing);
                } catch (IOException expected) {
                    failedClearly = expected.getMessage().contains("NuGet");
                } finally {
                    System.clearProperty("astral.modforge.dotnet");
                }
                check(failedClearly, "NuGet failure throws instead of returning a successful export");
                ForgeSpec failingDllOnly = new ForgeSpec(src, "dev.test.restore-no-source",
                        List.of(Path.of("Features/AutoSwitch.cs")), base.resolve("failed-dll-only"),
                        ForgeSpec.LanguageMode.EMBEDDED, "zh_cn", "", "", true, false, null, false, false);
                System.setProperty("astral.modforge.dotnet", failDotnet.toString());
                boolean dllOnlyFails = false;
                try {
                    new ForgeExporter().export(failingDllOnly);
                } catch (IOException expected) {
                    dllOnlyFails = expected.getMessage().contains("NuGet");
                } finally {
                    System.clearProperty("astral.modforge.dotnet");
                }
                check(dllOnlyFails, "DLL-only exports also fail immediately after unsuccessful restore");
                try (Stream<Path> files = Files.walk(failingDllOnly.destination().resolve("Exports"))) {
                    Set<String> names = files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
                    check(names.contains("build.log") && names.contains("export-report.md")
                                    && !names.contains("MOD.csproj") && !names.contains("Plugin.cs"),
                            "DLL-only failure retains diagnostics but cleans temporary C# sources");
                }

                Path failedExports = failing.destination().resolve("Exports");
                try (Stream<Path> exported = Files.list(failedExports)) {
                    Path failedDir = exported.findFirst().orElseThrow();
                    check(Files.readString(failedDir.resolve("build.log")).contains("NU1101")
                                    && Files.readString(failedDir.resolve("export-report.md")).contains("NuGet")
                                    && !Files.exists(failedDir.resolve("dist/Dev.Test.RestoreError.dll")),
                            "failed restore retains build log/report and never deploys a nonexistent DLL");
                }
            }

            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
                Path fakeBuild = base.resolve("fake-dotnet-no-dll.sh");
                Files.writeString(fakeBuild, """
                        #!/bin/sh
                        if [ "$1" = "--version" ]; then echo 8.0.0; exit 0; fi
                        if [ "$1" = "restore" ]; then echo Restored; exit 0; fi
                        if [ "$1" = "build" ]; then echo BuildSucceededButNoArtifact; exit 0; fi
                        exit 99
                        """);
                fakeBuild.toFile().setExecutable(true);
                ForgeSpec missingDll = new ForgeSpec(src, "dev.test.no-artifact",
                        List.of(Path.of("Features/AutoSwitch.cs")), base.resolve("no-dll-output"),
                        ForgeSpec.LanguageMode.EMBEDDED, "zh_cn", "", "", true, false, null, true, false);
                System.setProperty("astral.modforge.dotnet", fakeBuild.toString());
                boolean error = false;
                try {
                    new ForgeExporter().export(missingDll);
                } catch (IOException expected) {
                    error = expected.getMessage().contains("DLL");
                } finally {
                    System.clearProperty("astral.modforge.dotnet");
                }
                check(error, "dotnet exit=0 without a DLL is not misreported as successful compilation");
            }

            ForgeExporter.Result fresh=new ForgeExporter().createEmpty(base,"dev.test.blank");
            check(Files.isRegularFile(fresh.directory().resolve("dev_test_blank.csproj")),"new standalone project .csproj");
            check(Files.isRegularFile(fresh.directory().resolve("ForgePaths.props"))
                    && Files.isRegularFile(fresh.directory().resolve("NuGet.Config")),
                    "new project includes editable proxy paths and NuGet sources");
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
            // Explicit project service registration prevents Project.getService() from returning null.
            // plugin.xml 注册与前端空值保护必须同时存在。
            String descriptor = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"));
            check(descriptor.contains("<projectService serviceImplementation=\"dev.astral.modforge.ide.BuildLogService\""),
                    "BuildLogService registered as a project service");
            check(descriptor.contains("id=\"dev.astral.modforge.ExportContext\"")
                            && descriptor.contains("group-id=\"EditorPopupMenu\"")
                            && descriptor.contains("group-id=\"ProjectViewPopupMenu\""),
                    "C# export action registered in editor and project context menus");
            check(Files.readString(Path.of("src/main/java/dev/astral/modforge/ide/ExportModAction.java"))
                            .contains("CommonDataKeys.VIRTUAL_FILE"),
                    "context export handles editor single-file selections");
            String toolWindowFactory = Files.readString(Path.of("src/main/java/dev/astral/modforge/ide/ForgeToolWindowFactory.java"));
            check(toolWindowFactory.contains("BuildLogService.getInstance(project)") &&
                    Files.readString(Path.of("src/main/java/dev/astral/modforge/ide/ExportModAction.java"))
                            .contains("BuildLogService.getInstance(project)"),
                    "tool window and export action use nullable-safe log service lookup");
            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
                Path fakeSdk = base.resolve("fake-dotnet-embedded.sh");
                Files.writeString(fakeSdk, """
                        #!/bin/sh
                        if [ "$1" = "--version" ]; then echo 8.0.0; exit 0; fi
                        if [ "$1" = "restore" ]; then exit 0; fi
                        if [ "$1" != "build" ]; then exit 25; fi
                        grep -q 'ModLocalization.Initialize(Log)' Plugin.cs || exit 21
                        grep -q 'GetManifestResourceStream' ModLocalization.cs || exit 22
                        if grep -q 'Config.Bind' Plugin.cs; then exit 23; fi
                        if grep -q 'File.WriteAllText' ModLocalization.cs; then exit 24; fi
                        mkdir -p bin/Release/net6.0
                        printf fake > bin/Release/net6.0/Dev.Test.EmbeddedInstall.dll
                        echo EmbeddedConfigMock succeeded
                        """);
                fakeSdk.toFile().setExecutable(true);
                ForgeSpec embeddedDeploy = new ForgeSpec(src,"dev.test.embedded-install",
                        List.of(Path.of("Features/AutoSwitch.cs")),base.resolve("out"),
                        ForgeSpec.LanguageMode.EMBEDDED,"en_us","","",true,true,game,false,true);
                System.setProperty("astral.modforge.dotnet", fakeSdk.toString());
                try {
                    ForgeExporter.Result deployedEmbedded = new ForgeExporter().export(embeddedDeploy);
                    check(deployedEmbedded.compiled() && deployedEmbedded.deployed(),
                            "embedded-default DLL deployed after successful mock build");
                    check(Files.isRegularFile(game.resolve("BepInEx/plugins/dev_test_embedded_install/Dev.Test.EmbeddedInstall.dll")) &&
                            !Files.exists(game.resolve("BepInEx/config/dev.test.embedded-install.cfg")) &&
                            !Files.exists(game.resolve("BepInEx/config/dev.test.embedded-install/lang")),
                            "deploy copies DLL without generating sidecar default config or language files");
                } finally { System.clearProperty("astral.modforge.dotnet"); }
            }
            // v0.5.3: host references both managed SDK and nested SDK Native.
            // 使用 SDK v0.10.0 双工程目录的真实结构，检查导出时不会破坏相对路径。
            Path sdkV10 = base.resolve("AstralParty.Modding.SDK");
            Path sdkNative = sdkV10.resolve("Native");
            Files.createDirectories(sdkNative.resolve("build"));
            Files.writeString(sdkV10.resolve("AstralParty.Modding.SDK.csproj"),
                    "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>net6.0</TargetFramework></PropertyGroup></Project>");
            Files.writeString(sdkV10.resolve("Collections.cs"),
                    "namespace AstralParty.Modding.SDK { public static class SampleCore {} }");
            Files.writeString(sdkNative.resolve("AstralParty.Modding.SDK.Native.csproj"),
                    "<Project Sdk=\"Microsoft.NET.Sdk\"><ItemGroup><ProjectReference Include=\"../AstralParty.Modding.SDK.csproj\" /></ItemGroup></Project>");
            Files.writeString(sdkNative.resolve("build/NativeInteropReferences.targets"), "<Project />");
            Path migratedHost = base.resolve("host-v048");
            Files.createDirectories(migratedHost.resolve("Features"));
            Files.writeString(migratedHost.resolve("AstralProbe.csproj"),
                    "<Project Sdk=\"Microsoft.NET.Sdk\"><ItemGroup>"
                            + "<ProjectReference Include=\"../AstralParty.Modding.SDK/AstralParty.Modding.SDK.csproj\" />"
                            + "<ProjectReference Include=\"../AstralParty.Modding.SDK/Native/AstralParty.Modding.SDK.Native.csproj\" />"
                            + "</ItemGroup></Project>");
            Files.writeString(migratedHost.resolve("Features/Feature.cs"),
                    "namespace Sample { class Feature { internal static void Initialize() {} } }");
            ForgeSpec nativeReferenceExport = new ForgeSpec(migratedHost, "dev.test.nativesdk",
                    List.of(Path.of("Features/Feature.cs")), base.resolve("native-out"),
                    ForgeSpec.LanguageMode.INLINE, "zh_cn", "", "", false, false, null);
            ForgeExporter.Result sdkExportResult = new ForgeExporter().export(nativeReferenceExport);
            Path sdkCopy = sdkExportResult.directory().resolve("Dependencies/Projects/AstralParty.Modding.SDK");
            check(Files.isRegularFile(sdkCopy.resolve("AstralParty.Modding.SDK.csproj")) &&
                            Files.isRegularFile(sdkCopy.resolve("Native/AstralParty.Modding.SDK.Native.csproj")) &&
                            Files.isRegularFile(sdkCopy.resolve("Native/build/NativeInteropReferences.targets")),
                    "managed and native SDK projects copied together with targets");
            check(Files.readString(sdkExportResult.directory().resolve("MOD.csproj")).contains(
                            "Dependencies/Projects/AstralParty.Modding.SDK/Native/AstralParty.Modding.SDK.Native.csproj"),
                    "exported project references nested native SDK in its original directory");
            check(Files.readString(sdkExportResult.directory().resolve("MOD.csproj")).contains(
                            "AdditionalProperties=\"AstralInteropDir=$(ForgeInteropDir)\""),
                    "native SDK receives ForgeInteropDir for game proxy references");
            check(Files.readString(sdkCopy.resolve("Native/AstralParty.Modding.SDK.Native.csproj"))
                            .contains("../AstralParty.Modding.SDK.csproj"),
                    "nested SDK ProjectReference still resolves after export");

            // v0.5.4: Native is now a source package inside one unified SDK project.
            // 单程序集 SDK 必须把游戏代理路径传给根项目，且不能要求旧 Native.csproj。
            Path sdkV11 = base.resolve("unified/AstralParty.Modding.SDK");
            Files.createDirectories(sdkV11.resolve("Native/Interop"));
            Files.createDirectories(sdkV11.resolve("build"));
            Files.writeString(sdkV11.resolve("AstralParty.Modding.SDK.csproj"),
                    "<Project Sdk=\"Microsoft.NET.Sdk\"><Import Project=\"build/NativeInteropReferences.targets\" /></Project>");
            Files.writeString(sdkV11.resolve("Native/Interop/Utility.cs"),
                    "namespace AstralParty.Modding.SDK.Native.Interop { public static class Utility {} }");
            Files.writeString(sdkV11.resolve("build/NativeInteropReferences.targets"), "<Project />");
            Path unifiedHost = base.resolve("unified/AstralProbe");
            Files.createDirectories(unifiedHost.resolve("Features"));
            Files.writeString(unifiedHost.resolve("AstralProbe.csproj"),
                    "<Project Sdk=\"Microsoft.NET.Sdk\"><ItemGroup>"
                            + "<ProjectReference Include=\"../AstralParty.Modding.SDK/AstralParty.Modding.SDK.csproj\" Condition=\"'$(AstralInteropDir)' != ''\" />"
                            + "<ProjectReference Include=\"../AstralParty.Modding.SDK/AstralParty.Modding.SDK.csproj\" Condition=\"'$(AstralInteropDir)' == ''\" />"
                            + "</ItemGroup></Project>");
            Files.writeString(unifiedHost.resolve("Features/Feature.cs"),
                    "namespace Sample { class Feature { internal static void Initialize() {} } }");
            ForgeSpec unifiedSpec = new ForgeSpec(unifiedHost, "dev.test.unifiedsdk",
                    List.of(Path.of("Features/Feature.cs")), base.resolve("unified-out"),
                    ForgeSpec.LanguageMode.INLINE, "zh_cn", "", "", false, false, null);
            ForgeExporter.Result unifiedResult = new ForgeExporter().export(unifiedSpec);
            Path unifiedCopy = unifiedResult.directory().resolve("Dependencies/Projects/AstralParty.Modding.SDK");
            check(Files.isRegularFile(unifiedCopy.resolve("Native/Interop/Utility.cs"))
                            && Files.isRegularFile(unifiedCopy.resolve("build/NativeInteropReferences.targets")),
                    "unified SDK helper sources and MSBuild targets copied");
            check(!Files.exists(unifiedCopy.resolve("Native/AstralParty.Modding.SDK.Native.csproj")),
                    "unified SDK no longer requires a separate Native project");
            check(Files.readString(unifiedResult.directory().resolve("MOD.csproj"))
                            .contains("AdditionalProperties=\"AstralInteropDir=$(ForgeInteropDir)\""),
                    "unified SDK receives ForgeInteropDir through ProjectReference");
            String outputProject = Files.readString(unifiedResult.directory().resolve("MOD.csproj"));
            check(outputProject.split("Dependencies/Projects/AstralParty.Modding.SDK/AstralParty.Modding.SDK.csproj", -1).length == 2,
                    "conditional duplicate project references are emitted only once");

            System.out.println("CoreChecks PASS: " + passed + " checks / 检查全部通过");
        } finally { try(Stream<Path> paths=Files.walk(base)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);} }
    }
    private static void check(boolean good, String name) {if(!good)throw new AssertionError("FAIL: "+name);passed++;}
}
