package dev.astral.modforge.cli;

import dev.astral.modforge.core.*;
import java.nio.file.*;
import java.util.*;

/** CLI uses exactly the same core as Rider; useful for headless regression. / 命令行可复现 IDE 导出结果。 */
public class Main {
    public static void main(String[] args) {
        try { System.exit(run(args)); }
        catch(Exception ex) { System.err.println("ERROR / 错误:\n" + ex.getMessage()); System.exit(2); }
    }
    public static int run(String[] args) throws Exception {
        if(args.length==0 || args[0].equals("--help")) { usage(); return 0; }
        Map<String,String> options=new LinkedHashMap<>();
        for(int i=1;i<args.length;i++) {
            String argument=args[i]; if(!argument.startsWith("--"))throw new IllegalArgumentException("Invalid argument: " +argument);
            int eq=argument.indexOf('=');
            if(eq<0) options.put(argument.substring(2),"true");
            else options.put(argument.substring(2,eq),argument.substring(eq+1));
        }
        String id=required(options,"id");
        Path root=Path.of(options.getOrDefault("project", ".")).toAbsolutePath().normalize();
        ForgeExporter exporter=new ForgeExporter();
        if(args[0].equals("new")) {
            ForgeExporter.Result r=exporter.createEmpty(root,id);
            System.out.println("New MOD / 新建: "+r.directory()); return 0;
        }
        if(!args[0].equals("export")) throw new IllegalArgumentException("Unknown subcommand: "+args[0]);
        String list=required(options,"classes");
        List<Path> files=Arrays.stream(list.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(Path::of).toList();
        ForgeSpec.LanguageMode mode="inline".equalsIgnoreCase(options.getOrDefault("lang","embedded")) ?
                ForgeSpec.LanguageMode.INLINE : ForgeSpec.LanguageMode.EMBEDDED;
        ForgeSpec spec=new ForgeSpec(root,id,files,
                options.containsKey("output")?Path.of(options.get("output")):null,
                mode,options.getOrDefault("locale","zh_cn"),options.getOrDefault("load",""),
                options.getOrDefault("update",""),!options.containsKey("no-build"),
                options.containsKey("copy"),options.containsKey("game")?Path.of(options.get("game")):null,
                options.containsKey("export-source"),options.containsKey("embed-default-config"));
        ForgeExporter.Result r=exporter.export(spec);
        System.out.println("Project / 工程: "+r.directory());
        System.out.println("Report / 报告: "+r.report());
        if(r.dll()!=null) System.out.println("DLL: "+r.dll());
        else System.out.println("No DLL built / 未生成 DLL");
        if(r.deployed()) System.out.println("Copied to game / 已复制到游戏");
        return spec.build() && !r.compiled() ? 1 : 0;
    }
    private static String required(Map<String,String> opts, String key) {
        String value=opts.get(key);if(value==null || value.isBlank())throw new IllegalArgumentException("Missing --"+key);return value;
    }
    private static void usage() {
        System.out.println("Astral Mod Forge v0.3.0\n"+
            "  new --id=dev.example.mod [--project=/path/to/workspace]\n"+
            "  export --id=dev.example.mod --project=/path/to/csproj-dir --classes=Features/A.cs,Features/B.cs\n"+
            "      [--lang=embedded|inline] [--locale=zh_cn|en_us]\n"+
            "      [--load=Namespace.Type::Initialize;Namespace.Type::Init] [--update=Namespace.Type::Tick]\n"+
            "      [--output=/path/to/output] [--export-source] [--embed-default-config] [--no-build] [--copy --game=/path/to/game]");
    }
}
