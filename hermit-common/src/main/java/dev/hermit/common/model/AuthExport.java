package dev.hermit.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 授权器对外导出的一个登录变量。值一律按秘密处理，这里只有给使用者和规则作者看的说明。 */
public record AuthExport(@JsonInclude(JsonInclude.Include.NON_NULL) String description) {
}
