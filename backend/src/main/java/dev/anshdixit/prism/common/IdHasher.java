package dev.anshdixit.prism.common;

import dev.anshdixit.prism.config.PrismProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Keyed one-way hash for national identifiers. The raw number is never stored; the HMAC lets the system
 * detect the same identity across applications (synthetic-identity and duplicate checks) without holding it.
 */
@Component
public class IdHasher {

    private final byte[] key;

    public IdHasher(PrismProperties props) {
        this.key = ("prism-id-hash:" + props.security().jwtSecret()).getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String nationalId) {
        if (nationalId == null || nationalId.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(nationalId.replaceAll("[^0-9]", "").getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
