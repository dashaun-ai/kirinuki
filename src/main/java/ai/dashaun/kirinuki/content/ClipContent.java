package ai.dashaun.kirinuki.content;

import java.util.List;

public record ClipContent(int clipIndex, String summary, List<String> keywords, List<String> tags) {
}
