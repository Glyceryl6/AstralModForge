package dev.astral.modforge.ide;

import com.intellij.openapi.components.Service;
import javax.swing.*;

/**
 * Project-scoped build console. JetBrains requires light services to be final.
 * 构建日志按项目隔离；这里的 final 是 IntelliJ 轻量服务 API 的强制要求。
 */
@Service(Service.Level.PROJECT)
public final class BuildLogService {
    private final StringBuilder history = new StringBuilder();
    private JTextArea console;
    private JLabel phaseLabel;
    private String phase = "";

    public synchronized void attach(JTextArea output, JLabel status) {
        console = output;
        phaseLabel = status;
        String previous = history.toString();
        String currentPhase = phase;
        SwingUtilities.invokeLater(() -> {
            output.setText(previous);
            output.setCaretPosition(output.getDocument().getLength());
            status.setText(currentPhase);
        });
    }

    public synchronized void clear() {
        history.setLength(0);
        phase = "";
        SwingUtilities.invokeLater(() -> {
            if (console != null) console.setText("");
            if (phaseLabel != null) phaseLabel.setText("");
        });
    }

    public synchronized void event(String kind, String message) {
        if ("phase".equals(kind)) phase = message;
        String text = ("phase".equals(kind) ? "\n[STEP] " : "") + message + "\n";
        history.append(text);
        // Keep the in-memory preview bounded. The full dotnet output lives in build.log.
        if (history.length() > 1_000_000) history.delete(0, history.length() - 750_000);
        String currentPhase = phase;
        SwingUtilities.invokeLater(() -> {
            if (console != null) {
                console.append(text);
                if (console.getDocument().getLength() > 1_000_000) {
                    try { console.getDocument().remove(0, 250_000); }
                    catch (javax.swing.text.BadLocationException ignored) { }
                }
                console.setCaretPosition(console.getDocument().getLength());
            }
            if (phaseLabel != null) phaseLabel.setText(currentPhase);
        });
    }
}
