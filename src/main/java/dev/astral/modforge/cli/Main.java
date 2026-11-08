package dev.astral.modforge.cli;

import dev.astral.modforge.core.ForgeExporter;
import dev.astral.modforge.core.ForgeSpec;
import dev.astral.modforge.core.ForgeText;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CLI shares the export core with Rider. / 命令行与 IDE 复用同一导出核心。 */
public class Main {
    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception exception) {
            System.err.println(ForgeText.select("错误 / ERROR", ForgeText.systemLanguage()) + ":\n" +
                    ForgeText.select(exception.getMessage(), ForgeText.systemLanguage()));
            System.exit(2);
        }
    }

    public static int run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help")) {
            usage();
            return 0;
        }

        Map<String, String> options = new LinkedHashMap<>();
        for (int argumentIndex = 1; argumentIndex < args.length; argumentIndex++) {
            String argument = args[argumentIndex];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("Invalid argument: " + argument);
            }
            int equalsIndex = argument.indexOf('=');
            if (equalsIndex < 0) {
                options.put(argument.substring(2), "true");
            } else {
                options.put(argument.substring(2, equalsIndex), argument.substring(equalsIndex + 1));
            }
        }

        String id = required(options, "id");
        Path root = Path.of(options.getOrDefault("project", ".")).toAbsolutePath().normalize();
        ForgeExporter exporter = new ForgeExporter();
        if (args[0].equals("new")) {
            ForgeExporter.Result result = exporter.createEmpty(root, id);
            System.out.println(ForgeText.select("新建 MOD / New MOD", ForgeText.systemLanguage()) + ": " + result.directory());
            return 0;
        }
        if (!args[0].equals("export")) {
            throw new IllegalArgumentException("Unknown subcommand: " + args[0]);
        }

        String list = required(options, "classes");
        List<Path> files = Arrays.stream(list.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).map(Path::of).toList();
        ForgeSpec.LanguageMode mode = "inline".equalsIgnoreCase(
                options.getOrDefault("lang", "embedded"))
                ? ForgeSpec.LanguageMode.INLINE : ForgeSpec.LanguageMode.EMBEDDED;

        ForgeSpec spec = new ForgeSpec(root, id, files,
                options.containsKey("output") ? Path.of(options.get("output")) : null,
                mode, options.getOrDefault("locale", "zh_cn"), options.getOrDefault("load", ""),
                options.getOrDefault("update", ""), !options.containsKey("no-build"),
                options.containsKey("copy"),
                options.containsKey("game") ? Path.of(options.get("game")) : null,
                options.containsKey("export-source"), options.containsKey("embed-default-config"));

        ForgeExporter.Result result = exporter.export(spec);
        System.out.println(ForgeText.select("工程 / Project", ForgeText.systemLanguage()) + ": " + result.directory());
        System.out.println(ForgeText.select("报告 / Report", ForgeText.systemLanguage()) + ": " + result.report());
        if (result.dll() != null) {
            System.out.println("DLL: " + result.dll());
        } else {
            System.out.println(ForgeText.select("未生成 DLL / No DLL built", ForgeText.systemLanguage()));
        }
        if (result.deployed()) {
            System.out.println(ForgeText.select("已复制到游戏 / Copied to game", ForgeText.systemLanguage()));
        }
        return spec.build() && !result.compiled() ? 1 : 0;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing --" + key);
        }
        return value;
    }

    private static void usage() {
        System.out.println("Astral Mod Forge v0.4.0\n" +
                "  new --id=dev.example.mod [--project=/path/to/workspace]\n" +
                "  export --id=dev.example.mod --project=/path/to/csproj-dir --classes=Features/A.cs,Features/B.cs\n" +
                "      [--lang=embedded|inline] [--locale=zh_cn|en_us]\n" +
                "      [--load=Namespace.Type::Initialize;Namespace.Type::Init] [--update=Namespace.Type::Tick]\n" +
                "      [--output=/path/to/output] [--export-source] [--embed-default-config] [--no-build] [--copy --game=/path/to/game]");
    }
}
