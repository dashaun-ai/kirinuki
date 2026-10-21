package ai.dashaun.kirinuki.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties.Edit.Renderer;

class ThemeCatalogTest {

    private final KirinukiPipelineProperties properties = new KirinukiPipelineProperties(
            null, null, null, null, null, null, null, null, null, null,
            new KirinukiPipelineProperties.Edit(true, true, 3, "spring", "llama4:latest", 0.4, 2048, 0.8, 6, 12,
                    0.6, null, null, null, null, null, null, Renderer.HYBRID, true, Duration.ofMinutes(30)));

    private final ThemeCatalog catalog = new ThemeCatalog(properties);

    @Test
    void should_match_topics_that_appear_in_transcript() {
        List<ThemeTopic> matches = catalog.matches("We used Spring AI with ChatClient and RAG");

        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().match()).contains("spring ai");
        assertThat(matches.getFirst().prefer()).contains("TERMINAL");
    }

    @Test
    void should_return_multiple_matching_topics() {
        List<ThemeTopic> matches = catalog.matches("Virtual threads and GraalVM native image");

        assertThat(matches).hasSize(2);
    }

    @Test
    void should_return_empty_list_when_no_topic_matches() {
        List<ThemeTopic> matches = catalog.matches("Something completely unrelated");

        assertThat(matches).isEmpty();
    }

    @Test
    void should_provide_vocabulary() {
        assertThat(catalog.vocabulary()).contains("Spring AI");
    }

    @Test
    void should_provide_accent() {
        assertThat(catalog.accent()).isEqualTo("#6DB33F");
    }

    @Test
    void should_fail_when_theme_is_missing() {
        KirinukiPipelineProperties missing = new KirinukiPipelineProperties(
                null, null, null, null, null, null, null, null, null, null,
                new KirinukiPipelineProperties.Edit(true, true, 3, "missing", "llama4:latest", 0.4, 2048, 0.8, 6, 12,
                        0.6, null, null, null, null, null, null, Renderer.HYBRID, true, Duration.ofMinutes(30)));

        assertThatThrownBy(() -> new ThemeCatalog(missing)).isInstanceOf(KirinukiException.class);
    }
}
