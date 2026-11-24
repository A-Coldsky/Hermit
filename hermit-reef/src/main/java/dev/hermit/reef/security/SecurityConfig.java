package dev.hermit.reef.security;

import dev.hermit.reef.admin.AgentTokenService;
import dev.hermit.reef.web.ApiResponseWriter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.HeaderWriter;

@Configuration
public class SecurityConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtSecretStore secrets, Clock clock) {
        return JwtService.decoder(secrets, clock);
    }

    @Bean
    @Order(1)
    SecurityFilterChain agentChain(HttpSecurity http, ApiResponseWriter writer, AgentTokenService tokens,
            AgentPresence presence) throws Exception {
        return http
                .securityMatcher("/agent/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new AgentAccessFilter(writer, tokens, presence), AuthorizationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, JwtDecoder jwtDecoder,
            ReefJwtAuthenticationConverter jwtAuthenticationConverter, ApiAuthenticationEntryPoint entryPoint,
            ApiAccessDeniedHandler denied) throws Exception {
        return http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/admin/login").permitAll()
                        // USER 只能读目录、Tide、告警，以及改自己的口令和令牌。其余 /api/admin 要 ADMIN。
                        .requestMatchers(HttpMethod.GET, "/api/admin/me").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.POST, "/api/admin/me/password").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/signing-key").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/conches").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/conches/*").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/conches/*/versions").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/conches/*/versions/*/yaml")
                        .hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/conches/*/diff").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/tide/**").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/alerts").hasAnyRole("ADMIN", "USER")
                        // 作废配置令牌只给 ADMIN。不要并进下面的 /tokens/*，那条 DELETE 允许 USER 吊销自己的库内令牌。
                        .requestMatchers(HttpMethod.POST, "/api/admin/tokens/config/revoke").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/admin/tokens").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.POST, "/api/admin/tokens").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/tokens/*").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/crabs").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/announcements").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/crab/download-info").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/crab/download").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/submissions").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/submissions/pending-count").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/submissions/*").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/submissions/*/yaml").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.POST, "/api/admin/submissions").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.POST, "/api/admin/submissions/*/withdraw").hasAnyRole("ADMIN", "USER")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied))
                .build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain denyByDefault(HttpSecurity http, ApiAuthenticationEntryPoint entryPoint,
            ApiAccessDeniedHandler denied) throws Exception {
        return http
                .securityMatcher("/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
                .headers(headers -> headers
                        .cacheControl(cache -> cache.disable())
                        .addHeaderWriter(cacheControlIfAbsent()))
                .authorizeHttpRequests(auth -> auth
                        // /api/** 与 /agent/** 在更前面的链上。剩下的 GET 是页面导航，回 SPA，不回 401 JSON。
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    // 默认的 Cache-Control 写在响应提交时，会盖住静态资源自己的头。没写过头的拒绝响应仍不进缓存。
    private static HeaderWriter cacheControlIfAbsent() {
        return (request, response) -> {
            if (response.containsHeader(HttpHeaders.CACHE_CONTROL)) {
                return;
            }
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate");
            response.setHeader(HttpHeaders.PRAGMA, "no-cache");
            response.setHeader(HttpHeaders.EXPIRES, "0");
        };
    }
}
