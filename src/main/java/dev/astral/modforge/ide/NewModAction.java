package dev.astral.modforge.ide;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import dev.astral.modforge.core.ForgeExporter;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Menu action; no project objects stored in fields / 菜单动作不持有工程引用。
 */
public class NewModAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.getBasePath() == null) return;
        String id = Messages.showInputDialog(project, I18n.get("id"), I18n.get("new.title"), Messages.getQuestionIcon());
        if (id == null) return;
        String finalId = id.trim();
        Path root = Path.of(project.getBasePath());
        new Task.Backgroundable(project, I18n.get("new.title"), true) {
            private ForgeExporter.Result result;
            private Exception failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Creating standalone MOD project / 创建空 MOD 工程");
                try {
                    result = new ForgeExporter().createEmpty(root, finalId);
                } catch (Exception ex) {
                    failure = ex;
                }
            }

            @Override
            public void onSuccess() {
                if (failure != null)
                    Messages.showErrorDialog(project, I18n.get("failed") + failure.getMessage(), I18n.get("new.title"));
                else
                    Messages.showInfoMessage(project, I18n.get("finished") + result.directory(), I18n.get("new.title"));
            }
        }.queue();
    }
}
