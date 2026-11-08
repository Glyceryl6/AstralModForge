package dev.astral.modforge.ide;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import dev.astral.modforge.core.ExportObserver;
import dev.astral.modforge.core.ForgeExporter;
import dev.astral.modforge.core.ForgeSpec;
import dev.astral.modforge.core.ForgeText;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Rider menu entry; no project objects are retained in fields. / 菜单动作不持有工程引用。 */
public class ExportModAction extends AnAction {
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

        List<Path> preselected = collectSelectedFiles(event);
        Path root = ProjectRoots.resolve(Path.of(project.getBasePath()), preselected);
        ExportDialog dialog = new ExportDialog(project, root, preselected);
        if (!dialog.showAndGet()) {
            return;
        }

        ForgeSpec spec;
        try {
            spec = dialog.toSpec();
        } catch (Exception exception) {
            Messages.showErrorDialog(project, ForgeText.select(exception.getMessage(), I18n.language()), I18n.get("export.title"));
            return;
        }

        BuildLogService console = BuildLogService.getInstance(project);
        console.clear();
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Astral Mod Forge");
        if (toolWindow != null) {
            toolWindow.show();
        }

        startExport(project, spec, console);
    }

    private List<Path> collectSelectedFiles(AnActionEvent event) {
        List<Path> selected = new ArrayList<>();
        VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (files == null) {
            return selected;
        }

        for (VirtualFile file : files) {
            if (!file.isDirectory() && file.getPath().endsWith(".cs")) {
                selected.add(Path.of(file.getPath()));
            }
        }
        return selected;
    }

    private void startExport(Project project, ForgeSpec spec, BuildLogService console) {
        new Task.Backgroundable(project, I18n.get("export.title"), true) {
            private ForgeExporter.Result result;
            private Exception failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText(I18n.get("phase.preparing"));
                try {
                    this.result = new ForgeExporter().export(spec, new ExportObserver() {
                        @Override
                        public void onEvent(String kind, String line) {
                            console.event(kind, line);
                            if ("phase".equals(kind)) {
                                indicator.setText(line);
                            } else {
                                indicator.setText2(line);
                            }
                        }

                        @Override
                        public boolean isCancelled() {
                            return indicator.isCanceled();
                        }

                        @Override
                        public String language() {
                            return I18n.language();
                        }
                    });
                } catch (Exception exception) {
                    this.failure = exception;
                    String message = exception.getMessage() == null
                            ? exception.toString() : exception.getMessage();
                    console.event("error", ForgeText.select(message, I18n.language()));
                }
            }

            @Override
            public void onSuccess() {
                if (this.failure != null) {
                    Messages.showErrorDialog(project, I18n.get("failed") + ForgeText.select(this.failure.getMessage(), I18n.language()),
                            I18n.get("export.title"));
                    return;
                }

                String suffix = this.result.compiled()
                        ? "\nDLL: " + this.result.dll()
                        : "\n" + I18n.get("finished.no.dll");
                Messages.showInfoMessage(project, I18n.get("finished") + this.result.report() + suffix,
                        I18n.get("export.title"));
            }
        }.queue();
    }
}
