package ai.dashaun.kirinuki.edit;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

@Component
public class ThemeCatalog {

    private final Theme theme;

    public ThemeCatalog(KirinukiPipelineProperties properties) {
        this.theme = load(properties.edit().theme());
    }

    public String accent() {
        return theme.accent();
    }

    public List<String> vocabulary() {
        return theme.vocabulary();
    }

    public List<ThemeTopic> matches(String transcript) {
        String lower = transcript.toLowerCase(Locale.ROOT);
        List<ThemeTopic> matched = new ArrayList<>();
        for (ThemeTopic topic : theme.topics()) {
            if (topic.match().stream().anyMatch(keyword -> lower.contains(keyword.toLowerCase(Locale.ROOT)))) {
                matched.add(topic);
            }
        }
        return matched;
    }

    @SuppressWarnings("unchecked")
    private Theme load(String name) {
        ClassPathResource resource = new ClassPathResource("edit/themes/%s.yml".formatted(name));
        if (!resource.exists()) {
            throw new KirinukiException("Theme not found: " + name);
        }
        try (InputStream input = resource.getInputStream()) {
            Map<String, Object> raw = new Yaml().load(input);
            String themeName = (String) raw.get("name");
            String accent = (String) raw.get("accent");
            List<ThemeTopic> topics = new ArrayList<>();
            List<Map<String, Object>> rawTopics = (List<Map<String, Object>>) raw.get("topics");
            if (rawTopics != null) {
                for (Map<String, Object> topic : rawTopics) {
                    topics.add(new ThemeTopic(
                            stringList(topic.get("match")),
                            stringList(topic.get("prefer")),
                            stringList(topic.get("logos"))));
                }
            }
            List<String> vocabulary = stringList(raw.get("vocabulary"));
            return new Theme(themeName, accent, topics, vocabulary);
        } catch (IOException exception) {
            throw new KirinukiException("Could not load theme " + name, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        return (List<String>) value;
    }
}
