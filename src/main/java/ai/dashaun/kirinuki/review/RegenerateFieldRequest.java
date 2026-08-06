package ai.dashaun.kirinuki.review;

import jakarta.validation.constraints.NotNull;

public record RegenerateFieldRequest(@NotNull ContentField field, String platform) {
}
