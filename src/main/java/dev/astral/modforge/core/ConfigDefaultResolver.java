package dev.astral.modforge.core;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read simple literal defaults from ConfigFile.Bind(section, key, defaultValue, ...).
 * 只接受能够准确验证类型的 C# 字面量；不执行表达式，也不猜测动态默认值。
 */
public class ConfigDefaultResolver {
    private static final Pattern BIND = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*=\\s*(?:[A-Za-z_]\\w*\\s*\\.\\s*)+Bind\\s*\\(");
    private static final Pattern INTEGER = Pattern.compile("[+-]?(?:[0-9][0-9_]*|0[xX][0-9a-fA-F_]+)");
    private static final Pattern FLOAT = Pattern.compile("[+-]?(?:[0-9][0-9_]*(?:\\.[0-9_]*)?|\\.[0-9_]+)(?:[eE][+-]?[0-9_]+)?[fF]");
    private static final Pattern DOUBLE = Pattern.compile("[+-]?(?:[0-9][0-9_]*(?:\\.[0-9_]*)?|\\.[0-9_]+)(?:[eE][+-]?[0-9_]+)?[dD]?");
    private static final Pattern STRING = Pattern.compile("(?:\\\"(?:[^\\\"\\\\]|\\\\.)*\\\"|@\\\"(?:[^\\\"]|\\\"\\\")*\\\")", Pattern.DOTALL);

    public Map<String, String> literals(String source) {
        String masked = CSharpIndex.maskLiteralsAndComments(source);
        Matcher match = BIND.matcher(masked);
        Map<String, String> defaults = new LinkedHashMap<>();
        while (match.find()) {
            List<String> args = arguments(source, masked, match.end() - 1);
            if (args.size() >= 3) defaults.put(match.group(1), args.get(2).trim());
        }
        return defaults;
    }

    private List<String> arguments(String source, String masked, int opening) {
        List<String> result = new ArrayList<>();
        int depth = 0, square = 0, curly = 0, start = opening + 1;
        for (int i = opening; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (--depth == 0) {
                    result.add(source.substring(start, i));
                    return result;
                }
            } else if (c == '[') square++;
            else if (c == ']') square--;
            else if (c == '{') curly++;
            else if (c == '}') curly--;
            else if (c == ',' && depth == 1 && square == 0 && curly == 0) {
                result.add(source.substring(start, i));
                start = i + 1;
            }
        }
        return List.of();
    }

    public boolean valid(String type, String value) {
        return switch (type) {
            case "bool", "Boolean" -> "true".equals(value) || "false".equals(value);
            case "int" -> INTEGER.matcher(value).matches();
            case "float" -> FLOAT.matcher(value).matches();
            case "double" -> DOUBLE.matcher(value).matches();
            case "string" -> STRING.matcher(value).matches();
            default -> false;
        };
    }
}
