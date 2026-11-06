package dev.astral.modforge.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable export request / 不可变导出任务，避免后台任务读取到变化中的界面状态。
 */
public record ForgeSpec(Path sourceRoot, String modId, List<Path> selected, Path destination,
                        LanguageMode languageMode, String locale,
                        String loadEntry, String updateEntry,
                        boolean build, boolean copyToGame, Path gameRoot) {
    private static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");

    public enum LanguageMode {EMBEDDED, INLINE}

    public ForgeSpec {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(languageMode, "languageMode");
        if (!VALID_ID.matcher(modId).matches() || modId.length() > 100)
            throw new IllegalArgumentException("非法 MOD ID / Invalid MOD ID: " + modId);
        if (!"zh_cn".equals(locale) && !"en_us".equals(locale))
            throw new IllegalArgumentException("只支持 zh_cn 或 en_us / Unsupported locale");
        if (copyToGame && !build) throw new IllegalArgumentException("复制游戏前必须编译 / Build first");
        sourceRoot = sourceRoot.toAbsolutePath().normalize();
        destination = (destination == null ? sourceRoot.resolve("AstralModForge") : destination)
                .toAbsolutePath().normalize();
        selected = List.copyOf(selected);
        loadEntry = loadEntry == null ? "" : loadEntry.trim();
        updateEntry = updateEntry == null ? "" : updateEntry.trim();
        gameRoot = gameRoot == null ? null : gameRoot.toAbsolutePath().normalize();
    }

    public String safeName() {
        String name = modId.replaceAll("[^a-zA-Z0-9_]", "_");
        if (Character.isDigit(name.charAt(0))) name = "Mod_" + name;
        return name;
    }

    public String pluginNamespace() {
        return "AstralModForge.Generated." + safeName();
    }
}
