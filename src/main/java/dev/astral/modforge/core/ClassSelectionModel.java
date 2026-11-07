package dev.astral.modforge.core;

import java.nio.file.Path;
import java.util.*;

/**
 * Selection graph shared by the Rider tree and tests.
 * 勾选功能会连带依赖；取消依赖会撤销所有依靠它的根功能，避免导出残缺调用链。
 */
public class ClassSelectionModel {
    public enum State { NONE, PARTIAL, ALL }

    private final CSharpIndex index;
    private final Map<Path, Set<Path>> edges = new HashMap<>();
    private final Set<Path> eligible = new TreeSet<>();
    private final LinkedHashSet<Path> requested = new LinkedHashSet<>();
    private final LinkedHashSet<Path> checked = new LinkedHashSet<>();
    private final List<String> warnings = new ArrayList<>();

    public ClassSelectionModel(CSharpIndex index) {
        this.index = index;
        for (CSharpIndex.Unit unit : index.units().values())
            if (!SettingsDetacher.isHostManaged(index, unit)) eligible.add(unit.file());
        for (Path file : eligible) {
            CSharpIndex.Unit from = index.unit(file);
            List<String> errors = new ArrayList<>();
            Set<Path> dependencies = new TreeSet<>();
            for (CSharpIndex.Unit dep : index.dependencies(from, errors))
                if (eligible.contains(dep.file())) dependencies.add(dep.file());
            for (String name : from.declared()) {
                String fq = from.namespace().isBlank() ? name : from.namespace() + "." + name;
                for (CSharpIndex.Unit part : index.sameType(fq))
                    if (eligible.contains(part.file()) && !part.file().equals(file)) dependencies.add(part.file());
            }
            edges.put(file, Set.copyOf(dependencies));
            warnings.addAll(errors);
        }
    }

    public Set<Path> eligible() { return Collections.unmodifiableSet(eligible); }
    public Set<Path> checked() { return Collections.unmodifiableSet(checked); }
    /** Manually chosen entrypoints; generated dependency files do not become auto-start features. */
    public Set<Path> requested() { return Collections.unmodifiableSet(requested); }
    public List<String> warnings() { return List.copyOf(warnings); }

    public void check(Path path) {
        path = normalized(path);
        if (!eligible.contains(path)) return;
        requested.add(path);
        rebuild();
    }

    public void checkAll(Collection<Path> paths) {
        for (Path p : paths) { Path path = normalized(p); if (eligible.contains(path)) requested.add(path); }
        rebuild();
    }

    public void uncheck(Path path) { uncheckAll(List.of(path)); }

    public void uncheckAll(Collection<Path> paths) {
        Set<Path> toRemove = new HashSet<>();
        for (Path p : paths) toRemove.add(normalized(p));
        // Reject every selected root which transitively calls any deselected class.
        // 连同调用此类的功能一并取消；保留其它不依赖它的已选功能。
        requested.removeIf(root -> {
            Set<Path> closure = closure(root);
            return closure.stream().anyMatch(toRemove::contains);
        });
        rebuild();
    }

    public State state(Collection<Path> members) {
        int total = 0, selected = 0;
        for (Path path : members) if (eligible.contains(normalized(path))) {
            total++;
            if (checked.contains(normalized(path))) selected++;
        }
        if (selected == 0) return State.NONE;
        return total == selected ? State.ALL : State.PARTIAL;
    }

    private Path normalized(Path path) { return (path.isAbsolute() ? path : index.root().resolve(path)).normalize(); }
    private Set<Path> closure(Path root) {
        Set<Path> reached = new LinkedHashSet<>();
        Deque<Path> queue = new ArrayDeque<>(); queue.add(root);
        while (!queue.isEmpty()) {
            Path path = queue.removeFirst();
            if (!eligible.contains(path) || !reached.add(path)) continue;
            queue.addAll(edges.getOrDefault(path, Set.of()));
        }
        return reached;
    }
    private void rebuild() {
        checked.clear();
        for (Path root : requested) checked.addAll(closure(root));
    }
}
