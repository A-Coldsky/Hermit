package dev.hermit.reef.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import dev.hermit.reef.config.ReefProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final JwtEncoder encoder;
    private final ReefProperties properties;
    private final Clock clock;

    public JwtService(JwtSecretStore secrets, ReefProperties properties, Clock clock) {
        this.encoder = nimbusEncoder(secrets);
        this.properties = properties;
        this.clock = clock;
    }

    public static JwtDecoder decoder(JwtSecretStore secrets, Clock clock) {
        SecretKeySpec key = new SecretKeySpec(secrets.secret(), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        decoder.setJwtValidator(timestamps);
        return decoder;
    }

    public IssuedJwt issue(String username) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.getJwtTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(username)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedJwt(token, expiresAt);
    }

    private static JwtEncoder nimbusEncoder(JwtSecretStore secrets) {
        OctetSequenceKey key = new OctetSequenceKey.Builder(secrets.secret())
                .algorithm(com.nimbusds.jose.JWSAlgorithm.HS256)
                .build();
        JWKSource<SecurityContext> jwks = new ImmutableJWKSet<>(new JWKSet(key));
        return new NimbusJwtEncoder(jwks);
    }
}
