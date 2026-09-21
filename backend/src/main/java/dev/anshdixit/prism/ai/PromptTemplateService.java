package dev.anshdixit.prism.ai;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads prompt templates from {@code resources/prompts/<task>.{system,user}.txt} and fills {@code {{placeholders}}}.
 * Templates are versioned files in the repository, reviewed like code - never assembled ad hoc in business logic.
 */
@Service
public class PromptTemplateService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String system(LlmTask task) {
        return load(task, "system");
    }

    public String user(LlmTask task, Map<String, ?> values) {
        return render(load(task, "user"), values);
    }

    String render(String template, Map<String, ?> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = values.get(m.group(1));
            String replacement = v == null ? "n/a" : String.valueOf(v);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String load(LlmTask task, String part) {
        String path = "prompts/" + task.name().toLowerCase(Locale.ROOT) + "." + part + ".txt";
        return cache.computeIfAbsent(path, p -> {
            try {
                return new ClassPathResource(p).getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Missing prompt template " + p, e);
            }
        });
    }
}
