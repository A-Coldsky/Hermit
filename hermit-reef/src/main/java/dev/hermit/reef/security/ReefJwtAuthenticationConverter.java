package dev.hermit.reef.security;

import dev.hermit.reef.admin.AdminAccount;
import dev.hermit.reef.admin.AdminUsers;
import dev.hermit.reef.web.ReefErrorCode;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * 授权以数据库里的当前角色为准，不看 JWT 里签发时的 role。用户被禁用或不存在则 401。
 * 改口令或被重置口令后 token_version 变了，之前签出的 JWT 也是 401。
 */
@Component
public class ReefJwtAuthenticationConverter implements Converter<Jwt, JwtAuthenticationToken> {

    private final AdminUsers users;

    public ReefJwtAuthenticationConverter(AdminUsers users) {
        this.users = users;
    }

    /** 升级前签出的令牌没有 tv，按 0 处理。 */
    static int tokenVersion(Jwt jwt) {
        Object value = jwt.getClaims().get(JwtService.VERSION_CLAIM);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return -1;
    }

    @Override
    public JwtAuthenticationToken convert(Jwt jwt) {
        String username = jwt.getSubject();
        AdminAccount account = username == null ? null : users.find(username);
        if (account == null || account.disabled() || tokenVersion(jwt) != account.tokenVersion()) {
            throw new BadCredentialsException(ReefErrorCode.UNAUTHORIZED.message());
        }
        return new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + account.role().name())),
                account.username());
    }
}
