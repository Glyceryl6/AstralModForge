package dev.astral.modforge.ide;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Rider may open a solution above several .csproj folders / Rider 解决方案根目录未必是 MOD 工程根目录。
 */
final class ProjectRoots {

    static Path resolve(Path projectBase, List<Path> selected) {
        if (!selected.isEmpty()) {
            Path dir = selected.getFirst().getParent();
            while (dir != null && dir.startsWith(projectBase)) {
                if (hasProject(dir)) return dir;
                dir = dir.getParent();
            }
        }
        if (hasProject(projectBase)) return projectBase;
        try (Stream<Path> children = Files.list(projectBase)) {
            List<Path> matches = children.filter(Files::isDirectory).filter(ProjectRoots::hasProject).toList();
            if (matches.size() == 1) return matches.getFirst();
        } catch (Exception ignored) {}
        return projectBase;
    }

    private static boolean hasProject(Path directory) {
        try (Stream<Path> children = Files.list(directory)) {
            return children.anyMatch(p -> Files.isRegularFile(p) && p.toString().endsWith(".csproj"));
        } catch (Exception ignored) {
            return false;
        }
    }

}