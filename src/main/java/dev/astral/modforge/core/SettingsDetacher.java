package dev.astral.modforge.core;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remove host settings dependencies from exported features.
 * 从独立 MOD 中剥离宿主设置：只将能够证明是 ConfigEntry<bool> 的读取固定为 true。
 */
public class SettingsDetacher {
    public record Result(Map<Path, String> sources, List<String> warnings, List<String> errors) {}

    private static final Pattern ENTRY = Pattern.compile(
            "\\bConfigEntry\\s*<\\s*(bool|Boolean|int|float|double|string)\\s*>\\s+([^;]+);", Pattern.DOTALL);
    private static final Pattern CONFIG_VALUE = Pattern.compile(
            "\\b((?:global::)?(?:[A-Za-z_]\\w*\\.)*(?:Plugin|[A-Za-z_]\\w*Configuration))\\s*\\.\\s*(\\w+)\\s*\\.\\s*Value\\b");
    private static final Pattern CONFIG_OBJECT = Pattern.compile(
            "\\b((?:global::)?(?:[A-Za-z_]\\w*\\.)*(?:Plugin|[A-Za-z_]\\w*Configuration))\\s*\\.\\s*(\\w+)\\b");
    private static final Pattern USING = Pattern.compile("(?m)^[ \\t]*using\\s+(?:static\\s+)?([A-Za-z_][\\w.]*)\\s*;[ \\t]*(?:\\r?\\n)?");
    private static final Pattern LAYOUT_VALUE = Pattern.compile(
            "\\b(?:(?:global::)?(?:[A-Za-z_]\\w*\\.)*)ModSettingsLayout\\s*\\.\\s*([A-Za-z_]\\w*)\\b");
    private static final Pattern LAYOUT_OPTION = Pattern.compile(
            "\\b(?:F|I)\\s*\\(\\s*\"([A-Za-z_]\\w*)\"\\s*,\\s*([^,]+)");
    private static final Pattern LAYOUT_PROPERTY = Pattern.compile(
            "\\b(?:internal|public|private|protected)\\s+static\\s+(float|int|uint|bool|string)\\s+([A-Za-z_]\\w*)\\s*(?:=>|\\{)");
    private static final Set<String> PLUGIN_MEMBERS = Set.of("Instance", "ModLog", "SaveSettings");

    /** Settings and configuration source files belong to the host, not the standalone DLL. */
    public static boolean isHostManaged(CSharpIndex index, CSharpIndex.Unit unit) {
        String path = index.root().relativize(unit.file()).toString().replace('\\', '/');
        return path.equals("Plugin.cs") || path.equals("HotfixWatcher.cs") ||
                path.endsWith("/ModLanguageService.cs") || path.endsWith("/ModLocalization.cs") ||
                path.contains("/Settings/") || path.startsWith("Framework/Settings/") ||
                path.contains("/Configuration/") || path.startsWith("Framework/Configuration/");
    }

    public static boolean isUiSettings(CSharpIndex index, CSharpIndex.Unit unit) {
        String path = index.root().relativize(unit.file()).toString().replace('\\', '/');
        return path.contains("/Settings/") || path.startsWith("Framework/Settings/");
    }

    public Result transform(CSharpIndex index, Collection<CSharpIndex.Unit> sources) {
        return transform(index, sources, Map.of(), false);
    }

    public Result transform(CSharpIndex index, Collection<CSharpIndex.Unit> sources, boolean embedDefaults) {
        return transform(index, sources, Map.of(), embedDefaults);
    }

    public Result transform(CSharpIndex index, Collection<CSharpIndex.Unit> sources,
                            Map<Path, String> sourceOverrides, boolean embedDefaults) {
        ConfigDefaultResolver resolver = new ConfigDefaultResolver();
        Map<String, String> propertyTypes = new HashMap<>();
        Map<String, String> propertyDefaults = new HashMap<>();
        Map<String, String> layoutTypes = new HashMap<>();
        Map<String, String> layoutDefaults = new HashMap<>();
        Set<String> hostNamespaces = new HashSet<>();
        Set<String> managedTypeNames = new HashSet<>();
        for (CSharpIndex.Unit unit : index.units().values()) {
            if (!isHostManaged(index, unit)) continue;
            if (!unit.namespace().isBlank() &&
                    (isUiSettings(index, unit) || index.root().relativize(unit.file()).toString().replace('\\', '/').contains("/Configuration/")))
                hostNamespaces.add(unit.namespace());
            Map<String, String> literals = resolver.literals(unit.source());
            if (unit.declared().contains("ModSettingsLayout")) {
                Matcher options = LAYOUT_OPTION.matcher(unit.source());
                while (options.find()) {
                    layoutDefaults.put(options.group(1), options.group(2).trim());
                }
                Matcher properties = LAYOUT_PROPERTY.matcher(unit.code());
                while (properties.find()) {
                    layoutTypes.put(properties.group(2), properties.group(1));
                }
            }
            for (String type : unit.declared()) {
                managedTypeNames.add(type);
                Matcher matches = ENTRY.matcher(unit.code());
                while (matches.find()) {
                    for (String name : declaredNames(matches.group(2))) {
                        String key = type + "." + name;
                        propertyTypes.put(key, matches.group(1));
                        if (literals.containsKey(name)) propertyDefaults.put(key, literals.get(name));
                    }
                }
            }
        }
        Map<Path, String> output = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>(), errors = new ArrayList<>();
        Set<String> enabledNames = new TreeSet<>();
        Map<String, String> inlinedDefaults = new TreeMap<>();
        Map<String, String> layoutValues = new TreeMap<>();
        for (CSharpIndex.Unit unit : sources) {
            String code = sourceOverrides.getOrDefault(unit.file(), unit.source());
            String masked = CSharpIndex.maskLiteralsAndComments(code);
            Matcher m = CONFIG_VALUE.matcher(masked);
            List<Replacement> replacements = new ArrayList<>();
            Set<Integer> rewrittenMembers = new HashSet<>();
            while (m.find()) {
                String qualifier = m.group(1).replace("global::", "");
                String owner = qualifier.substring(qualifier.lastIndexOf('.') + 1);
                String name = m.group(2);
                String type = propertyTypes.get(owner + "." + name);
                if (type == null) {
                    errors.add("无法确定设置类型，未改写 / Unknown setting: " + qualifier + "." + name +
                            " (" + index.root().relativize(unit.file()) + ")");
                    continue;
                }
                if (isWrite(masked, m.start(), m.end())) {
                    errors.add("设置值被写入或按引用传递，不能安全固定 / Setting write or ref: " + qualifier + "." + name);
                    continue;
                }
                String replacement;
                if ("bool".equals(type) || "Boolean".equals(type)) {
                    replacement = "true";
                    enabledNames.add(qualifier + "." + name);
                } else if (embedDefaults) {
                    replacement = propertyDefaults.get(owner + "." + name);
                    if (replacement == null || !resolver.valid(type, replacement)) {
                        errors.add("无法从 Bind 取得安全的默认值 / Missing literal default: " + qualifier + "." + name);
                        continue;
                    }
                    inlinedDefaults.put(qualifier + "." + name, replacement);
                } else {
                    errors.add("非布尔设置需要勾选内嵌默认配置 / Non-boolean setting: " + qualifier + "." + name);
                    continue;
                }
                replacements.add(new Replacement(m.start(), m.end(), replacement));
                rewrittenMembers.add(m.start(2));
            }
            // Host layout accessors are backed by the original MOD's mutable settings file.
            // 独立 MOD 不携带宿主布局配置类；可识别的属性改用原项目声明的默认值。
            Matcher layout = LAYOUT_VALUE.matcher(masked);
            while (layout.find()) {
                String name = layout.group(1);
                String type = layoutTypes.get(name);
                String value = layoutDefaults.get(name);
                if (type == null || value == null) {
                    errors.add("无法安全内联宿主布局设置 / Cannot inline host layout setting: ModSettingsLayout." + name +
                            " (" + index.root().relativize(unit.file()) + ")");
                    continue;
                }
                if (isWrite(masked, layout.start(), layout.end())) {
                    errors.add("宿主布局设置被写入，不能安全导出 / Host layout setting is written: " + name);
                    continue;
                }
                String replacement = layoutLiteral(type, value);
                if (replacement == null) {
                    errors.add("宿主布局设置默认值不是安全字面量 / Unsupported host layout default: " + name + "=" + value);
                    continue;
                }
                replacements.add(new Replacement(layout.start(), layout.end(), replacement));
                layoutValues.put(name, replacement);
            }

            // No lingering ConfigEntry object may be copied, including passing a setting to a helper.
            Matcher raw = CONFIG_OBJECT.matcher(masked);
            while (raw.find()) {
                String owner = raw.group(1).replace("global::", "");
                owner = owner.substring(owner.lastIndexOf('.') + 1);
                if (propertyTypes.containsKey(owner + "." + raw.group(2)) && !rewrittenMembers.contains(raw.start(2)))
                    errors.add("存在未转换的设置对象引用 / Setting object reference: " + raw.group(0) +
                            " (" + index.root().relativize(unit.file()) + ")");
            }
            String changed = apply(code, replacements);
            // Drop using directives for discarded host namespaces; do not drop BepInEx.Configuration.
            Matcher usings = USING.matcher(CSharpIndex.maskLiteralsAndComments(changed));
            List<Replacement> unused = new ArrayList<>();
            while (usings.find()) if (hostNamespaces.contains(usings.group(1)))
                unused.add(new Replacement(usings.start(), usings.end(), ""));
            changed = apply(changed, unused);
            String remaining = CSharpIndex.maskLiteralsAndComments(changed);
            for (String type : managedTypeNames) {
                if (type.equals("Plugin") || type.equals("ModLocalization") || type.equals("ModLanguageService") ||
                        type.equals("HotfixWatcher")) continue;
                // Declarations/imports do not survive: any remaining host-managed class reference is unsafe.
                if (Pattern.compile("\\b" + Pattern.quote(type) + "\\b").matcher(remaining).find()) {
                    errors.add("依赖宿主设置或配置类，无法独立导出 / Host settings class: " + type +
                            " (" + index.root().relativize(unit.file()) + ")");
                }
            }
            // The original Plugin itself is replaced by the generated entrypoint.
            Matcher plugin = Pattern.compile("\\bPlugin\\s*\\.\\s*(\\w+)").matcher(remaining);
            while (plugin.find()) if (!PLUGIN_MEMBERS.contains(plugin.group(1)))
                errors.add("依赖原 MOD 的 Plugin 成员 / Host Plugin member: " + plugin.group(1) +
                        " (" + index.root().relativize(unit.file()) + ")");
            output.put(unit.file(), changed);
        }
        if (!enabledNames.isEmpty())
            warnings.add("已将 " + enabledNames.size() + " 个布尔开关固定为开启，不生成设置按钮或配置绑定 / " +
                    "Boolean toggles are always enabled: " + enabledNames);
        if (!inlinedDefaults.isEmpty())
            warnings.add("已内嵌 " + inlinedDefaults.size() + " 项非布尔默认配置 / Inlined default settings: " + inlinedDefaults);
        if (!layoutValues.isEmpty())
            warnings.add("已将 " + layoutValues.size() + " 项宿主布局设置替换为默认值 / Host layout defaults inlined: " + layoutValues);
        return new Result(Map.copyOf(output), List.copyOf(warnings), List.copyOf(new LinkedHashSet<>(errors)));
    }

    private static String layoutLiteral(String type, String value) {
        String literal = value.trim();
        if ("bool".equals(type)) {
            if ("0".equals(literal) || "0f".equalsIgnoreCase(literal)) return "false";
            if ("1".equals(literal) || "1f".equalsIgnoreCase(literal)) return "true";
            return null;
        }
        if ("uint".equals(type)) {
            if (!literal.matches("(?:0[xX][0-9a-fA-F_]+|[0-9][0-9_]*)[uU]?") ) return null;
            return literal.endsWith("u") || literal.endsWith("U") ? literal : literal + "u";
        }
        if ("int".equals(type)) {
            return literal.matches("[+-]?(?:0[xX][0-9a-fA-F_]+|[0-9][0-9_]*)[lL]?") ? literal : null;
        }
        if ("float".equals(type)) {
            return literal.matches("[+-]?(?:[0-9][0-9_]*(?:\\.[0-9_]*)?|\\.[0-9_]+)(?:[eE][+-]?[0-9_]+)?[fF]?") ? literal : null;
        }
        if ("string".equals(type)) {
            return literal.startsWith("\"") && literal.endsWith("\"") ? literal : null;
        }
        return null;
    }

    /** C# allows ConfigEntry<float> A = null!, B = null! in one declaration. */
    private static Set<String> declaredNames(String declaration) {
        Set<String> names = new LinkedHashSet<>();
        int depth = 0, bracket = 0, start = 0;
        for (int i = 0; i <= declaration.length(); i++) {
            char c = i == declaration.length() ? ',' : declaration.charAt(i);
            if (c == '(' || c == '{') depth++;
            else if (c == ')' || c == '}') depth--;
            else if (c == '[') bracket++;
            else if (c == ']') bracket--;
            else if (c == ',' && depth == 0 && bracket == 0) {
                Matcher name = Pattern.compile("^\\s*([A-Za-z_]\\w*)").matcher(declaration.substring(start, i));
                if (name.find()) names.add(name.group(1));
                start = i + 1;
            }
        }
        return names;
    }

    private static boolean isWrite(String masked, int start, int end) {
        String before = masked.substring(Math.max(0, start - 15), start);
        if (before.matches("(?s).*\\b(ref|out|in)\\s*$")) return true;
        String after = masked.substring(end).stripLeading();
        return after.startsWith("=") && !after.startsWith("==") && !after.startsWith("=>") ||
                after.startsWith("++") || after.startsWith("--") || after.startsWith("+=") ||
                after.startsWith("-=") || after.startsWith("??=");
    }

    private record Replacement(int start, int end, String value) {}
    private static String apply(String original, List<Replacement> edits) {
        StringBuilder result = new StringBuilder(original);
        edits.sort(Comparator.comparingInt(Replacement::start).reversed());
        for (Replacement edit : edits) result.replace(edit.start, edit.end, edit.value);
        return result.toString();
    }
}
