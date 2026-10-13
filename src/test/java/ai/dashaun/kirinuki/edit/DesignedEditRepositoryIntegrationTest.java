package ai.dashaun.kirinuki.edit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ai.dashaun.kirinuki.AbstractIntegrationTest;

class DesignedEditRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DesignedEditRepository repository;

    @Test
    void should_persist_and_find_by_video_and_clip_index() {
        UUID videoId = UUID.randomUUID();
        DesignedEdit edit = new DesignedEdit(UUID.randomUUID(), videoId, 1, DesignedEditStatus.QUEUED, null, null,
                Instant.now(), Instant.now());

        repository.save(edit);

        assertThat(repository.findByVideoIdAndClipIndex(videoId, 1)).isPresent();
        assertThat(repository.findByVideoIdOrderByClipIndex(videoId)).hasSize(1);
    }
}
