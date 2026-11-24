package dev.hermit.reef.admin;

/**
 * 登录失败进入提醒阈值或已锁定时附带的结构化详情。
 * 同一失败次数下，未知用户、密码错误、已禁用用户返回相同值，避免暴露账号是否存在。
 */
public record LoginFailureDetail(int remainingAttempts, boolean locked) {
}
