package dev.astral.modforge.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable export request / 不可变导出任务，避免后台任务读取到变化中的界面状态。 */
public record ForgeSpec(Path sourceRoot, String modId, List<Path> selected, Path destination,
                        LanguageMode languageMode, String locale,
                        String loadEntry, String updateEntry,
                        boolean build, boolean copyToGame, Path gameRoot, boolean exportSource,
                        boolean embedDefaultConfig) {
    private static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");

    public enum LanguageMode { EMBEDDED, INLINE }

    /** Compatibility with v0.1.0 API: callers using the original constructor still export source. */
    public ForgeSpec(Path sourceRoot, String modId, List<Path> selected, Path destination,
                     LanguageMode languageMode, String locale, String loadEntry, String updateEntry,
                     boolean build, boolean copyToGame, Path gameRoot) {
        this(sourceRoot, modId, selected, destination, languageMode, locale,
                loadEntry, updateEntry, build, copyToGame, gameRoot, true, false);
    }

    /** Compatibility with v0.2.0: persistent per-MOD configuration remains the default. */
    public ForgeSpec(Path sourceRoot, String modId, List<Path> selected, Path destination,
                     LanguageMode languageMode, String locale, String loadEntry, String updateEntry,
                     boolean build, boolean copyToGame, Path gameRoot, boolean exportSource) {
        this(sourceRoot, modId, selected, destination, languageMode, locale,
                loadEntry, updateEntry, build, copyToGame, gameRoot, exportSource, false);
    }

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
        if (!build && !exportSource) throw new IllegalArgumentException("未编译时必须导出源码 / Export source if build is disabled");
        sourceRoot = sourceRoot.toAbsolutePath().normalize();
        // A source export under an SDK-style .csproj root may create duplicate C# definitions.
        // 默认未勾选源码时输出在子目录；勾选源码时改为源项目的同级目录。
        destination = (destination == null ? (exportSource
                ? sourceRoot.resolveSibling(sourceRoot.getFileName() + "-ForgeExports")
                : sourceRoot.resolve("AstralModForge")) : destination).toAbsolutePath().normalize();
        if (exportSource && !selected.isEmpty() && destination.startsWith(sourceRoot))
            throw new IllegalArgumentException("源码输出必须位于源工程以外，避免重复定义 / Source output must be outside the project root: " + destination);
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
    public String pluginNamespace() { return "AstralModForge.Generated." + safeName(); }
}
