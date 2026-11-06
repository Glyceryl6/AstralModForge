package dev.astral.modforge.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Conservative C# source index, NOT a Roslyn/ReSharper semantic model.
 * 保守的 C# 文本索引；只做确定性的源码依赖收集，不假装能证明所有调用安全。
 */
public final class CSharpIndex {
    private static final Pattern NAMESPACE = Pattern.compile("\\bnamespace\\s+([\\w.]+)\\s*[;{]");
    private static final Pattern DECLARATION = Pattern.compile("\\b(?:class|struct|interface|enum|record(?:\\s+class|\\s+struct)?)\\s+([A-Za-z_]\\w*)");
    private static final Pattern USING = Pattern.compile("\\busing\\s+(?:static\\s+)?([\\w.]+)\\s*;");
    private static final Pattern NAME = Pattern.compile("\\b[A-Za-z_][A-Za-z_0-9]*\\b");
    private final Path root;
    private final Map<Path, Unit> units = new TreeMap<>();
    private final Map<String, List<Unit>> byShortName = new HashMap<>();
    private final Map<String, List<Unit>> byQualifiedName = new HashMap<>();

    public record Unit(Path file, String namespace, Set<String> declared, Set<String> usings,
                       String source, String code, Set<String> identifiers) {
        public boolean defines(String fqName) {
            if (!fqName.startsWith(namespace + ".")) return false;
            return declared.contains(fqName.substring(namespace.length() + 1));
        }
    }

    public CSharpIndex(Path root) throws IOException {
        this.root = root.toRealPath();
        Path csproj;
        try (Stream<Path> projects = Files.list(this.root)) {
            csproj = projects.filter(p -> p.toString().endsWith(".csproj")).findFirst().orElse(null);
        }
        List<Pattern> excludes = readExclusions(csproj);
        try (Stream<Path> paths = Files.walk(this.root)) {
            for (Path file : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".cs")).toList()) {
                Path relative = this.root.relativize(file);
                String slash = relative.toString().replace('\\', '/');
                if (slash.matches("(?i)(?:^|.*/)(?:bin|obj|tests|.git|AstralModForge|AstralProbe.ContentPreview.Addon)/.*"))
                    continue;
                if (slash.startsWith("Modules/") && Files.isDirectory(this.root.resolve("Features"))) continue;
                if (excludes.stream().anyMatch(p -> p.matcher(slash).matches())) continue;
                String content = Files.readString(file, StandardCharsets.UTF_8).replace("\uFEFF", "");
                String code = maskLiteralsAndComments(content);
                Matcher ns = NAMESPACE.matcher(code);
                String namespace = ns.find() ? ns.group(1) : "";
                Set<String> types = groups(DECLARATION, code, 1);
                Set<String> usings = groups(USING, code, 1);
                Set<String> identifiers = groups(NAME, code, 0);
                Unit unit = new Unit(file, namespace, types, usings, content, code, identifiers);
                units.put(file, unit);
                for (String type : types) {
                    byShortName.computeIfAbsent(type, key -> new ArrayList<>()).add(unit);
                    byQualifiedName.computeIfAbsent(namespace.isEmpty() ? type : namespace + "." + type,
                            key -> new ArrayList<>()).add(unit);
                }
            }
        }
    }

    public Path root() {
        return root;
    }

    public Map<Path, Unit> units() {
        return Collections.unmodifiableMap(units);
    }

    public Unit unit(Path file) {
        return units.get(file.toAbsolutePath().normalize());
    }

    public List<Unit> sameType(String fqName) {
        return byQualifiedName.getOrDefault(fqName, List.of());
    }

    public Set<Unit> dependencies(Unit from, List<String> errors) {
        Set<Unit> result = new LinkedHashSet<>();
        for (String id : from.identifiers) {
            if (id.equals("Plugin") || id.equals("ModLocalization") || id.equals("ModLanguageService")) continue;
            List<Unit> matches = byShortName.getOrDefault(id, List.of());
            if (matches.isEmpty()) continue;
            Map<String, List<Unit>> candidates = new LinkedHashMap<>();
            for (Unit match : matches) {
                String fq = match.namespace.isEmpty() ? id : match.namespace + "." + id;
                candidates.computeIfAbsent(fq, ignored -> new ArrayList<>()).add(match);
            }
            if (candidates.size() == 1) {
                result.addAll(candidates.values().iterator().next());
                continue;
            }
            String own = from.namespace.isEmpty() ? id : from.namespace + "." + id;
            if (candidates.containsKey(own)) {
                result.addAll(candidates.get(own));
                continue;
            }
            List<String> namespaces = candidates.keySet().stream()
                    .filter(fq -> from.usings.contains(fq.substring(0, fq.lastIndexOf('.'))))
                    .toList();
            if (namespaces.size() == 1) result.addAll(candidates.get(namespaces.getFirst()));
            else errors.add("有歧义的类型 / Ambiguous C# type '" + id + "' in " + root.relativize(from.file));
        }
        result.remove(from);
        return result;
    }

    private static Set<String> groups(Pattern pattern, String content, int index) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) result.add(matcher.group(index));
        return result;
    }

    // Preserve offsets to keep regex substitutions from crossing literal/comment boundaries.
    // 替换为等长空白，避免把注释里的假引用当成真实依赖。
    static String maskLiteralsAndComments(String s) {
        char[] result = s.toCharArray();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && (s.charAt(i + 1) == '/' || s.charAt(i + 1) == '*')) {
                boolean line = s.charAt(i + 1) == '/';
                int end = i + 2;
                if (line) {
                    while (end < s.length() && s.charAt(end) != '\n') end++;
                } else {
                    while (end + 1 < s.length() && !(s.charAt(end) == '*' && s.charAt(end + 1) == '/')) end++;
                    end = Math.min(s.length(), end + 2);
                }
                blank(result, i, end);
                i = end;
                continue;
            }
            boolean verbatim = c == '@' && i + 1 < s.length() && s.charAt(i + 1) == '"';
            boolean quoted = c == '"' || c == '\'' || verbatim;
            if (quoted) {
                int start = i;
                char quote = verbatim ? '"' : c;
                i += verbatim ? 2 : 1;
                while (i < s.length()) {
                    if (!verbatim && s.charAt(i) == '\\') {
                        i = Math.min(s.length(), i + 2);
                        continue;
                    }
                    if (s.charAt(i) == quote) {
                        if (verbatim && i + 1 < s.length() && s.charAt(i + 1) == '"') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                blank(result, start, i);
                continue;
            }
            i++;
        }
        return new String(result);
    }

    private static void blank(char[] result, int start, int end) {
        for (int j = start; j < end; j++) if (result[j] != '\n' && result[j] != '\r') result[j] = ' ';
    }

    private static List<Pattern> readExclusions(Path project) {
        if (project == null) return List.of();
        List<Pattern> patterns = new ArrayList<>();
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("https://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("https://xml.org/sax/features/external-general-entities", false);
            f.setFeature("https://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            Document d = f.newDocumentBuilder().parse(project.toFile());
            for (String tag : List.of("Compile", "DefaultItemExcludes")) {
                NodeList elements = d.getElementsByTagName(tag);
                for (int i = 0; i < elements.getLength(); i++) {
                    Element e = (Element) elements.item(i);
                    String raw = tag.equals("Compile") ? e.getAttribute("Remove") : e.getTextContent();
                    for (String item : raw.split(";")) {
                        String path = item.replace('\\', '/').trim();
                        if (path.isEmpty() || path.contains("$(") || path.startsWith("..")) continue;
                        patterns.add(Pattern.compile(globRegex(path)));
                    }
                }
            }
        } catch (Exception ignored) {
            // Build diagnostics remain authoritative; parsing uncommon MSBuild expressions is best effort.
        }
        return patterns;
    }

    private static String globRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                    regex.append("(?:.*/)?");
                    i++;
                } else regex.append(".*");
            } else if (c == '*') regex.append("[^/]*");
            else if (c == '?') regex.append(".");
            else regex.append("\\.[]{}()+-^$|".indexOf(c) < 0 ? c : "\\" + c);
        }
        return regex.append('$').toString();
    }
}
