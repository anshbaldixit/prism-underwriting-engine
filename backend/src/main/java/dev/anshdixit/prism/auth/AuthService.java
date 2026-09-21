package dev.anshdixit.prism.auth;

import dev.anshdixit.prism.config.PrismProperties;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwt;
    private final Duration ttl;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtEncoder jwt, PrismProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.ttl = Duration.ofMinutes(props.security().tokenTtlMinutes());
    }

    public record Session(String token, String username, String displayName, String role, Instant expiresAt) {
    }

    public Session login(String username, String password) {
        User user = users.findByUsername(username.trim().toLowerCase())
                .filter(u -> encoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("prism")
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("roles", List.of(user.getRole().name()))
                .claim("name", user.getDisplayName())
                .build();
        String token = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new Session(token, user.getUsername(), user.getDisplayName(), user.getRole().name(), now.plus(ttl));
    }
}
