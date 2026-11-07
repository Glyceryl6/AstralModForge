package dev.astral.modforge.ide;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.awt.*;

/** Displays build phases and streams compiler output without opening an external terminal. */
public class ForgeToolWindowFactory implements ToolWindowFactory {
    @Override public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        JLabel status = new JLabel(I18n.get("console.ready"));
        JTextArea output = new JTextArea();
        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, output.getFont().getSize()));
        panel.add(status, BorderLayout.NORTH);
        panel.add(new JScrollPane(output), BorderLayout.CENTER);
        JButton clear = new JButton(I18n.get("console.clear"));
        clear.addActionListener(e -> project.getService(BuildLogService.class).clear());
        JButton copy = new JButton(I18n.get("console.copy"));
        copy.addActionListener(e -> { output.selectAll(); output.copy(); output.select(0,0); });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(copy); buttons.add(clear);
        panel.add(buttons, BorderLayout.SOUTH);
        project.getService(BuildLogService.class).attach(output, status);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
