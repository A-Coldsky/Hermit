package dev.hermit.common.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.hermit.common.version.SemVer;

/**
 * 能力分组（一级与二级目录）的公共查询与匹配规则。
 * <ul>
 *   <li>分组路径形如 {@code parent} 或 {@code parent/child}，最多两层。</li>
 *   <li>一级分组匹配其直属能力以及所有下属二级子分组中的能力。</li>
 *   <li>未写 {@code group} 的能力为顶层直挂能力，不属于任何分组。</li>
 * </ul>
 */
public final class CapabilityGroups {

    public static final SemVer MIN_CRAB_VERSION = SemVer.parse("1.10.0");

    private CapabilityGroups() {
    }

    /** 规则包是否使用了 groups 声明或任意能力的 group 字段（含显式空映射或 null，避免低版本 Crab 解析失败）。 */
    public static boolean usesGroups(ConchDoc doc) {
        if (doc == null) {
            return false;
        }
        if (doc.groupsDeclared() || !doc.groups().isEmpty()) {
            return true;
        }
        for (Action action : doc.actions().values()) {
            if (action != null && (action.groupDeclared() || action.group() != null)) {
                return true;
            }
        }
        return false;
    }

    /** 规则包里是否声明了该分组路径（{@code parent} 或 {@code parent/child}）。 */
    public static boolean hasGroup(ConchDoc doc, String path) {
        return find(doc, path) != null;
    }

    /** 查找已声明的一级或二级分组定义；不存在或层级超过两层时返回 {@code null}。 */
    public static GroupDef find(ConchDoc doc, String path) {
        if (doc == null || path == null || path.isEmpty()) {
            return null;
        }
        int slash = path.indexOf('/');
        if (slash < 0) {
            return doc.groups().get(path);
        }
        if (slash == 0 || slash == path.length() - 1 || path.indexOf('/', slash + 1) >= 0) {
            return null;
        }
        String parent = path.substring(0, slash);
        String child = path.substring(slash + 1);
        GroupDef parentGroup = doc.groups().get(parent);
        return parentGroup == null ? null : parentGroup.children().get(child);
    }

    /**
     * 能力的分组路径是否命中给定的分组筛选/授权路径。
     * 一级分组（{@code approval}）同时命中 {@code approval} 和 {@code approval/leave}；
     * 二级分组（{@code approval/leave}）只命中自身。未分组能力（{@code null}）不命中任何分组。
     */
    public static boolean matches(String actionGroup, String filterGroup) {
        if (actionGroup == null || actionGroup.isEmpty() || filterGroup == null || filterGroup.isEmpty()) {
            return false;
        }
        return actionGroup.equals(filterGroup) || actionGroup.startsWith(filterGroup + "/");
    }

    /**
     * 格式化分组的展示标题：一级返回标题（缺省为键），二级返回「一级标题 / 二级标题」。
     * 找不到声明时原样返回路径。
     */
    public static String displayTitle(ConchDoc doc, String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        if (doc == null) {
            return path;
        }
        int slash = path.indexOf('/');
        if (slash < 0) {
            GroupDef group = doc.groups().get(path);
            return group == null ? path : label(group);
        }
        if (slash == 0 || slash == path.length() - 1 || path.indexOf('/', slash + 1) >= 0) {
            return path;
        }
        String parentKey = path.substring(0, slash);
        String childKey = path.substring(slash + 1);
        GroupDef parent = doc.groups().get(parentKey);
        if (parent == null) {
            return path;
        }
        GroupDef child = parent.children().get(childKey);
        if (child == null) {
            return path;
        }
        return label(parent) + " / " + label(child);
    }

    /** 按文档顺序展平所有已声明的分组路径及定义（{@code parent} 在前，随后是其 {@code parent/child}）。 */
    public static Map<String, GroupDef> flatten(ConchDoc doc) {
        if (doc == null || doc.groups().isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, GroupDef> flat = new LinkedHashMap<>();
        for (Map.Entry<String, GroupDef> entry : doc.groups().entrySet()) {
            GroupDef group = entry.getValue();
            flat.put(entry.getKey(), group);
            for (Map.Entry<String, GroupDef> child : group.children().entrySet()) {
                flat.put(entry.getKey() + "/" + child.getKey(), child.getValue());
            }
        }
        return Frozen.ordered(flat);
    }

    /** 返回属于指定分组（含一级组下的所有二级子组）的能力列表，保持文档顺序。 */
    public static List<Action> actionsInGroup(ConchDoc doc, String groupPath) {
        if (doc == null || groupPath == null || groupPath.isEmpty()) {
            return List.of();
        }
        ArrayList<Action> matched = new ArrayList<>();
        for (Action action : doc.actions().values()) {
            if (action != null && matches(action.group(), groupPath)) {
                matched.add(action);
            }
        }
        return List.copyOf(matched);
    }

    private static String label(GroupDef group) {
        return group.title() == null || group.title().isBlank() ? group.name() : group.title();
    }
}
