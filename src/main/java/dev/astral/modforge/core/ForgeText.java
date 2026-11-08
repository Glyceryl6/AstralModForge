package dev.astral.modforge.core;

import java.util.Locale;

/**
 * Selects one side of internal bilingual diagnostics at the UI boundary.
 * 内部诊断仍保留双语来源，显示时按 Rider 或命令行语言只输出一种语言。
 */
public class ForgeText {
    public static String select(String text, String language) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int separator = text.indexOf(" / ");
        if (separator < 0) {
            return text;
        }
        String selected = language != null && language.toLowerCase(Locale.ROOT).startsWith("zh")
                ? text.substring(0, separator)
                : text.substring(separator + 3);
        return selected;
    }

    public static String systemLanguage() {
        return Locale.getDefault().getLanguage();
    }
}
