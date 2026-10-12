package dev.hermit.reef.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 128) String username,
        // BCrypt 只取前 72 个 UTF-8 字节，超长必须拒绝，不能按字符数截断。
        @NotBlank @MaxUtf8Bytes(72) String password) {

    @Override
    public String toString() {
        return "LoginRequest[username=" + username + "]";
    }
}
