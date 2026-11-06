package dev.astral.modforge.ide;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * IDE locale-bound UI / 跟随 Rider 的系统语言；具体 MOD 的语言选择互不干扰。
 */
final class I18n {

    private static final ResourceBundle M = ResourceBundle.getBundle("dev.astral.modforge.i18n.Messages", Locale.getDefault());

    static String get(String key) {
        return M.getString(key);
    }

}