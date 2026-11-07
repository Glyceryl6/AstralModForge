package dev.astral.modforge.ide;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import javax.swing.*;

/**
 * Project-scoped build console registered in plugin.xml.
 * 构建日志在 plugin.xml 中显式注册；旧安装环境缺少服务时使用项目内备用实例。
 */
public class BuildLogService {
    private static final Key<BuildLogService> FALLBACK = Key.create("astral.modforge.buildLog.fallback");

    public static BuildLogService getInstance(Project project) {
        BuildLogService service = project.getService(BuildLogService.class);
        if (service != null) return service;
        // Avoid crashing the tool window when an older IDE fails to create the service.
        // 旧版环境缺失项目服务时不阻止打开工具窗口；备用实例只属于当前项目。
        synchronized (project) {
            BuildLogService fallback = project.getUserData(FALLBACK);
            if (fallback == null) {
                fallback = new BuildLogService();
                project.putUserData(FALLBACK, fallback);
            }
            return fallback;
        }
    }
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
