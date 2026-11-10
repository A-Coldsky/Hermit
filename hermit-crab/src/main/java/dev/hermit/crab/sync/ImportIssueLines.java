package dev.hermit.crab.sync;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import dev.hermit.common.validate.ValidationIssue;

/**
 * Attaches a 1-based YAML line when the finding can be pointed at a key that is actually in the
 * file, or when the message already names a line. A missing key has no line. The message text is
 * left as-is so CLI failure output stays the existing Chinese sentence.
 */
final class ImportIssueLines {
    private static final Pattern LINE_IN_MESSAGE = Pattern.compile("第\\s*(\\d+)\\s*行");

    private ImportIssueLines() {
    }

    static List<ValidationIssue> locate(String yaml, List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        Node root = null;
        boolean composed = false;
        ArrayList<ValidationIssue> copy = new ArrayList<>(issues.size());
        for (ValidationIssue issue : issues) {
            Integer line = positive(issue.line());
            if (line == null) {
                line = lineIn(issue.message());
            }
            if (line == null && hasPath(issue.path())) {
                if (!composed) {
                    root = compose(yaml);
                    composed = true;
                }
                line = lineOf(root, issue.path());
            }
            if (line == null) {
                copy.add(issue.line() == null ? issue : new ValidationIssue(issue.code(), issue.path(), issue.message()));
            } else if (line.equals(issue.line())) {
                copy.add(issue);
            } else {
                copy.add(new ValidationIssue(issue.code(), issue.path(), issue.message(), line));
            }
        }
        return List.copyOf(copy);
    }

    private static Node compose(String yaml) {
        if (yaml == null || yaml.isEmpty()) {
            return null;
        }
        try {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(2 * 1024 * 1024);
            options.setMaxAliasesForCollections(50);
            options.setNestingDepthLimit(64);
            return new Yaml(options).compose(new StringReader(yaml));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Integer lineOf(Node root, String path) {
        if (root == null) {
            return null;
        }
        List<Segment> segments = segments(path);
        if (segments == null) {
            return null;
        }
        Node node = walk(root, segments, 0);
        if (node == null) {
            return null;
        }
        Mark mark = node.getStartMark();
        if (mark == null) {
            return null;
        }
        int line = mark.getLine() + 1;
        return line > 0 ? line : null;
    }

    private static Node walk(Node node, List<Segment> segments, int index) {
        if (node == null || index >= segments.size()) {
            return node;
        }
        Segment segment = segments.get(index);
        if (segment.index != null) {
            if (!(node instanceof SequenceNode sequence) || segment.index >= sequence.getValue().size()) {
                return null;
            }
            return walk(sequence.getValue().get(segment.index), segments, index + 1);
        }
        if (!(node instanceof MappingNode mapping)) {
            return null;
        }
        for (NodeTuple tuple : mapping.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode key) || !segment.name.equals(key.getValue())) {
                continue;
            }
            if (index + 1 == segments.size()) {
                Node located = tuple.getKeyNode();
                return located.getStartMark() != null ? located : tuple.getValueNode();
            }
            return walk(tuple.getValueNode(), segments, index + 1);
        }
        return null;
    }

    private static List<Segment> segments(String path) {
        ArrayList<Segment> parts = new ArrayList<>();
        int i = 0;
        while (i < path.length()) {
            char current = path.charAt(i);
            if (current == '.') {
                if (parts.isEmpty() || i + 1 >= path.length() || path.charAt(i + 1) == '.') {
                    return null;
                }
                i++;
                continue;
            }
            if (current == '[') {
                int end = path.indexOf(']', i + 1);
                if (end < 0 || end == i + 1) {
                    return null;
                }
                int index;
                try {
                    index = Integer.parseInt(path.substring(i + 1, end));
                } catch (NumberFormatException ex) {
                    return null;
                }
                if (index < 0) {
                    return null;
                }
                parts.add(Segment.index(index));
                i = end + 1;
                continue;
            }
            int start = i;
            while (i < path.length()) {
                char next = path.charAt(i);
                if (next == '.' || next == '[') {
                    break;
                }
                if (!isNameChar(next)) {
                    return null;
                }
                i++;
            }
            if (start == i) {
                return null;
            }
            parts.add(Segment.name(path.substring(start, i)));
        }
        return parts.isEmpty() ? null : parts;
    }

    private static boolean isNameChar(char current) {
        return current == '_' || current == '-' || Character.isLetterOrDigit(current);
    }

    private static boolean hasPath(String path) {
        return path != null && !path.isEmpty();
    }

    private static Integer lineIn(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        Matcher matcher = LINE_IN_MESSAGE.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        try {
            return positive(Integer.valueOf(matcher.group(1)));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer positive(Integer line) {
        return line != null && line > 0 ? line : null;
    }

    private record Segment(String name, Integer index) {
        static Segment name(String name) {
            return new Segment(name, null);
        }

        static Segment index(int index) {
            return new Segment(null, index);
        }
    }
}
