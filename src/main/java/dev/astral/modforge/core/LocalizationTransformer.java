package dev.astral.modforge.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites ONLY recognized string-literal translation calls (never global string replacement).
 * 仅处理可识别的字面量翻译调用；动态键禁止内联，避免替错代码中的普通字符串。
 */
public class LocalizationTransformer {
    private static final Pattern CALL = Pattern.compile(
            "\\b(?:ModLanguageService|ModLocalization)\\s*\\.\\s*(?:Translate|T)\\s*\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*\\)");
    private static final Pattern ANY_CALL = Pattern.compile(
            "\\b(?:ModLanguageService|ModLocalization)\\s*\\.\\s*(?:Translate|T)\\s*\\(");
    private static final Pattern BARE_KEY = Pattern.compile("\"(astralprobe\\.[a-zA-Z0-9_.-]+)\"");
    private static final Pattern PREFIX_CALL = Pattern.compile(
            "^\\s*\"(astralprobe\\.[a-zA-Z0-9_.-]+\\.)\"\\s*\\+.*", Pattern.DOTALL);
    private static final Pattern MEMBER_KEY = Pattern.compile(
            "^\\s*(?:[A-Za-z_][\\w]*\\.)+[A-Za-z_][\\w]*Key\\s*$");
    private static final Pattern CHOICE_KEY = Pattern.compile(
            "\\bnew(?:\\s+[A-Za-z_][\\w]*)?\\s*\\([^;{}]*?\"(astralprobe\\.[a-zA-Z0-9_.-]+)\"",
            Pattern.DOTALL);
    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("^[A-Za-z_][\\w]*$");
    private static final Pattern CONST_STRING = Pattern.compile(
            "\\b(?:const\\s+)?(?:readonly\\s+)?(?:string|var)\\s+%s\\s*=\\s*\"(astralprobe\\.[a-zA-Z0-9_.-]+)\"");

    public record Result(Map<Path,String> sources, Map<String,String> zh, Map<String,String> en,
                         List<String> warnings, List<String> errors) {}

    public Result transform(Path projectRoot, ForgeSpec spec, Collection<CSharpIndex.Unit> units) throws IOException {
        return transform(projectRoot, spec, units, Map.of());
    }

    public Result transform(Path projectRoot, ForgeSpec spec, Collection<CSharpIndex.Unit> units,
                            Map<Path, String> sourceOverrides) throws IOException {
        Map<String,String> oldZh = readOptional(projectRoot, "zh_cn");
        Map<String,String> oldEn = readOptional(projectRoot, "en_us");
        Map<String,String> newZh = new TreeMap<>();
        Map<String,String> newEn = new TreeMap<>();
        Map<Path,String> transformed = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>(), errors = new ArrayList<>();
        // Explicit dynamic keys live in the source project, never in a global dictionary.
        // 手工声明的动态键只在原项目读取，避免导出所有模块的翻译。
        Set<String> declared = readDeclaredKeys(projectRoot, oldZh, oldEn, errors);
        for (CSharpIndex.Unit unit : units) {
            String source = sourceOverrides.getOrDefault(unit.file(), unit.source());
            String masked = CSharpIndex.maskLiteralsAndComments(source);
            Matcher m = CALL.matcher(source);
            StringBuffer buf = new StringBuffer();
            Set<Integer> resolved = new HashSet<>();
            Set<Integer> consumedKeys = new HashSet<>();
            while (m.find()) {
                if (!masked.startsWith("ModLanguageService", m.start()) &&
                    !masked.startsWith("ModLocalization", m.start())) continue;
                String oldKey = unescapeCSharp(m.group(1));
                if (oldKey == null) { errors.add("不支持的 C# 字符串转义 / Unsupported literal " + unit.file()); continue; }
                if (!oldZh.containsKey(oldKey) || !oldEn.containsKey(oldKey)) {
                    errors.add("缺少中英翻译 / Missing locale entry: " + oldKey + " (" + projectRoot.relativize(unit.file()) + ")");
                    continue;
                }
                resolved.add(m.start());
                consumedKeys.add(m.start(1));
                String newKey = oldKey.startsWith("astralprobe.") ? spec.modId() + oldKey.substring("astralprobe".length()) : oldKey;
                String replacement;
                if (spec.languageMode() == ForgeSpec.LanguageMode.INLINE) {
                    replacement = JsonFlat.quote(("en_us".equals(spec.locale()) ? oldEn : oldZh).get(oldKey));
                } else {
                    newZh.put(newKey, oldZh.get(oldKey));
                    newEn.put(newKey, oldEn.get(oldKey));
                    // Preserve call shape and namespace: the generated compatibility shim supplies the API.
                    replacement = m.group(0).replace('"' + m.group(1) + '"', JsonFlat.quote(newKey));
                }
                m.appendReplacement(buf, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(buf);
            Matcher any = ANY_CALL.matcher(source);
            while (any.find()) {
                if ((!masked.startsWith("ModLanguageService", any.start()) &&
                     !masked.startsWith("ModLocalization", any.start())) || resolved.contains(any.start())) {
                    continue;
                }

                String expression = callArgument(source, any.end());
                String location = projectRoot.relativize(unit.file()) + " @ " + any.start();
                if (spec.languageMode() == ForgeSpec.LanguageMode.INLINE) {
                    errors.add("动态或不支持的翻译调用，不能安全替换为硬编码 / Dynamic translation: " + location);
                    continue;
                }

                Set<String> inferred = inferDynamicKeys(expression, source, oldZh.keySet(), declared);
                if (inferred.isEmpty()) {
                    errors.add("无法确定动态翻译键；请在 localization-keys.txt 中明确列出键或前缀 / "
                            + "Unresolved dynamic localization (declare keys in localization-keys.txt): " + location);
                    continue;
                }

                for (String key : inferred) {
                    if (oldZh.containsKey(key) && oldEn.containsKey(key)) {
                        // Keep the original keys: dynamically generated expressions cannot be rewritten safely.
                        newZh.put(key, oldZh.get(key));
                        newEn.put(key, oldEn.get(key));
                    }
                }
                warnings.add("动态翻译仅收集可追踪的键 / Scoped dynamic keys (" + inferred.size()
                        + "): " + location);
            }
            Matcher bare = BARE_KEY.matcher(source);
            while (bare.find()) {
                String key = bare.group(1);
                if (consumedKeys.contains(bare.start(1)) || !oldZh.containsKey(key) || !oldEn.containsKey(key)) continue;
                if (spec.languageMode() == ForgeSpec.LanguageMode.INLINE) {
                    errors.add("翻译键不在受支持的翻译调用中，无法安全内联 / Unhandled UI translation key " +
                            key + " in " + projectRoot.relativize(unit.file()));
                } else {
                    newZh.putIfAbsent(key, oldZh.get(key));
                    newEn.putIfAbsent(key, oldEn.get(key));
                    warnings.add("裸翻译键未转换，请检查 SDK UI 是否使用独立词典 / Raw key " + key +
                            " in " + projectRoot.relativize(unit.file()));
                }
            }
            transformed.put(unit.file(), buf.toString());
        }
        if (spec.languageMode() == ForgeSpec.LanguageMode.INLINE)
            warnings.add("本次语言已固定为 " + spec.locale() + "，运行时无法切换文本 / Fixed locale inlined");
        return new Result(transformed, newZh, newEn, warnings, errors);
    }

    /**
     * Reads explicitly documented keys for calls whose runtime arguments cannot be resolved.
     * 每行一个键或带 * 的前缀；不允许静默回退为完整宿主词典。
     */
    private static Set<String> readDeclaredKeys(Path root, Map<String,String> zh,
                                                Map<String,String> en, List<String> errors) throws IOException {
        Path manifest = root.resolve("localization-keys.txt");
        if (!Files.isRegularFile(manifest)) {
            return Set.of();
        }

        Set<String> keys = new TreeSet<>();
        for (String raw : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.endsWith(".*")) {
                String prefix = line.substring(0, line.length() - 1);
                for (String key : zh.keySet()) {
                    if (key.startsWith(prefix)) {
                        keys.add(key);
                    }
                }
            } else {
                keys.add(line);
            }
        }
        for (String key : keys) {
            if (!zh.containsKey(key) || !en.containsKey(key)) {
                errors.add("动态翻译清单中的键缺失 / Key missing from language manifest: " + key);
            }
        }
        return keys;
    }

    private static String callArgument(String source, int position) {
        int depth = 1;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = position; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '\"') {
                    quoted = false;
                }
            } else if (c == '\"') {
                quoted = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return source.substring(position, i).trim();
            }
        }
        return "";
    }

    private static Set<String> inferDynamicKeys(String expression, String source,
                                                 Set<String> dictionaryKeys, Set<String> declared) {
        Set<String> inferred = new TreeSet<>();
        if (expression == null || expression.isBlank()) {
            return inferred;
        }
        Matcher prefix = PREFIX_CALL.matcher(expression);
        if (prefix.matches() && prefix.group(1).length() > "astralprobe.".length()) {
            for (String key : dictionaryKeys) {
                if (key.startsWith(prefix.group(1))) {
                    inferred.add(key);
                }
            }
            return inferred;
        }
        // E.g. Translate(choice.NameKey) and literal keys stored in the same MarkChoice[] table.
        // 只接受源文件自身的 Key 字段与字面量候选项，避免把整个词典当作依赖。
        if (MEMBER_KEY.matcher(expression).matches()) {
            Matcher m = CHOICE_KEY.matcher(source);
            while (m.find()) {
                if (dictionaryKeys.contains(m.group(1))) {
                    inferred.add(m.group(1));
                }
            }
            if (!inferred.isEmpty()) {
                return inferred;
            }
        }
        if (SIMPLE_IDENTIFIER.matcher(expression).matches()) {
            Pattern assigned = Pattern.compile(String.format(CONST_STRING.pattern(),
                    Pattern.quote(expression)));
            Matcher m = assigned.matcher(source);
            while (m.find()) {
                if (dictionaryKeys.contains(m.group(1))) {
                    inferred.add(m.group(1));
                }
            }
            if (!inferred.isEmpty()) {
                return inferred;
            }
        }
        // Explicit manifest is an opt-in escape hatch for reflection or runtime-computed keys.
        return new TreeSet<>(declared);
    }

    private static String unescapeCSharp(String key) {
        StringBuilder out = new StringBuilder();
        for(int i=0;i<key.length();i++) {
            char c=key.charAt(i);
            if(c!='\\'){out.append(c);continue;}
            if(++i>=key.length())return null;
            char e=key.charAt(i);
            switch(e) {
                case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                case '"' -> out.append('"'); case '\\' -> out.append('\\');
                case '0' -> out.append('\0');
                case 'u' -> { if(i+4>=key.length())return null;
                    try{out.append((char)Integer.parseInt(key.substring(i+1,i+5),16));}catch(Exception ex){return null;}
                    i+=4; }
                default -> { return null; }
            }
        }
        return out.toString();
    }

    private Map<String,String> readOptional(Path root, String locale) throws IOException {
        for (Path path : List.of(root.resolve("localization-templates").resolve(locale + ".json"),
                root.resolve("Resources/lang").resolve(locale + ".json"),
                root.resolve("lang").resolve(locale + ".json"))) {
            if (Files.isRegularFile(path)) return JsonFlat.parse(Files.readString(path, StandardCharsets.UTF_8));
        }
        return Map.of();
    }
}
