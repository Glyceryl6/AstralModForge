package dev.astral.modforge.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dependencies and lifecycle are deliberately separate: references != invocations.
 * 依赖闭包不能代替生命周期；查不到可靠入口时阻止错误的一键导出。
 */
public class DependencyPlanner {
    public record Entry(String type, String method, boolean isStatic, boolean takesTime, String kind) {
        String call(String variable) {
            return (this.isStatic ? this.type : variable) + "." + this.method + "("
                    + (this.takesTime ? "UnityEngine.Time.realtimeSinceStartup" : "") + ");";
        }
    }

    public record Plan(Set<CSharpIndex.Unit> sources, List<String> warnings, List<String> errors,
                       List<Entry> loads, List<Entry> updates, Map<Path,String> rewrittenSources) {
        public boolean ready() {
            return this.errors.isEmpty();
        }
    }

    private final CSharpIndex index;
    private static final Pattern ENTRY = Pattern.compile("\\b(public|internal|private|protected)?\\s*(static\\s+)?(?:void)\\s+([A-Za-z_]\\w*)\\s*\\(([^)]*)\\)");

    public DependencyPlanner(CSharpIndex index) {
        this.index = index;
    }

    public Plan plan(ForgeSpec spec) throws IOException {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<CSharpIndex.Unit> initial = new LinkedHashSet<>();
        for (Path p : spec.selected()) {
            Path file = p.isAbsolute() ? p.normalize() : spec.sourceRoot().resolve(p).normalize();
            if (!file.startsWith(this.index.root())) { errors.add("文件位于项目之外 / Outside project: " + p); continue; }
            CSharpIndex.Unit unit = this.index.unit(file);
            if (unit == null) errors.add("类未包含于可编译源文件索引 / Not indexed: " + p);
            else if (SettingsDetacher.isHostManaged(index, unit))
                errors.add("不能选择原 MOD 的 Plugin、设置、配置或本地化类 / Host-managed class: " + p);
            else initial.add(unit);
        }
        if (initial.isEmpty()) errors.add("没有选择类文件 / No C# classes selected");
        Set<CSharpIndex.Unit> all = new LinkedHashSet<>(initial);
        Deque<CSharpIndex.Unit> queue = new ArrayDeque<>(initial);
        while (!queue.isEmpty() && all.size() <= 300) {
            CSharpIndex.Unit unit = queue.removeFirst();
            for (CSharpIndex.Unit dep : this.index.dependencies(unit, errors)) {
                if (SettingsDetacher.isHostManaged(index, dep)) continue;
                if (all.add(dep)) queue.addLast(dep);
            }
            // Partial declarations may themselves reference dependencies. Traverse their bodies as well.
            // partial 的另一个文件也可能调用其它类，必须继续遍历。
            for (String type : unit.declared()) {
                String fq = unit.namespace().isEmpty() ? type : unit.namespace() + "." + type;
                for (CSharpIndex.Unit part : this.index.sameType(fq))
                    if (!SettingsDetacher.isHostManaged(index, part) && all.add(part)) queue.addLast(part);
            }
        }
        if (all.size() > 300) errors.add("依赖闭包超过 300 个文件，请拆分功能 / Dependency closure too large");

        // Different partial declarations of the same class MUST travel together.
        // 同一个 partial class 的多个分部不允许只复制其中之一。
        for (CSharpIndex.Unit unit : new ArrayList<>(all)) {
            for (String type : unit.declared()) {
                String fq = unit.namespace().isEmpty() ? type : unit.namespace() + "." + type;
                for (CSharpIndex.Unit part : this.index.sameType(fq))
                    if (!SettingsDetacher.isHostManaged(index, part)) all.add(part);
            }
        }

        FrameworkPruner pruner = new FrameworkPruner();
        // First pass prevents a discarded Framework helper from introducing unrelated settings errors.
        FrameworkPruner.Result pruned = pruner.prune(index, all, Map.of());
        all = new LinkedHashSet<>(pruned.sources());
        SettingsDetacher.Result settings = new SettingsDetacher().transform(index, all, spec.embedDefaultConfig());
        // A boolean switch or a removed settings import may make further framework files unnecessary.
        FrameworkPruner.Result refined = pruner.prune(index, all, settings.sources());
        if (refined.sources().size() != all.size()) {
            all = new LinkedHashSet<>(refined.sources());
            settings = new SettingsDetacher().transform(index, all, spec.embedDefaultConfig());
        }
        Set<Path> removedPaths = new LinkedHashSet<>(pruned.removed());
        removedPaths.addAll(refined.removed());
        if (!removedPaths.isEmpty())
            warnings.add("已跳过 " + removedPaths.size() + " 个无须打包的 Framework 文件 / Pruned unused Framework helpers: " + removedPaths);
        if (!refined.retained().isEmpty())
            warnings.add("这些 Framework 类仍被功能引用，已保留 / Required Framework helpers retained: " + refined.retained());
        if (initial.stream().noneMatch(all::contains))
            errors.add("勾选的文件均不属于可独立运行的功能 / No executable feature after Framework pruning");
        warnings.addAll(settings.warnings());
        errors.addAll(settings.errors());
        if (spec.embedDefaultConfig()) {
            errors.addAll(new ConfigPersistenceGuard().inspect(index, all, settings.sources()));
            warnings.add("默认配置内嵌：导出的插件不会自动写入 .cfg 或语言 JSON；自定义文件写入仍需开发者检查。");
        }
        Set<CSharpIndex.Unit> selectedAfterPruning = new LinkedHashSet<>(initial);
        selectedAfterPruning.retainAll(all);

        List<Entry> loads = chooseEntries(spec.loadEntry(), all, selectedAfterPruning, Set.of("Initialize", "Install", "Init"), "Load", errors, warnings);
        List<Entry> updates = chooseEntries(spec.updateEntry(), all, selectedAfterPruning, Set.of("Tick"), "Update", errors, warnings);
        if (loads.isEmpty() && updates.isEmpty() && !initial.isEmpty())
            errors.add("未找到可安全调用的初始化或更新入口；请填写 Namespace.Type::Method / Lifecycle entry required");
        for (CSharpIndex.Unit u : all) {
            if (u.code().contains("Paths.ConfigPath") && (u.source().contains("\"AstralProbe\"") || u.source().contains("\"AstralProbe/")))
                warnings.add("源码写死 AstralProbe 的配置目录，请改成每个 MOD 专属路径 / Host configuration path in " + this.index.root().relativize(u.file()));
        }
        if (all.size() > initial.size()) warnings.add("自动包含依赖文件 " + (all.size() - initial.size()) + " 个 / Collected transitive files");
        if (all.stream().anyMatch(u -> u.source().contains("AstralParty.Modding.SDK")))
            warnings.add("引用了外部 SDK；将尝试移植源项目 ProjectReference / SDK project reference required");
        for (CSharpIndex.Unit unit : initial) {
            for (CSharpIndex.Unit other : this.index.units().values()) {
                if (all.contains(other) || !other.file().getFileName().toString().equals("HotfixWatcher.cs")) continue;
                for (String type : unit.declared()) {
                    if (other.identifiers().contains(type)) {
                        warnings.add("原 HotfixWatcher 曾调用 " + type + "；导出时不会复制整个旧入口，请核对生命周期 / Original watcher references selected class");
                        break;
                    }
                }
            }
        }
        return new Plan(Collections.unmodifiableSet(all), List.copyOf(warnings), List.copyOf(errors), List.copyOf(loads), List.copyOf(updates), settings.sources());
    }

    private List<Entry> chooseEntries(String requested, Set<CSharpIndex.Unit> all, Set<CSharpIndex.Unit> selected,
                                      Set<String> defaults, String kind, List<String> errors, List<String> warnings) {
        List<Entry> result = new ArrayList<>();
        if (!requested.isBlank()) {
            for (String input : requested.split(";")) {
                String value = input.trim();
                if (value.isEmpty()) continue;
                String[] parts = value.split("::", -1);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    errors.add(kind + " 入口格式应为 Namespace.Type::Method / Invalid entry: " + value);
                    continue;
                }
                List<Entry> candidates = entriesIn(all, kind, parts[0], parts[1], defaults);
                if (candidates.size() != 1)
                    errors.add(kind + " 入口找不到或存在歧义 / Missing or ambiguous entry: " + value);
                else result.add(candidates.get(0));
            }
        } else {
            // Automatically wire only user-selected features, never dependency helpers.
            // 仅自动启动用户主动选择的类，依赖工具类不应意外执行其初始化方法。
            for (CSharpIndex.Unit unit : selected) {
                List<Entry> found = entriesIn(List.of(unit), kind, null, null, defaults);
                if (found.size() == 1) result.add(found.get(0));
                else if (found.size() > 1)
                    warnings.add(kind + " 存在多个候选方法，请指定入口 / Ambiguous methods: " + this.index.root().relativize(unit.file()));
            }
        }
        LinkedHashMap<String,Entry> unique = new LinkedHashMap<>();
        for (Entry entry : result) unique.putIfAbsent(entry.type() + "::" + entry.method(), entry);
        return new ArrayList<>(unique.values());
    }

    private List<Entry> entriesIn(Collection<CSharpIndex.Unit> search, String kind,
                                  String wantedType, String wantedMethod, Set<String> defaults) {
        List<Entry> found = new ArrayList<>();
        for (CSharpIndex.Unit unit : search) {
            if (unit.declared().size() != 1) continue;
            String name = unit.declared().iterator().next();
            String type = unit.namespace().isEmpty() ? name : unit.namespace() + "." + name;
            if (wantedType != null && !wantedType.equals(type)) continue;
            Matcher method = ENTRY.matcher(unit.code());
            while (method.find()) {
                String label = method.group(3);
                if (wantedMethod != null ? !wantedMethod.equals(label) : !defaults.contains(label)) continue;
                if ("private".equals(method.group(1)) || "protected".equals(method.group(1))) continue;
                String parameters = method.group(4).trim();
                boolean takesTime = parameters.matches("(?:float|System\\.Single)\\s+\\w+");
                if (!parameters.isEmpty() && !(kind.equals("Update") && takesTime)) continue;
                found.add(new Entry(type,label,method.group(2)!=null,takesTime,kind));
            }
        }
        return found;
    }
}
