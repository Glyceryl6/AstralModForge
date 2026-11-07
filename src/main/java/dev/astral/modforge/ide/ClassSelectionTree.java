package dev.astral.modforge.ide;

import dev.astral.modforge.core.CSharpIndex;
import dev.astral.modforge.core.ClassSelectionModel;
import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/** Expandable check tree with transitive dependency selection. / 可展开的依赖联动勾选树。 */
public class ClassSelectionTree extends JPanel {
    private record Item(String title, Path file) {
        @Override public String toString() { return title; }
    }

    private final ClassSelectionModel selection;
    private final DefaultMutableTreeNode top;
    private final JTree tree;
    private final JLabel count = new JLabel();
    private final Map<DefaultMutableTreeNode, Set<Path>> members = new IdentityHashMap<>();

    public ClassSelectionTree(CSharpIndex index, List<Path> preselected) {
        super(new BorderLayout(0, 5));
        selection = new ClassSelectionModel(index);
        top = new DefaultMutableTreeNode(new Item(index.root().getFileName().toString(), null));
        Map<String, DefaultMutableTreeNode> directories = new HashMap<>();
        directories.put("", top);
        for (Path file : selection.eligible()) {
            Path relative = index.root().relativize(file);
            DefaultMutableTreeNode parent = top;
            String key = "";
            for (int i = 0; i < relative.getNameCount() - 1; i++) {
                String piece = relative.getName(i).toString();
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
        collect(top);
        selection.checkAll(preselected);
        tree = new JTree(top);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(25);
        tree.setCellRenderer(new CheckboxRenderer());
        tree.setToggleClickCount(0);
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                Rectangle bounds = tree.getPathBounds(path);
                if (bounds != null && e.getX() >= bounds.x) {
                    tree.setSelectionPath(path);
                    toggle((DefaultMutableTreeNode) path.getLastPathComponent());
                    e.consume();
                }
            }
        });
        tree.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE && tree.getSelectionPath() != null) {
                    toggle((DefaultMutableTreeNode) tree.getSelectionPath().getLastPathComponent());
                    e.consume();
                }
            }
        });
        add(new JScrollPane(tree), BorderLayout.CENTER);
        add(count, BorderLayout.SOUTH);
        tree.expandPath(new TreePath(top.getPath()));
        for (Path selected : preselected) {
            DefaultMutableTreeNode node = findNode(top, selected);
            if (node != null) tree.expandPath(new TreePath(node.getPath()).getParentPath());
        }
        refresh();
    }

    private Set<Path> collect(DefaultMutableTreeNode node) {
        Item item = (Item) node.getUserObject();
        Set<Path> paths = new TreeSet<>();
        if (item.file() != null) paths.add(item.file());
        for (int i = 0; i < node.getChildCount(); i++)
            paths.addAll(collect((DefaultMutableTreeNode) node.getChildAt(i)));
        members.put(node, Set.copyOf(paths));
        return paths;
    }

    private DefaultMutableTreeNode findNode(DefaultMutableTreeNode root, Path file) {
        Item item = (Item) root.getUserObject();
        if (file.equals(item.file())) return root;
        for (int i=0;i<root.getChildCount();i++) {
            DefaultMutableTreeNode result = findNode((DefaultMutableTreeNode)root.getChildAt(i),file);
            if (result != null) return result;
        }
        return null;
    }

    private void toggle(DefaultMutableTreeNode node) {
        Set<Path> paths = members.getOrDefault(node, Set.of());
        if (paths.isEmpty()) return;
        if (selection.state(paths) == ClassSelectionModel.State.ALL) selection.uncheckAll(paths);
        else selection.checkAll(paths);
        refresh();
    }

    private void refresh() {
        tree.repaint();
        count.setText(I18n.get("selection.count", selection.requested().size(), selection.checked().size()));
    }

    public List<Path> roots() { return List.copyOf(selection.requested()); }
    public Set<Path> checked() { return selection.checked(); }
    public List<String> warnings() { return selection.warnings(); }

    private class CheckboxRenderer extends JPanel implements TreeCellRenderer {
        private final JCheckBox box = new JCheckBox();
        private final JLabel title = new JLabel();

        CheckboxRenderer() {
            super(new BorderLayout(4, 0));
            setOpaque(false);
            box.setOpaque(false);
            title.setOpaque(false);
            add(box, BorderLayout.WEST);
            add(title, BorderLayout.CENTER);
        }
        @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                boolean expanded, boolean leaf, int row, boolean focus) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Item item = (Item) node.getUserObject();
            ClassSelectionModel.State state = selection.state(members.getOrDefault(node, Set.of()));
            box.setSelected(state == ClassSelectionModel.State.ALL);
            box.putClientProperty("JButton.selectedState", state == ClassSelectionModel.State.PARTIAL ? "indeterminate" : null);
            title.setText(item.title());
            title.setFont(tree.getFont());
            title.setForeground(selected ? UIManager.getColor("Tree.selectionForeground") : tree.getForeground());
            box.setToolTipText(state == ClassSelectionModel.State.PARTIAL ? I18n.get("selection.partial") : I18n.get("selection.help"));
            return this;
        }
    }
}
