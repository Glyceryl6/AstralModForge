package dev.astral.modforge.ide;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import dev.astral.modforge.core.ForgeExporter;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/** New project action. / 创建项目时不持有长期的 Rider 工程引用。 */
public class NewModAction extends AnAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null || project.getBasePath() == null) {
            return;
        }

        String id = Messages.showInputDialog(project, I18n.get("id"), I18n.get("new.title"),
                Messages.getQuestionIcon());
        if (id == null) {
            return;
        }

        String modId = id.trim();
        Path root = Path.of(project.getBasePath());
        new Task.Backgroundable(project, I18n.get("new.title"), true) {
            private ForgeExporter.Result result;
            private Exception failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText(I18n.get("phase.creating"));
                try {
                    this.result = new ForgeExporter().createEmpty(root, modId);
                } catch (Exception exception) {
                    this.failure = exception;
                }
            }

            @Override
            public void onSuccess() {
                if (this.failure != null) {
                    Messages.showErrorDialog(project, I18n.get("failed") + this.failure.getMessage(),
                            I18n.get("new.title"));
                    return;
                }

                Messages.showInfoMessage(project, I18n.get("finished") + this.result.directory(),
                        I18n.get("new.title"));
            }
        }.queue();
    }
}
