package dev.astral.modforge.core;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

/**
 * Validate the actual game root; never copy to arbitrary user-supplied directory.
 */
public class GameInstaller {

    public static boolean valid(Path game, StringBuilder why) {
        if (game == null || !Files.isDirectory(game)) {
            why.append("目录不存在 / Directory missing");
            return false;
        }
        Path plugins = game.resolve("BepInEx/plugins"), core = game.resolve("BepInEx/core");
        if (!Files.isDirectory(plugins) || !Files.isDirectory(core)) {
            why.append("缺少 BepInEx/plugins 或 BepInEx/core");
            return false;
        }
        boolean gameAssembly = Files.isRegularFile(game.resolve("GameAssembly.dll"));
        boolean unityData = false;
        try (Stream<Path> children = Files.list(game)) {
            unityData = children.anyMatch(p -> Files.isDirectory(p) && p.getFileName().toString().endsWith("_Data"));
        } catch (IOException ignored) {
        }
        if (!gameAssembly && !unityData) {
            why.append("找不到 Unity 游戏标记（GameAssembly.dll / *_Data）");
            return false;
        }
        try {
            if (!plugins.toRealPath().startsWith(game.toRealPath())) {
                why.append("BepInEx 目录指向游戏之外 / Symlink escape");
                return false;
            }
        } catch (IOException ex) {
            why.append(ex);
            return false;
        }
        return true;
    }

    public static void copy(Path game, String safeName, Path distribution, List<String> notes) throws IOException {
        StringBuilder message = new StringBuilder();
        if (!valid(game, message)) throw new IOException(message.toString());
        Path plugins = game.resolve("BepInEx/plugins").toRealPath();
        Path target = plugins.resolve(safeName);
        Files.createDirectories(target);
        if (!target.toRealPath().startsWith(plugins))
            throw new IOException("禁止通过符号链接写入其它目录 / Symlink escape");
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        try (Stream<Path> outputs = Files.list(distribution)) {
            for (Path from : outputs.filter(p -> p.getFileName().toString().endsWith(".dll")).toList()) {
                Path dest = target.resolve(from.getFileName());
                if (Files.isSymbolicLink(dest))
                    throw new IOException("拒绝覆盖符号链接 DLL / Symlinked destination: " + dest);
                if (Files.isRegularFile(dest)) {
                    Path backup = target.resolve(from.getFileName().toString() + ".backup-" + now);
                    if (Files.exists(backup)) throw new IOException("备份已存在 / Backup exists: " + backup);
                    Files.copy(dest, backup, StandardCopyOption.COPY_ATTRIBUTES);
                }
                Path staging = target.resolve(from.getFileName() + ".new");
                Files.copy(from, staging, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(staging, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(staging, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
