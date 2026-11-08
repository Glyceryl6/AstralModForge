package dev.astral.modforge.ide;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import dev.astral.modforge.core.CSharpIndex;
import dev.astral.modforge.core.DependencyPlanner;
import dev.astral.modforge.core.ForgeSpec;
import dev.astral.modforge.core.ForgeText;
import dev.astral.modforge.core.LocalizationTransformer;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** The selection UI reads saved C# sources; compilation runs in a background action. */
class ExportDialog extends DialogWrapper {
    private final Path root;
    private final JTextField id = new JTextField("dev.astralparty.custommod", 34);
    private final JTextField output = new JTextField(45);
    private final JTextField load = new JTextField(45);
    private final JTextField update = new JTextField(45);
    private final JTextField game = new JTextField(45);
    private final JComboBox<String> mode = new JComboBox<>(
            new String[]{I18n.get("mode.embed"), I18n.get("mode.inline")});
    private final JComboBox<String> language = new JComboBox<>(new String[]{"zh_cn", "en_us"});
    private final JCheckBox deploy = new JCheckBox(I18n.get("copy"));
    private final JCheckBox build = new JCheckBox(I18n.get("build"), true);
    private final JCheckBox exportSource = new JCheckBox(I18n.get("export.source"), false);
    private final JCheckBox embedDefaultConfig = new JCheckBox(I18n.get("config.embed"), false);

    private ClassSelectionTree fileTree;
    private String indexError;

    ExportDialog(Project project, Path projectRoot, List<Path> preselected) {
        super(project);
        this.root = projectRoot.toAbsolutePath().normalize();
        setTitle(I18n.get("export.title"));

        try {
            this.fileTree = new ClassSelectionTree(new CSharpIndex(this.root), preselected);
        } catch (Exception exception) {
            this.indexError = ForgeText.select(exception.getMessage(), I18n.language());
        }

        this.build.addActionListener(event -> {
            if (!this.build.isSelected()) {
                this.exportSource.setSelected(true);
                this.deploy.setSelected(false);
            }
            this.deploy.setEnabled(this.build.isSelected());
        });
        this.exportSource.addActionListener(event -> {
            if (!this.exportSource.isSelected() && !this.build.isSelected()) {
                this.build.setSelected(true);
            }
        });
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel result = new JPanel(new BorderLayout(10, 10));
        JPanel upper = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(3, 4, 3, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;

        addFormRow(upper, constraints, 0, I18n.get("id"), this.id);
        addFormRow(upper, constraints, 1, I18n.get("lang"), this.mode);
        addFormRow(upper, constraints, 2, I18n.get("locale"), this.language);
        addFormRow(upper, constraints, 3, I18n.get("load"), this.load);
        addFormRow(upper, constraints, 4, I18n.get("update"), this.update);
        addFormRow(upper, constraints, 5, I18n.get("output"), pathRow(this.output));
        addFormRow(upper, constraints, 6, I18n.get("path"), pathRow(this.game));

        JPanel flags = new JPanel(new FlowLayout(FlowLayout.LEFT));
        flags.add(this.build);
        flags.add(this.exportSource);
        flags.add(this.deploy);
        flags.add(this.embedDefaultConfig);
        constraints.gridx = 0;
        constraints.gridy = 7;
        constraints.gridwidth = 2;
        upper.add(flags, constraints);
        result.add(upper, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(3, 4));
        center.add(new JLabel(I18n.get("source") + " — " + this.root), BorderLayout.NORTH);
        if (this.fileTree != null) {
            center.add(this.fileTree, BorderLayout.CENTER);
        } else {
            center.add(new JLabel(I18n.get("source.error", this.indexError)), BorderLayout.CENTER);
        }
        center.setPreferredSize(new Dimension(820, 365));

        JButton analyze = new JButton(I18n.get("analyze"));
        analyze.addActionListener(event -> analyze());
        center.add(analyze, BorderLayout.SOUTH);
        result.add(center, BorderLayout.CENTER);
        result.add(new JLabel(I18n.get("export.hint")), BorderLayout.SOUTH);
        return result;
    }

    private JComponent pathRow(JTextField field) {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.add(field, BorderLayout.CENTER);
        JButton browse = new JButton(I18n.get("browse"));
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(this.root.toFile());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        panel.add(browse, BorderLayout.EAST);
        return panel;
    }

    private void addFormRow(JPanel panel, GridBagConstraints constraints, int row,
                            String title, JComponent input) {
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        panel.add(new JLabel(title), constraints);

        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(input, constraints);
    }

    ForgeSpec toSpec() {
        if (this.fileTree == null) {
            throw new IllegalArgumentException(I18n.get("source.error", this.indexError));
        }

        List<Path> roots = this.fileTree.roots();
        if (roots.isEmpty()) {
            throw new IllegalArgumentException(I18n.get("no.entries"));
        }

        return new ForgeSpec(
                this.root, this.id.getText().trim(), roots,
                this.output.getText().isBlank() ? null : Path.of(this.output.getText().trim()),
                this.mode.getSelectedIndex() == 0
                        ? ForgeSpec.LanguageMode.EMBEDDED : ForgeSpec.LanguageMode.INLINE,
                Objects.requireNonNull((String) this.language.getSelectedItem()),
                this.load.getText(), this.update.getText(), this.build.isSelected(),
                this.deploy.isSelected(),
                this.game.getText().isBlank() ? null : Path.of(this.game.getText().trim()),
                this.exportSource.isSelected(), this.embedDefaultConfig.isSelected());
    }

    private void analyze() {
        try {
            ForgeSpec spec = toSpec();
            CSharpIndex index = new CSharpIndex(spec.sourceRoot());
            DependencyPlanner.Plan plan = new DependencyPlanner(index).plan(spec);
            LocalizationTransformer.Result locale = new LocalizationTransformer().transform(
                    spec.sourceRoot(), spec, plan.sources(), plan.rewrittenSources());
            StringBuilder summary = new StringBuilder(
                    I18n.get("analyze.files", plan.sources().size()) + "\n");

            for (String warning : this.fileTree.warnings()) {
                summary.append("[!] ").append(ForgeText.select(warning, I18n.language())).append('\n');
            }
            for (String warning : plan.warnings()) {
                summary.append("[!] ").append(ForgeText.select(warning, I18n.language())).append('\n');
            }
            for (String warning : locale.warnings()) {
                summary.append("[!] ").append(ForgeText.select(warning, I18n.language())).append('\n');
            }
            for (String error : plan.errors()) {
                summary.append("[X] ").append(ForgeText.select(error, I18n.language())).append('\n');
            }
            for (String error : locale.errors()) {
                summary.append("[X] ").append(ForgeText.select(error, I18n.language())).append('\n');
            }

            JTextArea text = new JTextArea(summary.toString(), 18, 65);
            text.setEditable(false);
            text.setCaretPosition(0);
            JOptionPane.showMessageDialog(null, new JScrollPane(text),
                    I18n.get("analyze.title"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception exception) {
            Messages.showErrorDialog(ForgeText.select(exception.getMessage(), I18n.language()), I18n.get("analyze.title"));
        }
    }
}
