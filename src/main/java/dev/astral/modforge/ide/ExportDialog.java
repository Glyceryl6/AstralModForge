package dev.astral.modforge.ide;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import dev.astral.modforge.core.CSharpIndex;
import dev.astral.modforge.core.DependencyPlanner;
import dev.astral.modforge.core.ForgeSpec;
import dev.astral.modforge.core.LocalizationTransformer;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Swing controls use the JetBrains dialog contract; parsing/building runs outside EDT.
 */
final class ExportDialog extends DialogWrapper {
    private final Path root;
    private final JTextField id = new JTextField("dev.astralparty.custommod", 34);
    private final JTextField output = new JTextField(45);
    private final JTextField load = new JTextField(45), update = new JTextField(45), game = new JTextField(45);
    private final JComboBox<String> mode = new JComboBox<>(new String[]{I18n.get("mode.embed"), I18n.get("mode.inline")});
    private final JComboBox<String> language = new JComboBox<>(new String[]{"zh_cn", "en_us"});
    private final JCheckBox deploy = new JCheckBox(I18n.get("copy"));
    private final JCheckBox build = new JCheckBox("编译 DLL / Build DLL", true);
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> files = new JList<>(model);
    private final java.util.List<Path> actualFiles = new ArrayList<>();

    ExportDialog(Project project, Path projectRoot, List<Path> preselected) {
        super(project);
        root = projectRoot.toAbsolutePath().normalize();
        setTitle(I18n.get("export.title"));
        try {
            CSharpIndex index = new CSharpIndex(root);
            for (Path path : index.units().keySet()) {
                model.addElement(root.relativize(path).toString().replace('\\', '/'));
                actualFiles.add(path);
            }
            List<Integer> selected = new ArrayList<>();
            for (int i = 0; i < actualFiles.size(); i++) if (preselected.contains(actualFiles.get(i))) selected.add(i);
            files.setSelectedIndices(selected.stream().mapToInt(Integer::intValue).toArray());
        } catch (Exception ex) {
            model.addElement("Unable to load project: " + ex.getMessage());
        }
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel result = new JPanel(new BorderLayout(10, 10));
        JPanel upper = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        add(upper, c, 0, I18n.get("id"), id);
        add(upper, c, 1, I18n.get("lang"), mode);
        add(upper, c, 2, I18n.get("locale"), language);
        add(upper, c, 3, I18n.get("load"), load);
        add(upper, c, 4, I18n.get("update"), update);
        add(upper, c, 5, I18n.get("output"), output);
        add(upper, c, 6, I18n.get("path"), pathRow(game));
        JPanel flags = new JPanel(new FlowLayout(FlowLayout.LEFT));
        flags.add(build);
        flags.add(deploy);
        c.gridx = 0;
        c.gridy = 7;
        c.gridwidth = 2;
        upper.add(flags, c);
        result.add(upper, BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(3, 4));
        center.add(new JLabel(I18n.get("source") + " — " + root), BorderLayout.NORTH);
        files.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JScrollPane scroller = new JScrollPane(files);
        scroller.setPreferredSize(new Dimension(820, 350));
        center.add(scroller, BorderLayout.CENTER);
        JButton analyze = new JButton("检查依赖 / Analyze dependencies");
        analyze.addActionListener(e -> analyze());
        center.add(analyze, BorderLayout.SOUTH);
        result.add(center, BorderLayout.CENTER);
        result.add(new JLabel("读取已保存的源码文件；编译异常将保存到导出目录/build.log。 / Saved files only; build.log contains compiler output."), BorderLayout.SOUTH);
        return result;
    }

    private JComponent pathRow(JTextField field) {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.add(field, BorderLayout.CENTER);
        JButton browse = new JButton(I18n.get("browse"));
        browse.addActionListener(e -> {
            JFileChooser dialog = new JFileChooser(root.toFile());
            dialog.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (dialog.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION)
                field.setText(dialog.getSelectedFile().getAbsolutePath());
        });
        panel.add(browse, BorderLayout.EAST);
        return panel;
    }

    private void add(JPanel panel, GridBagConstraints c, int row, String title, JComponent input) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        panel.add(new JLabel(title), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(input, c);
    }

    ForgeSpec toSpec() {
        int[] selections = files.getSelectedIndices();
        if (selections.length == 0) throw new IllegalArgumentException(I18n.get("no.entries"));
        List<Path> sources = new ArrayList<>();
        for (int idx : selections) sources.add(actualFiles.get(idx));
        return new ForgeSpec(root, id.getText().trim(), sources,
                output.getText().isBlank() ? null : Path.of(output.getText().trim()),
                mode.getSelectedIndex() == 0 ? ForgeSpec.LanguageMode.EMBEDDED : ForgeSpec.LanguageMode.INLINE,
                Objects.requireNonNull((String) language.getSelectedItem()), load.getText(), update.getText(),
                build.isSelected(), deploy.isSelected(), game.getText().isBlank() ? null : Path.of(game.getText().trim()));
    }

    private void analyze() {
        try {
            ForgeSpec spec = toSpec();
            CSharpIndex index = new CSharpIndex(spec.sourceRoot());
            DependencyPlanner.Plan plan = new DependencyPlanner(index).plan(spec);
            LocalizationTransformer.Result locale = new LocalizationTransformer().transform(spec.sourceRoot(), spec, plan.sources());
            StringBuilder b = new StringBuilder("Files: " + plan.sources().size() + "\n");
            for (String w : plan.warnings()) b.append("[!] ").append(w).append('\n');
            for (String w : locale.warnings()) b.append("[!] ").append(w).append('\n');
            for (String error : plan.errors()) b.append("[X] ").append(error).append('\n');
            for (String error : locale.errors()) b.append("[X] ").append(error).append('\n');
            Messages.showInfoMessage(b.toString(), "Dependency preflight / 依赖预检查");
        } catch (Exception ex) {
            Messages.showErrorDialog(ex.getMessage(), "Dependency preflight / 依赖预检查");
        }
    }
}
