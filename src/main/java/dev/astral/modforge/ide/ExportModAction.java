package dev.astral.modforge.ide;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.vfs.VirtualFile;
import dev.astral.modforge.core.*;
import org.jetbrains.annotations.NotNull;
import java.nio.file.Path;
import java.util.*;

public class ExportModAction extends AnAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent e) { e.getPresentation().setEnabled(e.getProject()!=null); }
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Project project=e.getProject(); if (project==null || project.getBasePath()==null) return;
        List<Path> preselected=new ArrayList<>();
        VirtualFile[] files=e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if(files!=null) for(VirtualFile file : files)
            if(!file.isDirectory() && file.getPath().endsWith(".cs")) preselected.add(Path.of(file.getPath()));
        ExportDialog dialog=new ExportDialog(project, ProjectRoots.resolve(Path.of(project.getBasePath()), preselected), preselected);
        if(!dialog.showAndGet()) return;
        ForgeSpec spec;
        try { spec=dialog.toSpec(); }
        catch(Exception ex) { Messages.showErrorDialog(project,ex.getMessage(),I18n.get("export.title")); return; }
        BuildLogService console = BuildLogService.getInstance(project);
        console.clear();
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Astral Mod Forge");
        if (toolWindow != null) toolWindow.show();
        new Task.Backgroundable(project, I18n.get("export.title"), true) {
            private ForgeExporter.Result result;
            private Exception failure;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText(I18n.get("phase.preparing"));
                try {
                    result = new ForgeExporter().export(spec, new ExportObserver() {
                        @Override public void onEvent(String kind, String line) {
                            console.event(kind, line);
                            if ("phase".equals(kind)) indicator.setText(line);
                            else indicator.setText2(line);
                        }
                        @Override public boolean isCancelled() { return indicator.isCanceled(); }
                    });
                } catch (Exception ex) {
                    failure = ex;
                    console.event("error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
                }
            }
            @Override public void onSuccess() {
                if(failure!=null) Messages.showErrorDialog(project,I18n.get("failed")+failure.getMessage(),I18n.get("export.title"));
                else {
                    String suffix=result.compiled() ? "\nDLL: "+result.dll() : "\n" + I18n.get("finished.no.dll");
                    Messages.showInfoMessage(project, I18n.get("finished") + result.report() + suffix, I18n.get("export.title"));
                }
            }
        }.queue();
    }
}
