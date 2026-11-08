package dev.astral.modforge.ide;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;

import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;

/**
 * Project-scoped build console registered in plugin.xml.
 * 构建日志在 plugin.xml 中注册；如服务不可用则使用项目内的备用实例。
 */
public class BuildLogService {
    private static final Key<BuildLogService> FALLBACK = Key.create("astral.modforge.buildLog.fallback");

    private final StringBuilder history = new StringBuilder();
    private JTextArea console;
    private JLabel phaseLabel;
    private String phase = "";

    public static BuildLogService getInstance(Project project) {
        BuildLogService service = project.getService(BuildLogService.class);
        if (service != null) {
            return service;
        }

        // 只在当前项目范围内回退，不能修复使用错误 SDK 编译的二进制文件。
        // Project-local fallback; incompatible compiled APIs must be rebuilt, not caught.
        synchronized (project) {
            BuildLogService fallback = project.getUserData(FALLBACK);
            if (fallback == null) {
                fallback = new BuildLogService();
                project.putUserData(FALLBACK, fallback);
            }
            return fallback;
        }
    }

    public synchronized void attach(JTextArea output, JLabel status) {
        this.console = output;
        this.phaseLabel = status;
        String previous = this.history.toString();
        String currentPhase = this.phase;
        SwingUtilities.invokeLater(() -> {
            output.setText(previous);
            output.setCaretPosition(output.getDocument().getLength());
            status.setText(currentPhase);
        });
    }

    public synchronized void clear() {
        this.history.setLength(0);
        this.phase = "";
        SwingUtilities.invokeLater(() -> {
            if (this.console != null) {
                this.console.setText("");
            }
            if (this.phaseLabel != null) {
                this.phaseLabel.setText("");
            }
        });
    }

    public synchronized void event(String kind, String message) {
        if ("phase".equals(kind)) {
            this.phase = message;
        }

        String text = ("phase".equals(kind) ? "\n[STEP] " : "") + message + "\n";
        this.history.append(text);
        // 内存预览设上限；完整 dotnet 构建输出保留在 build.log。
        if (this.history.length() > 1_000_000) {
            this.history.delete(0, this.history.length() - 750_000);
        }

        String currentPhase = this.phase;
        SwingUtilities.invokeLater(() -> {
            if (this.console != null) {
                this.console.append(text);
                if (this.console.getDocument().getLength() > 1_000_000) {
                    try {
                        this.console.getDocument().remove(0, 250_000);
                    } catch (BadLocationException ignored) {
                        // 内容已经变化；下一轮日志仍可继续显示。
                    }
                }
                this.console.setCaretPosition(this.console.getDocument().getLength());
            }

            if (this.phaseLabel != null) {
                this.phaseLabel.setText(currentPhase);
            }
        });
    }
}
