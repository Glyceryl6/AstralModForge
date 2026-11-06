package dev.astral.modforge.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public record Result(Map<Path, String> sources, Map<String, String> zh, Map<String, String> en, List<String> warnings, List<String> errors) { }

    public Result transform(Path projectRoot, ForgeSpec spec, Collection<CSharpIndex.Unit> units) throws IOException {
        Map<String, String> oldZh = readOptional(projectRoot, "zh_cn");
        Map<String, String> oldEn = readOptional(projectRoot, "en_us");
        Map<String, String> newZh = new TreeMap<>();
        Map<String, String> newEn = new TreeMap<>();
        Map<Path, String> transformed = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>(), errors = new ArrayList<>();
        boolean dynamic = false;
        for (CSharpIndex.Unit unit : units) {
            String source = unit.source();
            String masked = unit.code();
            Matcher m = CALL.matcher(source);
            StringBuilder buf = new StringBuilder();
            Set<Integer> resolved = new HashSet<>();
            Set<Integer> consumedKeys = new HashSet<>();
            while (m.find()) {
                if (!masked.startsWith("ModLanguageService", m.start()) &&
                        !masked.startsWith("ModLocalization", m.start())) continue;
                String oldKey = unescapeCSharp(m.group(1));
                if (oldKey == null) {
                    errors.add("不支持的 C# 字符串转义 / Unsupported literal " + unit.file());
                    continue;
                }
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
                        !masked.startsWith("ModLocalization", any.start())) || resolved.contains(any.start())) continue;
                dynamic = true;
                if (spec.languageMode() == ForgeSpec.LanguageMode.INLINE)
                    errors.add("动态或不支持的翻译调用，不能安全替换为硬编码 / Dynamic translation: " +
                            projectRoot.relativize(unit.file()) + " @ " + any.start());
            }
            Matcher bare = BARE_KEY.matcher(source);
            while (bare.find()) {
                String key = bare.group(1);
                if (consumedKeys.contains(bare.start(1)) || !oldZh.containsKey(key) || !oldEn.containsKey(key))
                    continue;
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
        if (dynamic && spec.languageMode() == ForgeSpec.LanguageMode.EMBEDDED) {
            warnings.add("存在动态翻译键，内嵌了全部原始键并保留原键名 / Dynamic keys: bundled full dictionaries");
            newZh.putAll(oldZh);
            newEn.putAll(oldEn);
        }
        if (spec.languageMode() == ForgeSpec.LanguageMode.INLINE)
            warnings.add("本次语言已固定为 " + spec.locale() + "，运行时无法切换文本 / Fixed locale inlined");
        return new Result(transformed, newZh, newEn, warnings, errors);
    }

    private static String unescapeCSharp(String key) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= key.length()) return null;
            char e = key.charAt(i);
            switch (e) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '0' -> out.append('\0');
                case 'u' -> {
                    if (i + 4 >= key.length()) return null;
                    try {
                        out.append((char) Integer.parseInt(key.substring(i + 1, i + 5), 16));
                    } catch (Exception ex) {
                        return null;
                    }
                    i += 4;
                }
                default -> {
                    return null;
                }
            }
        }
        return out.toString();
    }

    private Map<String, String> readOptional(Path root, String locale) throws IOException {
        for (Path path : List.of(root.resolve("localization-templates").resolve(locale + ".json"),
                root.resolve("Resources/lang").resolve(locale + ".json"),
                root.resolve("lang").resolve(locale + ".json"))) {
            if (Files.isRegularFile(path)) return JsonFlat.parse(Files.readString(path, StandardCharsets.UTF_8));
        }
        return Map.of();
    }
}
