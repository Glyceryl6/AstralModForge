package dev.astral.modforge.ide;

import com.intellij.DynamicBundle;

/** JetBrains IDE locale, not the operating-system locale. / 跟随 Rider 显示语言。 */
class I18n {
    private static final DynamicBundle BUNDLE = new DynamicBundle(
            I18n.class, "dev.astral.modforge.i18n.Messages");

    static String get(String key, Object... args) {
        return BUNDLE.getMessage(key, args);
    }

    static String language() {
        return DynamicBundle.getLocale().getLanguage();
    }
}
