package dev.hermit.common.version;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SemVer 2.0 的 MAJOR.MINOR.PATCH 与预发布后缀。不接受 build metadata（{@code +}）。
 */
public record SemVer(int major, int minor, int patch, List<String> prerelease) implements Comparable<SemVer> {

    private static final Pattern PATTERN = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");

    public SemVer {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("版本号不能为负");
        }
        prerelease = List.copyOf(prerelease);
        for (String id : prerelease) {
            if (isNumeric(id) && id.length() > 1 && id.charAt(0) == '0') {
                throw new IllegalArgumentException("预发布数字标识不能有前导零");
            }
        }
    }

    public static SemVer parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("版本号为空");
        }
        String trimmed = text.trim();
        if (trimmed.indexOf('+') >= 0) {
            throw new IllegalArgumentException("不支持 build metadata");
        }
        Matcher matcher = PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("不是合法的 semver: " + trimmed);
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = Integer.parseInt(matcher.group(3));
        String pre = matcher.group(4);
        List<String> prerelease = pre == null ? List.of() : List.of(pre.split("\\.", -1));
        return new SemVer(major, minor, patch, prerelease);
    }

    @Override
    public int compareTo(SemVer other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, other.minor);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(patch, other.patch);
        if (c != 0) {
            return c;
        }
        return comparePrerelease(prerelease, other.prerelease);
    }

    @Override
    public String toString() {
        String core = major + "." + minor + "." + patch;
        if (prerelease.isEmpty()) {
            return core;
        }
        return core + "-" + String.join(".", prerelease);
    }

    private static int comparePrerelease(List<String> left, List<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 0;
        }
        if (left.isEmpty()) {
            return 1;
        }
        if (right.isEmpty()) {
            return -1;
        }
        int n = Math.min(left.size(), right.size());
        for (int i = 0; i < n; i++) {
            int c = compareIdentifier(left.get(i), right.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNum = isNumeric(left);
        boolean rightNum = isNumeric(right);
        if (leftNum && rightNum) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        if (leftNum) {
            return -1;
        }
        if (rightNum) {
            return 1;
        }
        return left.compareTo(right);
    }

    private static boolean isNumeric(String id) {
        if (id.isEmpty()) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            if (!Character.isDigit(id.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
