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
 * Finds repeated keys under a top-level {@code scripts} mapping. The parser keeps one of each name,
 * so this has to look at the YAML node, not the parsed document. Other duplicate keys are ignored.
 */
public final class DuplicateScripts {

    private DuplicateScripts() {
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
            if (!(tuple.getKeyNode() instanceof ScalarNode key) || !"scripts".equals(key.getValue())) {
                continue;
            }
            if (tuple.getValueNode() instanceof MappingNode scripts && visited.put(scripts, Boolean.TRUE) == null) {
                collect(scripts, seen, errors);
            }
        }
        return List.copyOf(errors);
    }

    private static void collect(MappingNode scripts, Map<String, Integer> seen, List<ValidationIssue> errors) {
        for (NodeTuple tuple : scripts.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode key) || key.getValue() == null) {
                continue;
            }
            String name = key.getValue();
            Integer previous = seen.putIfAbsent(name, lineOf(key));
            if (previous == null) {
                continue;
            }
            Integer line = positive(lineOf(key));
            errors.add(new ValidationIssue(
                    ValidationCodes.SCRIPT_STEP,
                    ScriptLibraries.path(name),
                    ScriptLibraries.duplicate(name),
                    line));
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
