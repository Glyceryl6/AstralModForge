package dev.astral.modforge.core;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Selection graph shared by the Rider tree and tests.
 * 勾选功能会连带依赖；取消依赖会撤销所有依靠它的根功能，避免导出残缺调用链。
 */
public class ClassSelectionModel {
    public enum State {
        NONE, PARTIAL, ALL
    }

    private final CSharpIndex index;
    private final Map<Path, Set<Path>> edges = new HashMap<>();
    private final Set<Path> eligible = new TreeSet<>();
    private final LinkedHashSet<Path> requested = new LinkedHashSet<>();
    private final LinkedHashSet<Path> checked = new LinkedHashSet<>();
    private final List<String> warnings = new ArrayList<>();

    public ClassSelectionModel(CSharpIndex index) {
        this.index = index;
        for (CSharpIndex.Unit unit : index.units().values()) {
            if (!SettingsDetacher.isHostManaged(index, unit)) {
                this.eligible.add(unit.file());
            }
        }

        for (Path file : this.eligible) {
            CSharpIndex.Unit from = index.unit(file);
            List<String> errors = new ArrayList<>();
            Set<Path> dependencies = new TreeSet<>();
            for (CSharpIndex.Unit dependency : index.dependencies(from, errors)) {
                if (this.eligible.contains(dependency.file())) {
                    dependencies.add(dependency.file());
                }
            }
            for (String name : from.declared()) {
                String qualified = from.namespace().isBlank() ? name : from.namespace() + "." + name;
                for (CSharpIndex.Unit part : index.sameType(qualified)) {
                    if (this.eligible.contains(part.file()) && !part.file().equals(file)) {
                        dependencies.add(part.file());
                    }
                }
            }
            this.edges.put(file, Set.copyOf(dependencies));
            this.warnings.addAll(errors);
        }
    }

    public Set<Path> eligible() {
        return Collections.unmodifiableSet(this.eligible);
    }

    public Set<Path> checked() {
        return Collections.unmodifiableSet(this.checked);
    }

    /** Manually chosen entrypoints; generated dependency files do not become auto-start features. */
    public Set<Path> requested() {
        return Collections.unmodifiableSet(this.requested);
    }

    public List<String> warnings() {
        return List.copyOf(this.warnings);
    }

    public void check(Path path) {
        path = normalized(path);
        if (!this.eligible.contains(path)) {
            return;
        }
        this.requested.add(path);
        rebuild();
    }

    public void checkAll(Collection<Path> paths) {
        for (Path item : paths) {
            Path path = normalized(item);
            if (this.eligible.contains(path)) {
                this.requested.add(path);
            }
        }
        rebuild();
    }

    public void uncheck(Path path) {
        uncheckAll(List.of(path));
    }

    public void uncheckAll(Collection<Path> paths) {
        Set<Path> toRemove = new HashSet<>();
        for (Path path : paths) {
            toRemove.add(normalized(path));
        }

        // 连同调用该依赖的功能一并取消；不影响其它已选功能。
        // Remove roots that transitively require a deselected class.
        this.requested.removeIf(root -> closure(root).stream().anyMatch(toRemove::contains));
        rebuild();
    }

    public State state(Collection<Path> members) {
        int total = 0;
        int selected = 0;
        for (Path path : members) {
            if (this.eligible.contains(normalized(path))) {
                total++;
                if (this.checked.contains(normalized(path))) {
                    selected++;
                }
            }
        }
        if (selected == 0) {
            return State.NONE;
        }
        return total == selected ? State.ALL : State.PARTIAL;
    }

    private Path normalized(Path path) {
        return (path.isAbsolute() ? path : this.index.root().resolve(path)).normalize();
    }

    private Set<Path> closure(Path root) {
        Set<Path> reached = new LinkedHashSet<>();
        Deque<Path> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Path path = queue.removeFirst();
            if (!this.eligible.contains(path) || !reached.add(path)) {
                continue;
            }
            queue.addAll(this.edges.getOrDefault(path, Set.of()));
        }
        return reached;
    }

    private void rebuild() {
        this.checked.clear();
        for (Path root : this.requested) {
            this.checked.addAll(closure(root));
        }
    }
}
