package dev.anshdixit.prism.ai;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Defence in depth for anything that leaves the service boundary towards an LLM. Prompts are built from
 * derived features, never from identity fields - but if free text (loan purpose, an underwriter's question)
 * carries an SSN, email, phone or card number, it is masked here before the request is sent or logged.
 */
@Component
public class PiiRedactor {

    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern AADHAAR_OR_LONG_ID = Pattern.compile("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d{1,3}[ -]?)?(?:\\(\\d{3}\\)|\\d{3})[ -]?\\d{3}[ -]?\\d{4}(?!\\d)");
    private static final Pattern ACCOUNT = Pattern.compile("(?i)\\b(acct|account|iban|routing)\\s*(no\\.?|number|#)?\\s*[:#]?\\s*\\d{6,}\\b");

    public String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = ACCOUNT.matcher(text).replaceAll("$1 [ACCOUNT]");
        out = CARD.matcher(out).replaceAll("[CARD]");
        out = SSN.matcher(out).replaceAll("[ID]");
        out = AADHAAR_OR_LONG_ID.matcher(out).replaceAll("[ID]");
        out = EMAIL.matcher(out).replaceAll("[EMAIL]");
        out = PHONE.matcher(out).replaceAll("[PHONE]");
        return out;
    }

    public boolean containsPii(String text) {
        return text != null && !redact(text).equals(text);
    }
}
