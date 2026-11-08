package dev.astral.modforge.ide;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Rider may open a solution above several .csproj folders. / 解决方案根目录未必是 MOD 工程根目录。 */
class ProjectRoots {
    static Path resolve(Path projectBase, List<Path> selected) {
        if (!selected.isEmpty()) {
            Path directory = selected.get(0).getParent();
            while (directory != null && directory.startsWith(projectBase)) {
                if (hasProject(directory)) {
                    return directory;
                }
                directory = directory.getParent();
            }
        }

        if (hasProject(projectBase)) {
            return projectBase;
        }

        try (Stream<Path> children = Files.list(projectBase)) {
            List<Path> matches = children.filter(Files::isDirectory)
                    .filter(ProjectRoots::hasProject).toList();
            if (matches.size() == 1) {
                return matches.get(0);
            }
        } catch (Exception ignored) {
            // 未定位到单独的 C# 项目时仍保留用户选择的根目录。
        }
        return projectBase;
    }

    private static boolean hasProject(Path directory) {
        try (Stream<Path> children = Files.list(directory)) {
            return children.anyMatch(path -> Files.isRegularFile(path)
                    && path.toString().endsWith(".csproj"));
        } catch (Exception ignored) {
            return false;
        }
    }
}
