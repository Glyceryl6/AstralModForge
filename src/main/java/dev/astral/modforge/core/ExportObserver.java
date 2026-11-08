package dev.astral.modforge.core;

/** Live build and export events; calls may originate on a background thread. */
@FunctionalInterface
public interface ExportObserver {
    void onEvent(String kind, String message);

    /** Cancellation is checked between phases and while the compiler runs. */
    default boolean isCancelled() {
        return false;
    }

    /** Language used for Forge-generated diagnostics; compiler output is passed through unchanged. */
    default String language() {
        return ForgeText.systemLanguage();
    }

    static ExportObserver silent() {
        return (kind, message) -> {
        };
    }
}
