package com.android.commands.monkey.ape.tree;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Iterator;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Loads a {@code uiautomator dump --compressed} file into a {@link GUITree} the way the jar reads
 * one, for tests that ask a predicate about a real screen.
 *
 * <p>The nodes come from {@link GUITreeBuilder}'s own XML reader, so a fixture is read with the
 * attributes production reads. That reader does not read {@code content-desc}; this loader copies it
 * onto the nodes afterwards, walking the document and the tree in the same order, and converts
 * nothing else.
 *
 * <p>The builder and the tree are allocated rather than constructed: their constructors take an
 * {@code android.content.ComponentName} and a naming manager, which the surefire classpath does not
 * carry and which reading the nodes does not need.
 */
public final class DumpTrees {

    private DumpTrees() {
    }

    /**
     * @param resource a classpath resource, e.g. {@code /dynamic-region/retrowars_ingame.xml}
     * @return the tree the jar's reader builds from it, with content descriptions filled in
     */
    public static GUITree load(String resource) throws Exception {
        Document document;
        try (InputStream in = DumpTrees.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no such resource: " + resource);
            }
            document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
        GUITreeBuilder builder = allocate(GUITreeBuilder.class);
        GUITreeNode root = builder.buildNodeFromXml(document);
        copyContentDesc(firstElement(document.getDocumentElement()), root);
        return treeOf(root);
    }

    /** A tree whose root is {@code root}, with nothing else set. */
    public static GUITree treeOf(GUITreeNode root) throws Exception {
        GUITree tree = allocate(GUITree.class);
        Field field = GUITree.class.getDeclaredField("rootNode");
        field.setAccessible(true);
        field.set(tree, root);
        return tree;
    }

    private static void copyContentDesc(Element element, GUITreeNode node) {
        node.setContentDesc(element.getAttribute("content-desc"));
        Iterator<GUITreeNode> children = node.getChildren();
        NodeList list = element.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node child = list.item(i);
            if (child instanceof Element) {
                copyContentDesc((Element) child, children.next());
            }
        }
    }

    private static Element firstElement(Element parent) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element) {
                return (Element) list.item(i);
            }
        }
        throw new IllegalArgumentException("dump has no node");
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }
}
