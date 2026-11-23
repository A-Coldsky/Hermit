package dev.hermit.common.validate;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;

/**
 * Finds repeated keys under a top-level {@code groups} mapping and each group's {@code children}
 * mapping. The parser keeps one of each name, so this has to look at the YAML node, not the parsed
 * document. Other duplicate keys are ignored.
 */
public final class DuplicateGroups {

    private DuplicateGroups() {
    }

    public static List<ValidationIssue> find(String yaml) {
        Node root = compose(yaml);
        if (!(root instanceof MappingNode mapping)) {
            return List.of();
        }
        LinkedHashMap<String, Integer> seen = new LinkedHashMap<>();
        IdentityHashMap<Node, Boolean> visited = new IdentityHashMap<>();
        ArrayList<ValidationIssue> errors = new ArrayList<>();
        for (NodeTuple tuple : mapping.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode key) || !"groups".equals(key.getValue())) {
                continue;
            }
            if (tuple.getValueNode() instanceof MappingNode groups && visited.put(groups, Boolean.TRUE) == null) {
                collectGroups(groups, seen, errors);
            }
        }
        return List.copyOf(errors);
    }

    private static void collectGroups(
            MappingNode groups,
            Map<String, Integer> seen,
            List<ValidationIssue> errors
    ) {
        for (NodeTuple tuple : groups.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode key) || key.getValue() == null) {
                continue;
            }
            String name = key.getValue();
            Integer previous = seen.putIfAbsent(name, lineOf(key));
            if (previous != null) {
                errors.add(new ValidationIssue(
                        ValidationCodes.GROUP_NAME,
                        groupPath(name),
                        groupMessage(name),
                        positive(lineOf(key))));
            }
            if (tuple.getValueNode() instanceof MappingNode groupDef) {
                collectChildren(name, groupDef, errors);
            }
        }
    }

    private static void collectChildren(
            String parentName,
            MappingNode groupDef,
            List<ValidationIssue> errors
    ) {
        LinkedHashMap<String, Integer> seen = new LinkedHashMap<>();
        IdentityHashMap<Node, Boolean> visited = new IdentityHashMap<>();
        for (NodeTuple tuple : groupDef.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode key) || !"children".equals(key.getValue())) {
                continue;
            }
            if (tuple.getValueNode() instanceof MappingNode children && visited.put(children, Boolean.TRUE) == null) {
                for (NodeTuple childTuple : children.getValue()) {
                    if (!(childTuple.getKeyNode() instanceof ScalarNode childKey) || childKey.getValue() == null) {
                        continue;
                    }
                    String childName = childKey.getValue();
                    Integer previous = seen.putIfAbsent(childName, lineOf(childKey));
                    if (previous == null) {
                        continue;
                    }
                    errors.add(new ValidationIssue(
                            ValidationCodes.GROUP_NAME,
                            childPath(parentName, childName),
                            childMessage(childName),
                            positive(lineOf(childKey))));
                }
            }
        }
    }

    private static Node compose(String yaml) {
        if (yaml == null || yaml.isEmpty()) {
            return null;
        }
        try {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(4 * 1024 * 1024);
            options.setAllowDuplicateKeys(true);
            return new Yaml(options).compose(new StringReader(yaml));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static int lineOf(ScalarNode key) {
        Mark mark = key.getStartMark();
        if (mark == null || mark.getLine() < 0) {
            return 0;
        }
        return mark.getLine() + 1;
    }

    private static Integer positive(int line) {
        return line > 0 ? line : null;
    }

    private static String groupPath(String name) {
        if (name.isEmpty() || !simple(name)) {
            return "groups";
        }
        return "groups." + name;
    }

    private static String childPath(String parent, String child) {
        if (parent.isEmpty() || !simple(parent)) {
            return "groups";
        }
        if (child.isEmpty() || !simple(child)) {
            return "groups." + parent + ".children";
        }
        return "groups." + parent + ".children." + child;
    }

    private static boolean simple(String name) {
        for (int i = 0; i < name.length(); i++) {
            char current = name.charAt(i);
            if (current != '_' && current != '-' && !Character.isLetterOrDigit(current)) {
                return false;
            }
        }
        return true;
    }

    private static String groupMessage(String name) {
        if (name.isEmpty()) {
            return "分组名重复";
        }
        return "分组名 " + name + " 重复";
    }

    private static String childMessage(String name) {
        if (name.isEmpty()) {
            return "子分组名重复";
        }
        return "子分组名 " + name + " 重复";
    }
}
