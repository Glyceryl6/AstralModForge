package dev.astral.modforge.ide;

import dev.astral.modforge.core.CSharpIndex;
import dev.astral.modforge.core.ClassSelectionModel;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Expandable check tree with transitive dependency selection. / 可展开的依赖联动勾选树。 */
public class ClassSelectionTree extends JPanel {
    private record Item(String title, Path file) {
        @Override
        public String toString() {
            return this.title;
        }
    }

    private final ClassSelectionModel selection;
    private final DefaultMutableTreeNode top;
    private final JTree tree;
    private final JLabel count = new JLabel();
    private final Map<DefaultMutableTreeNode, Set<Path>> members = new IdentityHashMap<>();

    public ClassSelectionTree(CSharpIndex index, List<Path> preselected) {
        super(new BorderLayout(0, 5));
        this.selection = new ClassSelectionModel(index);
        this.top = new DefaultMutableTreeNode(new Item(index.root().getFileName().toString(), null));

        Map<String, DefaultMutableTreeNode> directories = new HashMap<>();
        directories.put("", this.top);
        for (Path file : this.selection.eligible()) {
            Path relative = index.root().relativize(file);
            DefaultMutableTreeNode parent = this.top;
            String key = "";
            for (int partIndex = 0; partIndex < relative.getNameCount() - 1; partIndex++) {
                String piece = relative.getName(partIndex).toString();
                key += "/" + piece;
                DefaultMutableTreeNode next = directories.get(key);
                if (next == null) {
                    next = new DefaultMutableTreeNode(new Item(piece, null));
                    parent.add(next);
                    directories.put(key, next);
                }
                parent = next;
            }
            parent.add(new DefaultMutableTreeNode(new Item(file.getFileName().toString(), file)));
        }

        collect(this.top);
        this.selection.checkAll(preselected);
        this.tree = new JTree(this.top);
        this.tree.setRootVisible(true);
        this.tree.setShowsRootHandles(true);
        this.tree.setRowHeight(25);
        this.tree.setCellRenderer(new CheckboxRenderer());
        this.tree.setToggleClickCount(0);
        this.tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }

                TreePath path = ClassSelectionTree.this.tree.getPathForLocation(
                        event.getX(), event.getY());
                if (path == null) {
                    return;
                }

                Rectangle bounds = ClassSelectionTree.this.tree.getPathBounds(path);
                if (bounds != null && event.getX() >= bounds.x) {
                    ClassSelectionTree.this.tree.setSelectionPath(path);
                    toggle((DefaultMutableTreeNode) path.getLastPathComponent());
                    event.consume();
                }
            }
        });
        this.tree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_SPACE
                        && ClassSelectionTree.this.tree.getSelectionPath() != null) {
                    toggle((DefaultMutableTreeNode)
                            ClassSelectionTree.this.tree.getSelectionPath().getLastPathComponent());
                    event.consume();
                }
            }
        });

        add(new JScrollPane(this.tree), BorderLayout.CENTER);
        add(this.count, BorderLayout.SOUTH);
        this.tree.expandPath(new TreePath(this.top.getPath()));
        for (Path selected : preselected) {
            DefaultMutableTreeNode node = findNode(this.top, selected);
            if (node != null) {
                this.tree.expandPath(new TreePath(node.getPath()).getParentPath());
            }
        }
        refresh();
    }

    private Set<Path> collect(DefaultMutableTreeNode node) {
        Item item = (Item) node.getUserObject();
        Set<Path> paths = new TreeSet<>();
        if (item.file() != null) {
            paths.add(item.file());
        }
        for (int childIndex = 0; childIndex < node.getChildCount(); childIndex++) {
            paths.addAll(collect((DefaultMutableTreeNode) node.getChildAt(childIndex)));
        }
        this.members.put(node, Set.copyOf(paths));
        return paths;
    }

    private DefaultMutableTreeNode findNode(DefaultMutableTreeNode root, Path file) {
        Item item = (Item) root.getUserObject();
        if (file.equals(item.file())) {
            return root;
        }
        for (int childIndex = 0; childIndex < root.getChildCount(); childIndex++) {
            DefaultMutableTreeNode found = findNode(
                    (DefaultMutableTreeNode) root.getChildAt(childIndex), file);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void toggle(DefaultMutableTreeNode node) {
        Set<Path> paths = this.members.getOrDefault(node, Set.of());
        if (paths.isEmpty()) {
            return;
        }
        if (this.selection.state(paths) == ClassSelectionModel.State.ALL) {
            this.selection.uncheckAll(paths);
        } else {
            this.selection.checkAll(paths);
        }
        refresh();
    }

    private void refresh() {
        this.tree.repaint();
        this.count.setText(I18n.get("selection.count", this.selection.requested().size(),
                this.selection.checked().size()));
    }

    public List<Path> roots() {
        return List.copyOf(this.selection.requested());
    }

    public Set<Path> checked() {
        return this.selection.checked();
    }

    public List<String> warnings() {
        return this.selection.warnings();
    }

    private class CheckboxRenderer extends JPanel implements TreeCellRenderer {
        private final JCheckBox box = new JCheckBox();
        private final JLabel title = new JLabel();

        CheckboxRenderer() {
            super(new BorderLayout(4, 0));
            setOpaque(false);
            this.box.setOpaque(false);
            this.title.setOpaque(false);
            add(this.box, BorderLayout.WEST);
            add(this.title, BorderLayout.CENTER);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                      boolean expanded, boolean leaf, int row,
                                                      boolean focus) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Item item = (Item) node.getUserObject();
            ClassSelectionModel.State state = ClassSelectionTree.this.selection.state(
                    ClassSelectionTree.this.members.getOrDefault(node, Set.of()));
            this.box.setSelected(state == ClassSelectionModel.State.ALL);
            this.box.putClientProperty("JButton.selectedState",
                    state == ClassSelectionModel.State.PARTIAL ? "indeterminate" : null);
            this.title.setText(item.title());
            this.title.setFont(tree.getFont());
            this.title.setForeground(selected
                    ? UIManager.getColor("Tree.selectionForeground") : tree.getForeground());
            this.box.setToolTipText(state == ClassSelectionModel.State.PARTIAL
                    ? I18n.get("selection.partial") : I18n.get("selection.help"));
            return this;
        }
    }
}
