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
