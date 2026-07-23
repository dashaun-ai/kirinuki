package ai.dashaun.kirinuki.video;

import jakarta.validation.constraints.NotBlank;

public record IngestVideoRequest(@NotBlank String url) {
}
