package dev.anshdixit.prism.auth;

import dev.anshdixit.prism.config.PrismProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Creates the three demo users on first start. The password comes from PRISM_SEED_PASSWORD; if that is unset a
 * random one is generated and printed ONCE to the log - there is no default credential anywhere in the code.
 */
@Component
@Order(1)
public class UserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PrismProperties props;

    public UserSeeder(UserRepository users, PasswordEncoder encoder, PrismProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        String password = props.security().seedPassword();
        boolean generated = password == null || password.isBlank();
        if (generated) {
            byte[] buf = new byte[12];
            new SecureRandom().nextBytes(buf);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        }
        String hash = encoder.encode(password);
        users.save(new User("applicant", hash, "Demo Applicant", User.Role.APPLICANT));
        users.save(new User("underwriter", hash, "Dana Underwriter", User.Role.UNDERWRITER));
        users.save(new User("admin", hash, "Model Risk Admin", User.Role.ADMIN));
        if (generated) {
            log.warn("Seeded demo users applicant / underwriter / admin with GENERATED password: {}  (set PRISM_SEED_PASSWORD to choose one)", password);
        } else {
            log.info("Seeded demo users applicant / underwriter / admin with the password from PRISM_SEED_PASSWORD");
        }
    }
}
