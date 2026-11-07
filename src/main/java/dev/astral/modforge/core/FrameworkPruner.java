package dev.astral.modforge.core;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remove accidentally selected Framework helpers only when no surviving C# unit references them.
 * 仅移除没有被实际引用的 Framework 文件；仍在调用链中的工具类必须保留，以免导出 DLL 缺失类型。
 */
public class FrameworkPruner {
    public record Result(Set<CSharpIndex.Unit> sources, List<Path> removed, List<Path> retained) {}

    private static final Pattern USING = Pattern.compile("(?m)^\\s*using\\s+(?!static\\b)([\\w.]+)\\s*;");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_][A-Za-z_0-9]*\\b");
    private static final Pattern DECLARATION = Pattern.compile("\\bnamespace\\s+[\\w.]+\\s*[;{]");

    public static boolean framework(CSharpIndex index, CSharpIndex.Unit unit) {
        return index.root().relativize(unit.file()).toString().replace('\\', '/').startsWith("Framework/");
    }

    public Result prune(CSharpIndex index, Set<CSharpIndex.Unit> candidates,
                        Map<Path, String> rewritten) {
        Set<CSharpIndex.Unit> kept = new LinkedHashSet<>();
        Deque<CSharpIndex.Unit> pending = new ArrayDeque<>();
        // Ordinary feature files remain unchanged. Framework files are considered only if referenced.
        for (CSharpIndex.Unit unit : candidates) {
            if (!framework(index, unit)) {
                kept.add(unit);
                pending.addLast(unit);
            }
        }
        // A standalone framework class is not an independently executable feature.
        // 显式勾选的 Framework 类只有被业务类引用时才作为依赖参与导出。
        while (!pending.isEmpty()) {
            CSharpIndex.Unit unit = pending.removeFirst();
            for (CSharpIndex.Unit dependency : referenced(index, unit, rewritten)) {
                if (candidates.contains(dependency) && kept.add(dependency)) pending.addLast(dependency);
            }
            for (String name : unit.declared()) {
                String fqName = unit.namespace().isBlank() ? name : unit.namespace() + "." + name;
                for (CSharpIndex.Unit part : index.sameType(fqName))
                    if (candidates.contains(part) && kept.add(part)) pending.addLast(part);
            }
        }
        List<Path> removed = new ArrayList<>(), retained = new ArrayList<>();
        for (CSharpIndex.Unit unit : candidates) if (framework(index, unit)) {
            (kept.contains(unit) ? retained : removed).add(index.root().relativize(unit.file()));
        }
        removed.sort(Comparator.comparing(Path::toString));
        retained.sort(Comparator.comparing(Path::toString));
        return new Result(Collections.unmodifiableSet(kept), List.copyOf(removed), List.copyOf(retained));
    }

    private Set<CSharpIndex.Unit> referenced(CSharpIndex index, CSharpIndex.Unit unit,
                                             Map<Path, String> rewritten) {
        String source = rewritten.getOrDefault(unit.file(), unit.source());
        String masked = CSharpIndex.maskLiteralsAndComments(source);
        // Strip namespace imports only. Preserve 'using static' and type aliases: they are real dependencies.
        // using static 可以在不写类型名的情况下调用成员，不允许错误剔除它引用的框架类。
        masked = USING.matcher(masked).replaceAll("");
        masked = DECLARATION.matcher(masked).replaceAll("");
        Set<String> identifiers = new LinkedHashSet<>();
        Matcher tokens = IDENTIFIER.matcher(masked);
        while (tokens.find()) identifiers.add(tokens.group());
        // Preserve aliases/using resolution from original declaration where needed.
        CSharpIndex.Unit adjusted = new CSharpIndex.Unit(unit.file(), unit.namespace(), unit.declared(),
                unit.usings(), source, masked, identifiers);
        List<String> ambiguities = new ArrayList<>();
        return index.dependencies(adjusted, ambiguities);
    }
}
