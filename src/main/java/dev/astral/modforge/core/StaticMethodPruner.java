package dev.astral.modforge.core;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative method-level pruning for stateless static helper classes.
 * 对无状态静态辅助类进行保守的方法级裁剪；遇到动态调用或状态初始化时保留整个类型。
 *
 * <p>This is intentionally not a complete C# semantic model. It only removes methods when
 * direct type-qualified calls (or explicit {@code using static} calls) form an unambiguous
 * source-level chain, including {@code using static} and simple type aliases. Reflection,
 * delegates with unknown targets, mutable static state,
 * constructors, properties and unsupported declarations make the containing type opaque.</p>
 */
public class StaticMethodPruner {
    public record Result(Set<CSharpIndex.Unit> sources, Map<Path, String> rewrittenSources,
                         List<String> warnings, List<String> removedMembers) {
    }

    private static final Pattern STATIC_CLASS = Pattern.compile(
            "(?<modifiers>(?:(?:public|internal|private|protected|file|abstract|sealed|static|partial|unsafe|new)\\s+)*)" +
                    "class\\s+(?<name>[A-Za-z_]\\w*)\\b[^;{]*\\{");
    private static final Pattern STATIC_USING = Pattern.compile(
            "(?m)^\\s*(?:global\\s+)?using\\s+static\\s+(?:global::)?([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)\\s*;");
    private static final Pattern USING_ALIAS = Pattern.compile(
            "(?m)^\\s*(?:global\\s+)?using\\s+([A-Za-z_]\\w*)\\s*=\\s*(?:global::)?([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)\\s*;");
    private static final Pattern NESTED_TYPE = Pattern.compile(
            "(?<modifiers>(?:(?:public|internal|private|protected|readonly|ref|static|partial|unsafe|sealed|abstract|new)\\s+)*)" +
                    "(?:class|struct|enum|interface|record)\\s+(?<name>[A-Za-z_]\\w*)\\b[^;{]*\\{");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_]\\w*\\b");
    private static final Pattern REFLECTION = Pattern.compile(
            "\\b(?:GetMethod|GetMethods|GetProperty|GetProperties|GetField|GetFields|GetMember|GetMembers|" +
                    "GetType|DynamicInvoke)\\s*\\(|\\b(?:Activator\\.CreateInstance|Type\\.GetType|" +
                    "Assembly\\.Load(?:From)?|RuntimeHelpers\\.RunClassConstructor)\\s*\\(");
    private static final Pattern ATTRIBUTES = Pattern.compile("(?s)\\[[^\\]]*\\]");

    private final Map<String, StaticType> typesByQualifiedName = new LinkedHashMap<>();
    private final Map<String, List<StaticType>> typesByShortName = new LinkedHashMap<>();
    private final Set<MethodRef> reachable = new LinkedHashSet<>();
    private final Deque<MethodRef> pending = new ArrayDeque<>();
    private final Set<CSharpIndex.Unit> activeUnits = new LinkedHashSet<>();
    private final Set<CSharpIndex.Unit> selectedUnits = new LinkedHashSet<>();
    private final Set<String> opaqueTypes = new LinkedHashSet<>();
    private CSharpIndex index;
    private Set<CSharpIndex.Unit> candidates;

    public Result prune(CSharpIndex index, Set<CSharpIndex.Unit> candidates,
                        Collection<CSharpIndex.Unit> selected,
                        Map<Path, String> sourceOverrides) {
        this.index = index;
        this.candidates = new LinkedHashSet<>(candidates);
        this.selectedUnits.clear();
        this.selectedUnits.addAll(selected);
        this.typesByQualifiedName.clear();
        this.typesByShortName.clear();
        this.reachable.clear();
        this.pending.clear();
        this.activeUnits.clear();
        this.opaqueTypes.clear();

        this.indexStaticTypes(sourceOverrides);
        this.seedSelectedUnits(sourceOverrides);
        this.walkReachableMethods(sourceOverrides);

        Set<CSharpIndex.Unit> retained = new LinkedHashSet<>(this.candidates);
        Map<Path, String> rewritten = new LinkedHashMap<>(sourceOverrides);
        List<String> removed = new ArrayList<>();
        List<String> opaque = new ArrayList<>();

        for (StaticType type : this.typesByQualifiedName.values()) {
            boolean selectedType = type.parts.stream().anyMatch(part -> this.selectedUnits.contains(part.unit));
            boolean used = selectedType || this.opaqueTypes.contains(type.qualifiedName) ||
                    type.methods.stream().anyMatch(this.reachable::contains);

            if (!used) {
                for (ClassPart part : type.parts) {
                    retained.remove(part.unit);
                    rewritten.remove(part.unit.file());
                }
                continue;
            }

            if (selectedType || this.opaqueTypes.contains(type.qualifiedName) || !type.safeToPrune) {
                if (!selectedType && (!type.safeToPrune || this.opaqueTypes.contains(type.qualifiedName))) {
                    opaque.add(type.qualifiedName);
                    this.reachable.addAll(type.methods);
                }
                continue;
            }

            for (ClassPart part : type.parts) {
                List<Edit> edits = new ArrayList<>();
                List<MethodRef> removedInPart = new ArrayList<>();
                for (MethodRef method : part.methods) {
                    if (!this.reachable.contains(method)) {
                        edits.add(new Edit(method.start, method.end));
                        removedInPart.add(method);
                        removed.add(type.qualifiedName + "::" + method.name);
                    }
                }

                String source = sourceOverrides.getOrDefault(part.unit.file(), part.unit.source());
                char[] referenceScan = CSharpIndex.maskLiteralsAndComments(source).toCharArray();
                for (MethodRef method : removedInPart) {
                    blank(referenceScan, method.start, method.end);
                }
                for (NestedType nested : part.nestedTypes) {
                    if (nested.externallyVisible) {
                        continue;
                    }
                    blank(referenceScan, nested.start, nested.end);
                    Pattern reference = Pattern.compile("\\b" + Pattern.quote(nested.name) + "\\b");
                    if (!reference.matcher(new String(referenceScan)).find()) {
                        edits.add(new Edit(nested.start, nested.end));
                        removed.add(type.qualifiedName + "::nested " + nested.name);
                    }
                }

                if (!edits.isEmpty()) {
                    rewritten.put(part.unit.file(), apply(source, edits));
                }
            }
        }

        // Remove only generated imports that point to source namespaces no longer present.
        // 删除仅由已裁剪辅助类提供、且最终工程中已不存在的源码命名空间引用。
        this.removeDanglingSourceUsings(retained, rewritten);

        List<String> warnings = new ArrayList<>();
        if (!removed.isEmpty()) {
            removed.sort(String::compareTo);
            warnings.add("已裁剪 " + removed.size() + " 个未使用的静态辅助成员 / " +
                    "Removed " + removed.size() + " unreachable static helper members: " + removed);
        }
        if (!opaque.isEmpty()) {
            opaque.sort(String::compareTo);
            warnings.add("以下静态类存在状态或动态调用，已完整保留 / " +
                    "Static helpers kept intact because their behavior is not statically safe to trim: " + opaque);
        }

        return new Result(Collections.unmodifiableSet(new LinkedHashSet<>(retained)),
                Collections.unmodifiableMap(new LinkedHashMap<>(rewritten)),
                List.copyOf(warnings), List.copyOf(removed));
    }

    private void indexStaticTypes(Map<Path, String> sourceOverrides) {
        for (CSharpIndex.Unit unit : this.candidates) {
            if (unit.declared().size() != 1) {
                continue;
            }

            String source = sourceOverrides.getOrDefault(unit.file(), unit.source());
            String code = CSharpIndex.maskLiteralsAndComments(source);
            Matcher matcher = STATIC_CLASS.matcher(code);
            while (matcher.find()) {
                String name = matcher.group("name");
                if (!unit.declared().contains(name) || !matcher.group("modifiers").matches("(?s).*\\bstatic\\b.*")) {
                    continue;
                }

                int open = matcher.end() - 1;
                int close = matchingBrace(code, open);
                if (close < 0) {
                    continue;
                }

                String qualified = unit.namespace().isBlank() ? name : unit.namespace() + "." + name;
                StaticType type = this.typesByQualifiedName.computeIfAbsent(qualified,
                        ignored -> new StaticType(qualified, name));
                ClassPart part = new ClassPart(unit, open, close, code);
                if (Pattern.compile("\\bpublic\\b").matcher(matcher.group("modifiers")).find()) {
                    type.safeToPrune = false;
                }
                part.methods.addAll(parseMethods(type, part));
                part.nestedTypes.addAll(parseNestedTypes(part));
                part.safeToPrune = isSafeToPrune(type, part, source);
                type.parts.add(part);
                type.methods.addAll(part.methods);
                type.safeToPrune &= part.safeToPrune;
            }
        }

        for (StaticType type : this.typesByQualifiedName.values()) {
            this.typesByShortName.computeIfAbsent(type.shortName, ignored -> new ArrayList<>()).add(type);
            if (type.methods.isEmpty()) {
                type.safeToPrune = false;
            }
        }
    }

    private List<MethodRef> parseMethods(StaticType type, ClassPart part) {
        List<MethodRef> methods = new ArrayList<>();
        String code = part.code;
        int depth = 1;
        int memberStart = part.open + 1;
        int cursor = part.open + 1;

        while (cursor < part.close) {
            char current = code.charAt(cursor);
            if (depth == 1 && current == '(') {
                int nameEnd = cursor;
                int nameCursor = nameEnd - 1;
                while (nameCursor >= memberStart && Character.isWhitespace(code.charAt(nameCursor))) {
                    nameCursor--;
                }
                if (nameCursor >= memberStart && code.charAt(nameCursor) == '>') {
                    int genericDepth = 1;
                    nameCursor--;
                    while (nameCursor >= memberStart && genericDepth > 0) {
                        char generic = code.charAt(nameCursor);
                        if (generic == '>') {
                            genericDepth++;
                        } else if (generic == '<') {
                            genericDepth--;
                        }
                        nameCursor--;
                    }
                }
                int nameStart = nameCursor + 1;
                while (nameCursor >= memberStart && Character.isWhitespace(code.charAt(nameCursor))) {
                    nameCursor--;
                }
                while (nameCursor >= memberStart && isIdentifierPart(code.charAt(nameCursor))) {
                    nameCursor--;
                }
                nameStart = nameCursor + 1;
                String name = code.substring(nameStart, nameEnd).replaceAll("<.*$", "").trim();
                int closeParen = matchingParen(code, cursor, part.close);
                if (closeParen < 0) {
                    cursor++;
                    continue;
                }

                int tail = skipWhitespace(code, closeParen + 1, part.close);
                if (code.startsWith("where", tail)) {
                    tail = skipMethodConstraints(code, tail, part.close);
                }
                tail = skipWhitespace(code, tail, part.close);
                int bodyKind = methodBodyKind(code, tail, part.close);
                String prefix = code.substring(memberStart, nameStart);
                String declarationPrefix = ATTRIBUTES.matcher(prefix).replaceAll(" ").trim();
                boolean declaration = !name.isBlank() && !declarationPrefix.contains("=") &&
                        declarationPrefix.matches("(?s).*[A-Za-z_][\\w.<>,?\\[\\]:]*");

                if (declaration && bodyKind != 0) {
                    if (name.equals(type.shortName)) {
                        type.safeToPrune = false;
                    }
                    int bodyStart;
                    int bodyEnd;
                    int end;
                    if (bodyKind == '{') {
                        int closeBody = matchingBrace(code, tail);
                        if (closeBody < 0 || closeBody > part.close) {
                            type.safeToPrune = false;
                            cursor++;
                            continue;
                        }
                        bodyStart = tail + 1;
                        bodyEnd = closeBody;
                        end = closeBody + 1;
                    } else if (bodyKind == '=') {
                        bodyStart = tail + 2;
                        int semicolon = findExpressionEnd(code, bodyStart, part.close);
                        if (semicolon < 0) {
                            type.safeToPrune = false;
                            cursor++;
                            continue;
                        }
                        bodyEnd = semicolon;
                        end = semicolon + 1;
                    } else {
                        bodyStart = bodyEnd = tail;
                        end = tail + 1;
                    }

                    MethodRef method = new MethodRef(type, part, name, memberStart, end,
                            bodyStart, bodyEnd);
                    methods.add(method);
                    cursor = end;
                    memberStart = end;
                    continue;
                }
            }

            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 1) {
                    memberStart = cursor + 1;
                }
            } else if (current == ';' && depth == 1) {
                memberStart = cursor + 1;
            }
            cursor++;
        }

        return methods;
    }

    private List<NestedType> parseNestedTypes(ClassPart part) {
        List<NestedType> nestedTypes = new ArrayList<>();
        Matcher matcher = NESTED_TYPE.matcher(part.code);
        while (matcher.find()) {
            if (matcher.start() <= part.open || matcher.end() > part.close) {
                continue;
            }
            int depth = braceDepth(part.code, part.open + 1, matcher.start());
            if (depth != 1) {
                continue;
            }
            int open = matcher.end() - 1;
            int close = matchingBrace(part.code, open);
            if (close < 0 || close > part.close) {
                continue;
            }
            int memberStart = findMemberStart(part.code, part.open + 1, matcher.start());
            String modifiers = matcher.group("modifiers");
            boolean visible = Pattern.compile("\\b(?:public|internal|protected)\\b").matcher(modifiers).find();
            nestedTypes.add(new NestedType(matcher.group("name"), memberStart, close + 1, visible));
        }
        return nestedTypes;
    }

    private static int braceDepth(String code, int start, int end) {
        int depth = 1;
        for (int i = start; i < end; i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return depth;
    }

    private static int findMemberStart(String code, int start, int end) {
        int depth = 1;
        int memberStart = start;
        for (int i = start; i < end; i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 1) {
                    memberStart = i + 1;
                }
            } else if (c == ';' && depth == 1) {
                memberStart = i + 1;
            }
        }
        return memberStart;
    }

    private static void blank(char[] source, int start, int end) {
        int from = Math.max(0, start);
        int to = Math.min(source.length, end);
        for (int i = from; i < to; i++) {
            if (source[i] != '\n' && source[i] != '\r') {
                source[i] = ' ';
            }
        }
    }

    private boolean isSafeToPrune(StaticType type, ClassPart part, String source) {
        String body = part.code.substring(part.open + 1, part.close);
        String withoutMethods = body;
        List<Edit> methodEdits = new ArrayList<>();
        for (MethodRef method : part.methods) {
            methodEdits.add(new Edit(method.start - part.open - 1, method.end - part.open - 1));
        }
        withoutMethods = apply(withoutMethods, methodEdits);

        if (Pattern.compile("\\bstatic\\s+" + Pattern.quote(type.shortName) + "\\s*\\(").matcher(body).find()) {
            return false;
        }
        if (Pattern.compile("\\bstatic\\s+[^;{}]+;").matcher(withoutMethods).find()) {
            return false;
        }
        if (REFLECTION.matcher(body).find() || Pattern.compile("\\bdynamic\\b|\\bevent\\b|\\bget\\s*(?:;|\\{)|\\bset\\s*(?:;|\\{)|\\binit\\s*(?:;|\\{)").matcher(body).find()) {
            return false;
        }
        if (Pattern.compile("\\[\\s*(?:HarmonyPatch|RuntimeInitializeOnLoadMethod|Preserve|InitializeOnLoad)").matcher(part.code).find()) {
            return false;
        }

        // A static initializer can have observable side effects even if no field is read.
        // 静态字段初始化可能有副作用；无法证明无副作用时不裁剪该类型。
        int depth = 1;
        int parens = 0;
        int brackets = 0;
        for (int i = 0; i < withoutMethods.length(); i++) {
            char c = withoutMethods.charAt(i);
            if (c == '(') {
                parens++;
            } else if (c == ')') {
                parens--;
            } else if (c == '[') {
                brackets++;
            } else if (c == ']') {
                brackets--;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == '=' && depth == 1 && parens == 0 && brackets == 0) {
                if (i + 1 < withoutMethods.length() && (withoutMethods.charAt(i + 1) == '>' || withoutMethods.charAt(i + 1) == '=')) {
                    continue;
                }
                if (i > 0 && (withoutMethods.charAt(i - 1) == '=' || withoutMethods.charAt(i - 1) == '!' || withoutMethods.charAt(i - 1) == '<' || withoutMethods.charAt(i - 1) == '>')) {
                    continue;
                }
                return false;
            }
        }

        return true;
    }

    private void seedSelectedUnits(Map<Path, String> sourceOverrides) {
        Deque<CSharpIndex.Unit> units = new ArrayDeque<>(this.selectedUnits);
        Set<CSharpIndex.Unit> visited = new HashSet<>();
        while (!units.isEmpty()) {
            CSharpIndex.Unit unit = units.removeFirst();
            if (!visited.add(unit)) {
                continue;
            }
            this.activeUnits.add(unit);
            String source = sourceOverrides.getOrDefault(unit.file(), unit.source());
            this.scanSource(unit, source, null, null, source);

            for (String name : unit.declared()) {
                String qualified = unit.namespace().isBlank() ? name : unit.namespace() + "." + name;
                for (CSharpIndex.Unit part : this.index.sameType(qualified)) {
                    if (this.candidates.contains(part) && !visited.contains(part)) {
                        units.addLast(part);
                    }
                }
            }
            this.enqueueNonStaticDependencies(unit, source, sourceOverrides);
        }
    }

    private void walkReachableMethods(Map<Path, String> sourceOverrides) {
        Set<MethodRef> processed = new HashSet<>();
        while (!this.pending.isEmpty()) {
            MethodRef method = this.pending.removeFirst();
            if (!processed.add(method)) {
                continue;
            }
            String body = method.part.code.substring(method.bodyStart, method.bodyEnd);
            String rawBody = method.part.unit.source().substring(method.bodyStart, method.bodyEnd);
            this.scanSource(method.part.unit, body, method.type, method, rawBody);
            this.enqueueNonStaticDependencies(method.part.unit, body, sourceOverrides);
        }
    }

    private void enqueueNonStaticDependencies(CSharpIndex.Unit owner, String source,
                                              Map<Path, String> sourceOverrides) {
        String code = CSharpIndex.maskLiteralsAndComments(source);
        Set<String> identifiers = new LinkedHashSet<>();
        Matcher tokens = IDENTIFIER.matcher(code);
        while (tokens.find()) {
            identifiers.add(tokens.group());
        }
        CSharpIndex.Unit view = new CSharpIndex.Unit(owner.file(), owner.namespace(), owner.declared(),
                owner.usings(), source, code, identifiers);
        List<String> errors = new ArrayList<>();
        for (CSharpIndex.Unit dependency : this.index.dependencies(view, errors)) {
            if (!this.candidates.contains(dependency) || this.isStaticHelper(dependency)) {
                continue;
            }
            if (SettingsDetacher.isHostManaged(this.index, dependency) || !this.activeUnits.add(dependency)) {
                continue;
            }
            String dependencySource = sourceOverrides.getOrDefault(dependency.file(), dependency.source());
            this.scanSource(dependency, dependencySource, null, null, dependencySource);
            this.enqueueNonStaticDependencies(dependency, dependencySource, sourceOverrides);
        }
    }

    private void scanSource(CSharpIndex.Unit unit, String source, StaticType currentType,
                            MethodRef currentMethod, String rawSource) {
        String code = CSharpIndex.maskLiteralsAndComments(source);
        for (StaticType target : this.typesByQualifiedName.values()) {
            Pattern reflectionName = Pattern.compile("\"[^\"]*\\b" +
                    Pattern.quote(target.shortName) + "\\b[^\"]*\"");
            if (reflectionName.matcher(rawSource).find()) {
                this.markOpaque(target);
            }
            Pattern typePattern = Pattern.compile("\\b" + Pattern.quote(target.shortName) + "\\b");
            Matcher occurrences = typePattern.matcher(code);
            while (occurrences.find()) {
                int start = occurrences.start();
                if (isUsingDirective(code, start)) {
                    continue;
                }
                int after = skipWhitespace(code, occurrences.end(), code.length());
                if (after < code.length() && code.charAt(after) == '.') {
                    int memberStart = skipWhitespace(code, after + 1, code.length());
                    int memberEnd = memberStart;
                    while (memberEnd < code.length() && isIdentifierPart(code.charAt(memberEnd))) {
                        memberEnd++;
                    }
                    if (memberEnd == memberStart) {
                        this.markOpaque(target);
                        continue;
                    }
                    String member = code.substring(memberStart, memberEnd);
                    if (target.methods.stream().anyMatch(method -> method.name.equals(member))) {
                        this.markMethods(target, member);
                    } else {
                        this.markOpaque(target);
                    }
                } else if (currentType == null || !currentType.qualifiedName.equals(target.qualifiedName)) {
                    this.markOpaque(target);
                }
            }
        }

        String importCode = CSharpIndex.maskLiteralsAndComments(unit.source());
        Matcher staticImports = STATIC_USING.matcher(importCode);
        while (staticImports.find()) {
            String imported = staticImports.group(1);
            String shortName = imported.substring(imported.lastIndexOf('.') + 1);
            for (StaticType target : this.typesByShortName.getOrDefault(shortName, List.of())) {
                for (MethodRef method : target.methods) {
                    Pattern use = Pattern.compile("\\b" + Pattern.quote(method.name) + "\\b");
                    if (use.matcher(code).find()) {
                        this.markMethod(method);
                    }
                }
            }
        }

        Matcher aliases = USING_ALIAS.matcher(importCode);
        while (aliases.find()) {
            String alias = aliases.group(1);
            String targetName = aliases.group(2);
            String shortName = targetName.substring(targetName.lastIndexOf('.') + 1);
            Matcher aliasUses = Pattern.compile("\\b" + Pattern.quote(alias) + "\\s*\\.\\s*([A-Za-z_]\\w*)").matcher(code);
            while (aliasUses.find()) {
                String member = aliasUses.group(1);
                for (StaticType target : this.typesByShortName.getOrDefault(shortName, List.of())) {
                    if (target.methods.stream().anyMatch(method -> method.name.equals(member))) {
                        this.markMethods(target, member);
                    } else {
                        this.markOpaque(target);
                    }
                }
            }
        }

        if (currentType != null) {
            for (MethodRef candidate : currentType.methods) {
                if (currentMethod != null && candidate.equals(currentMethod)) {
                    continue;
                }
                Pattern call = Pattern.compile("\\b" + Pattern.quote(candidate.name) + "\\s*\\(");
                if (call.matcher(code).find()) {
                    this.markMethod(candidate);
                }
            }
        }
    }

    private void markMethods(StaticType type, String name) {
        List<MethodRef> methods = type.methods.stream().filter(method -> method.name.equals(name)).toList();
        if (methods.isEmpty()) {
            this.markOpaque(type);
            return;
        }
        for (MethodRef method : methods) {
            this.markMethod(method);
        }
    }

    private void markMethod(MethodRef method) {
        if (this.reachable.add(method)) {
            if (!method.type.safeToPrune) {
                this.markOpaque(method.type);
            }
            this.pending.addLast(method);
        }
    }

    private void markOpaque(StaticType type) {
        if (this.opaqueTypes.add(type.qualifiedName)) {
            this.reachable.addAll(type.methods);
            this.pending.addAll(type.methods);
        }
    }

    private boolean isStaticHelper(CSharpIndex.Unit unit) {
        return this.typesByQualifiedName.values().stream().anyMatch(type ->
                type.parts.stream().anyMatch(part -> part.unit.equals(unit)));
    }

    private static boolean isUsingDirective(String code, int position) {
        int lineStart = code.lastIndexOf('\n', Math.max(0, position - 1)) + 1;
        String prefix = code.substring(lineStart, position).trim();
        return prefix.startsWith("using ") || prefix.startsWith("global using ");
    }

    private void removeDanglingSourceUsings(Set<CSharpIndex.Unit> retained, Map<Path, String> rewritten) {
        Set<String> sourceNamespaces = new HashSet<>();
        Set<String> retainedNamespaces = new HashSet<>();
        Set<String> generatedNamespaces = new HashSet<>();
        for (CSharpIndex.Unit unit : this.index.units().values()) {
            if (!unit.namespace().isBlank()) {
                sourceNamespaces.add(unit.namespace());
                String fileName = unit.file().getFileName().toString();
                if (fileName.equals("Plugin.cs") || fileName.equals("ModLocalization.cs") ||
                        fileName.equals("ModLanguageService.cs")) {
                    generatedNamespaces.add(unit.namespace());
                }
            }
        }
        for (CSharpIndex.Unit unit : retained) {
            if (!unit.namespace().isBlank()) {
                retainedNamespaces.add(unit.namespace());
            }
        }

        Pattern usingPattern = Pattern.compile(
                "(?m)^[ \t]*(?:global\\s+)?using\\s+(static\\s+)?([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)\\s*;[ \t]*(?:\\r?\\n)?");
        Pattern aliasPattern = Pattern.compile(
                "(?m)^[ \t]*(?:global\\s+)?using\\s+([A-Za-z_]\\w*)\\s*=\\s*(?:global::)?([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)\\s*;[ \t]*(?:\\r?\\n)?");

        for (CSharpIndex.Unit unit : retained) {
            String source = rewritten.getOrDefault(unit.file(), unit.source());
            String masked = CSharpIndex.maskLiteralsAndComments(source);
            List<Edit> edits = new ArrayList<>();
            Matcher matcher = usingPattern.matcher(masked);
            while (matcher.find()) {
                boolean staticImport = matcher.group(1) != null;
                String imported = matcher.group(2);
                boolean generated = generatedNamespaces.contains(imported);
                boolean danglingNamespace = !staticImport && sourceNamespaces.contains(imported) &&
                        !retainedNamespaces.contains(imported) && !generated;
                boolean danglingType = staticImport && !this.index.sameType(imported).isEmpty() &&
                        this.index.sameType(imported).stream().noneMatch(retained::contains);
                if (danglingNamespace || danglingType) {
                    edits.add(new Edit(matcher.start(), matcher.end()));
                }
            }

            Matcher aliases = aliasPattern.matcher(masked);
            while (aliases.find()) {
                String target = aliases.group(2);
                if (!this.index.sameType(target).isEmpty() &&
                        this.index.sameType(target).stream().noneMatch(retained::contains)) {
                    edits.add(new Edit(aliases.start(), aliases.end()));
                }
            }

            if (!edits.isEmpty()) {
                rewritten.put(unit.file(), apply(source, edits));
            }
        }
    }

    private static int methodBodyKind(String code, int position, int limit) {
        if (position >= limit) {
            return 0;
        }
        if (code.charAt(position) == '{' || code.charAt(position) == ';') {
            return code.charAt(position);
        }
        if (code.startsWith("=>", position)) {
            return '=';
        }
        return 0;
    }

    private static int skipMethodConstraints(String code, int start, int limit) {
        int parens = 0;
        for (int i = start; i < limit; i++) {
            char c = code.charAt(i);
            if (c == '(') {
                parens++;
            } else if (c == ')') {
                parens--;
            } else if (parens == 0 && (c == '{' || c == ';' || code.startsWith("=>", i))) {
                return i;
            }
        }
        return limit;
    }

    private static int findExpressionEnd(String code, int start, int limit) {
        int parens = 0;
        int brackets = 0;
        int braces = 0;
        for (int i = start; i < limit; i++) {
            char c = code.charAt(i);
            if (c == '(') {
                parens++;
            } else if (c == ')') {
                parens--;
            } else if (c == '[') {
                brackets++;
            } else if (c == ']') {
                brackets--;
            } else if (c == '{') {
                braces++;
            } else if (c == '}') {
                braces--;
            } else if (c == ';' && parens == 0 && brackets == 0 && braces == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int matchingParen(String code, int start, int limit) {
        int depth = 0;
        for (int i = start; i < limit; i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int matchingBrace(String code, int start) {
        int depth = 0;
        for (int i = start; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int skipWhitespace(String code, int position, int limit) {
        int result = position;
        while (result < limit && Character.isWhitespace(code.charAt(result))) {
            result++;
        }
        return result;
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String apply(String original, List<Edit> edits) {
        StringBuilder result = new StringBuilder(original);
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        for (Edit edit : edits) {
            result.replace(edit.start, edit.end, "");
        }
        return result.toString();
    }

    private record Edit(int start, int end) {
    }

    private static class StaticType {
        private final String qualifiedName;
        private final String shortName;
        private final List<ClassPart> parts = new ArrayList<>();
        private final List<MethodRef> methods = new ArrayList<>();
        private boolean safeToPrune = true;

        private StaticType(String qualifiedName, String shortName) {
            this.qualifiedName = qualifiedName;
            this.shortName = shortName;
        }
    }

    private static class ClassPart {
        private final CSharpIndex.Unit unit;
        private final int open;
        private final int close;
        private final String code;
        private final List<MethodRef> methods = new ArrayList<>();
        private final List<NestedType> nestedTypes = new ArrayList<>();
        private boolean safeToPrune;

        private ClassPart(CSharpIndex.Unit unit, int open, int close, String code) {
            this.unit = unit;
            this.open = open;
            this.close = close;
            this.code = code;
        }
    }

    private record NestedType(String name, int start, int end, boolean externallyVisible) {
    }

    private static class MethodRef {
        private final StaticType type;
        private final ClassPart part;
        private final String name;
        private final int start;
        private final int end;
        private final int bodyStart;
        private final int bodyEnd;

        private MethodRef(StaticType type, ClassPart part, String name, int start, int end,
                          int bodyStart, int bodyEnd) {
            this.type = type;
            this.part = part;
            this.name = name;
            this.start = start;
            this.end = end;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof MethodRef method)) {
                return false;
            }
            return this.part.unit.file().equals(method.part.unit.file()) && this.start == method.start;
        }

        @Override
        public int hashCode() {
            return 31 * this.part.unit.file().hashCode() + this.start;
        }
    }
}
