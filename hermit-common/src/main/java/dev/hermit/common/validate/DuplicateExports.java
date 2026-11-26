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
 * Finds repeated keys under {@code auth.exports}. SnakeYAML keeps only the last key when parsing,
 * so duplicate export names must be detected on the YAML AST node.
 */
public final class DuplicateExports {

    private DuplicateExports() {
    }

    public static List<ValidationIssue> find(String yaml) {
        Node root = compose(yaml);
        if (!(root instanceof MappingNode mapping)) {
            return List.of();
        }
        IdentityHashMap<Node, Boolean> visitedAuth = new IdentityHashMap<>();
        IdentityHashMap<Node, Boolean> visitedExports = new IdentityHashMap<>();
        LinkedHashMap<String, Integer> seen = new LinkedHashMap<>();
        ArrayList<ValidationIssue> errors = new ArrayList<>();
        for (NodeTuple tuple : mapping.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode key) || !"auth".equals(key.getValue())) {
                continue;
            }
            if (tuple.getValueNode() instanceof MappingNode authNode && visitedAuth.put(authNode, Boolean.TRUE) == null) {
                for (NodeTuple authTuple : authNode.getValue()) {
                    if (!(authTuple.getKeyNode() instanceof ScalarNode authKey)
                            || !"exports".equals(authKey.getValue())) {
                        continue;
                    }
                    if (authTuple.getValueNode() instanceof MappingNode exportsNode
                            && visitedExports.put(exportsNode, Boolean.TRUE) == null) {
                        collect(exportsNode, seen, errors);
                    }
                }
            }
        }
        return List.copyOf(errors);
    }

    private static void collect(MappingNode exports, Map<String, Integer> seen, List<ValidationIssue> errors) {
        for (NodeTuple tuple : exports.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode key) || key.getValue() == null) {
                continue;
            }
            String name = key.getValue();
            Integer previous = seen.putIfAbsent(name, lineOf(key));
            if (previous == null) {
                continue;
            }
            String path = AuthenticatorChecks.isExportIdentifier(name) ? "auth.exports." + name : "auth.exports";
            String message = name.isEmpty() ? "导出名重复" : "导出名 " + name + " 重复";
            errors.add(new ValidationIssue(
                    ValidationCodes.AUTHENTICATOR,
                    path,
                    message,
                    positive(lineOf(key))));
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
}
