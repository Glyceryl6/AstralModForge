package dev.astral.modforge.core;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Fail closed when a selected feature still performs known BepInEx configuration writes.
 * 内嵌配置模式必须阻止已知的运行时配置写入，不能只在打包时跳过复制 .cfg。
 */
public class ConfigPersistenceGuard {
    private static final Map<Pattern, String> WRITES = Map.of(
            Pattern.compile("\\bnew\\s+(?:[A-Za-z_]\\w*\\.)*ConfigFile\\s*\\("), "new ConfigFile(...)" ,
            Pattern.compile("\\b(?:Config|config|_config)\\s*\\.\\s*Bind\\s*(?:<[^>]+>)?\\s*\\("), "Config.Bind(...)" ,
            Pattern.compile("\\b(?:Config|config|_config)\\s*\\.\\s*Save\\s*\\("), "Config.Save(...)" ,
            Pattern.compile("\\b(?:Plugin\\s*\\.\\s*Instance|Instance|this)\\s*\\.\\s*Config\\b"), "Plugin.Instance.Config" ,
            Pattern.compile("\\bPaths\\s*\\.\\s*(?:ConfigPath|BepInExConfigPath)\\b"), "Paths.ConfigPath"
    );

    public List<String> inspect(CSharpIndex index, Collection<CSharpIndex.Unit> sources,
                                Map<java.nio.file.Path, String> rewritten) {
        List<String> errors = new ArrayList<>();
        for (CSharpIndex.Unit unit : sources) {
            String text = CSharpIndex.maskLiteralsAndComments(rewritten.get(unit.file()));
            for (var test : WRITES.entrySet()) if (test.getKey().matcher(text).find()) {
                errors.add("内嵌默认配置不能保留运行时配置访问 / Persistent configuration still referenced: " +
                        test.getValue() + " (" + index.root().relativize(unit.file()) + ")");
            }
        }
        return errors;
    }
}
