package dev.astral.modforge.ide;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import dev.astral.modforge.core.ForgeExporter;
import dev.astral.modforge.core.ForgeSpec;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ExportModAction extends AnAction {

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
        List<Path> preselected = new ArrayList<>();
        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (files != null) for (VirtualFile file : files)
            if (!file.isDirectory() && file.getPath().endsWith(".cs")) preselected.add(Path.of(file.getPath()));
        ExportDialog dialog = new ExportDialog(project, ProjectRoots.resolve(Path.of(project.getBasePath()), preselected), preselected);
        if (!dialog.showAndGet()) return;
        ForgeSpec spec;
        try {
            spec = dialog.toSpec();
        } catch (Exception ex) {
            Messages.showErrorDialog(project, ex.getMessage(), I18n.get("export.title"));
            return;
        }
        new Task.Backgroundable(project, I18n.get("export.title"), true) {
            private ForgeExporter.Result result;
            private Exception failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Collecting classes, generating project, compiling / 收集、生成、编译");
                try {
                    result = new ForgeExporter().export(spec);
                } catch (Exception ex) {
                    failure = ex;
                }
            }

            @Override
            public void onSuccess() {
                if (failure != null)
                    Messages.showErrorDialog(project, I18n.get("failed") + failure.getMessage(), I18n.get("export.title"));
                else {
                    String suffix = result.compiled() ? "\nDLL: " + result.dll() : "\n未产生 DLL / No compiled DLL; check export-report.md";
                    Messages.showInfoMessage(project, I18n.get("finished") + result.report() + suffix, I18n.get("export.title"));
                }
            }
        }.queue();
    }
}
