package ai.dashaun.kirinuki.edit;

import java.util.List;

public record Theme(String name, String accent, List<ThemeTopic> topics, List<String> vocabulary) {
}
