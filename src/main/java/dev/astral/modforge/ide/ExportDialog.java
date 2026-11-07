package dev.astral.modforge.ide;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import dev.astral.modforge.core.*;
import org.jetbrains.annotations.Nullable;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Source selection stays independent from build work; the tree only reads saved C# files. */
class ExportDialog extends DialogWrapper {
    private final Path root;
    private final JTextField id = new JTextField("dev.astralparty.custommod", 34);
    private final JTextField output = new JTextField(45);
    private final JTextField load = new JTextField(45), update = new JTextField(45), game = new JTextField(45);
    private final JComboBox<String> mode = new JComboBox<>(new String[]{I18n.get("mode.embed"), I18n.get("mode.inline")});
    private final JComboBox<String> language = new JComboBox<>(new String[]{"zh_cn", "en_us"});
    private final JCheckBox deploy = new JCheckBox(I18n.get("copy"));
    private final JCheckBox build = new JCheckBox(I18n.get("build"), true);
    private final JCheckBox exportSource = new JCheckBox(I18n.get("export.source"), false);
    private final JCheckBox embedDefaultConfig = new JCheckBox(I18n.get("config.embed"), false);
    private ClassSelectionTree fileTree;
    private String indexError;

    ExportDialog(Project project, Path projectRoot, List<Path> preselected) {
        super(project);
        root = projectRoot.toAbsolutePath().normalize();
        setTitle(I18n.get("export.title"));
        try { fileTree = new ClassSelectionTree(new CSharpIndex(root), preselected); }
        catch (Exception ex) { indexError = ex.getMessage(); }
        build.addActionListener(e -> {
            if (!build.isSelected()) {
                exportSource.setSelected(true);
                deploy.setSelected(false);
            }
            deploy.setEnabled(build.isSelected());
        });
        exportSource.addActionListener(e -> {
            if (!exportSource.isSelected() && !build.isSelected()) build.setSelected(true);
        });
        init();
    }

    @Override protected @Nullable JComponent createCenterPanel() {
        JPanel result = new JPanel(new BorderLayout(10,10));
        JPanel upper = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3,4,3,4); c.fill = GridBagConstraints.HORIZONTAL;
        add(upper,c,0,I18n.get("id"),id);
        add(upper,c,1,I18n.get("lang"),mode);
        add(upper,c,2,I18n.get("locale"),language);
        add(upper,c,3,I18n.get("load"),load);
        add(upper,c,4,I18n.get("update"),update);
        add(upper,c,5,I18n.get("output"),pathRow(output));
        add(upper,c,6,I18n.get("path"),pathRow(game));
        JPanel flags = new JPanel(new FlowLayout(FlowLayout.LEFT));
        flags.add(build); flags.add(exportSource); flags.add(deploy); flags.add(embedDefaultConfig);
        c.gridx = 0; c.gridy = 7; c.gridwidth = 2; upper.add(flags,c);
        result.add(upper,BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(3,4));
        center.add(new JLabel(I18n.get("source") + " — " + root),BorderLayout.NORTH);
        if (fileTree != null) center.add(fileTree,BorderLayout.CENTER);
        else center.add(new JLabel(I18n.get("source.error", indexError)),BorderLayout.CENTER);
        center.setPreferredSize(new Dimension(820,365));
        JButton analyze = new JButton(I18n.get("analyze"));
        analyze.addActionListener(e -> analyze());
        center.add(analyze,BorderLayout.SOUTH);
        result.add(center,BorderLayout.CENTER);
        result.add(new JLabel(I18n.get("export.hint")),BorderLayout.SOUTH);
        return result;
    }

    private JComponent pathRow(JTextField field) {
        JPanel panel = new JPanel(new BorderLayout(4,0)); panel.add(field,BorderLayout.CENTER);
        JButton browse = new JButton(I18n.get("browse"));
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(root.toFile());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION)
                field.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        panel.add(browse,BorderLayout.EAST);
        return panel;
    }

    private void add(JPanel panel, GridBagConstraints c, int row, String title, JComponent input) {
        c.gridy = row; c.gridx = 0; c.gridwidth = 1; c.weightx = 0; panel.add(new JLabel(title), c);
        c.gridx = 1; c.weightx = 1; panel.add(input,c);
    }

    ForgeSpec toSpec() {
        if (fileTree == null) throw new IllegalArgumentException(I18n.get("source.error", indexError));
        List<Path> roots = fileTree.roots();
        if (roots.isEmpty()) throw new IllegalArgumentException(I18n.get("no.entries"));
        return new ForgeSpec(root,id.getText().trim(),roots,
                output.getText().isBlank() ? null : Path.of(output.getText().trim()),
                mode.getSelectedIndex() == 0 ? ForgeSpec.LanguageMode.EMBEDDED : ForgeSpec.LanguageMode.INLINE,
                Objects.requireNonNull((String)language.getSelectedItem()),load.getText(),update.getText(),
                build.isSelected(),deploy.isSelected(),game.getText().isBlank()?null:Path.of(game.getText().trim()),
                exportSource.isSelected(),embedDefaultConfig.isSelected());
    }

    private void analyze() {
        try {
            ForgeSpec spec = toSpec();
            CSharpIndex index = new CSharpIndex(spec.sourceRoot());
            DependencyPlanner.Plan plan = new DependencyPlanner(index).plan(spec);
            LocalizationTransformer.Result locale = new LocalizationTransformer().transform(
                    spec.sourceRoot(),spec,plan.sources(),plan.rewrittenSources());
            StringBuilder result = new StringBuilder(I18n.get("analyze.files",plan.sources().size()) + "\n");
            if (fileTree != null) for (String warning : fileTree.warnings()) result.append("[!] ").append(warning).append('\n');
            for (String warning : plan.warnings()) result.append("[!] ").append(warning).append('\n');
            for (String warning : locale.warnings()) result.append("[!] ").append(warning).append('\n');
            for (String error : plan.errors()) result.append("[X] ").append(error).append('\n');
            for (String error : locale.errors()) result.append("[X] ").append(error).append('\n');
            JTextArea text = new JTextArea(result.toString(),18,65);
            text.setEditable(false);
            text.setCaretPosition(0);
            JOptionPane.showMessageDialog(null, new JScrollPane(text),
                    I18n.get("analyze.title"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) { Messages.showErrorDialog(ex.getMessage(),I18n.get("analyze.title")); }
    }
}
