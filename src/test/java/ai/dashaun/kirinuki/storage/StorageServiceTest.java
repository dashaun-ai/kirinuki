package ai.dashaun.kirinuki.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiStorageProperties;

class StorageServiceTest {

    private static final String VIDEO_ID = "3f2a9c5e-0000-0000-0000-000000000001";

    @TempDir
    Path root;

    private StorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new StorageService(new KirinukiStorageProperties(root));
    }

    @Test
    void should_put_the_part_marker_before_the_extension_when_the_artifact_has_one() {
        Path temporary = storageService.temporaryFor(VIDEO_ID, "audio.wav");

        assertThat(temporary).isEqualTo(root.resolve(VIDEO_ID).resolve("audio.part.wav"));
    }

    @Test
    void should_append_the_part_marker_when_the_artifact_has_no_extension() {
        Path temporary = storageService.temporaryFor(VIDEO_ID, "clips");

        assertThat(temporary).isEqualTo(root.resolve(VIDEO_ID).resolve("clips.part"));
    }

    @Test
    void should_mark_only_the_file_name_when_the_artifact_is_nested() {
        Path temporary = storageService.temporaryFor(VIDEO_ID, "clips/clip-1.mp4");

        assertThat(temporary).isEqualTo(root.resolve(VIDEO_ID).resolve("clips").resolve("clip-1.part.mp4"));
    }

    @Test
    void should_create_the_parent_directories_when_preparing_a_nested_artifact() {
        Path target = storageService.prepareFor(VIDEO_ID, "clips/clip-1.mp4");

        assertThat(target.getParent()).isDirectory();
    }

    @Test
    void should_hide_the_artifact_when_only_the_temporary_file_exists() throws IOException {
        Files.writeString(storageService.temporaryFor(VIDEO_ID, "transcript.json"), "{}");

        assertThat(storageService.exists(VIDEO_ID, "transcript.json")).isFalse();
    }

    @Test
    void should_publish_the_temporary_file_when_the_artifact_is_committed() throws IOException {
        Files.writeString(storageService.temporaryFor(VIDEO_ID, "transcript.json"), "{\"done\":true}");

        storageService.commit(VIDEO_ID, "transcript.json");

        assertThat(storageService.resolve(VIDEO_ID, "transcript.json")).hasContent("{\"done\":true}");
    }

    @Test
    void should_leave_no_temporary_file_behind_when_the_artifact_is_committed() throws IOException {
        Path temporary = storageService.temporaryFor(VIDEO_ID, "transcript.json");
        Files.writeString(temporary, "{}");

        storageService.commit(VIDEO_ID, "transcript.json");

        assertThat(temporary).doesNotExist();
    }

    @Test
    void should_fail_when_committing_an_artifact_that_was_never_written() {
        storageService.prepareFor(VIDEO_ID, "transcript.json");

        assertThatExceptionOfType(KirinukiException.class)
                .isThrownBy(() -> storageService.commit(VIDEO_ID, "transcript.json"))
                .withMessageContaining("Could not commit transcript.json");
    }

    @Test
    void should_delete_the_temporary_file_when_the_artifact_is_discarded() throws IOException {
        Path temporary = storageService.temporaryFor(VIDEO_ID, "audio.wav");
        Files.writeString(temporary, "truncated");

        storageService.discardTemporary(VIDEO_ID, "audio.wav");

        assertThat(temporary).doesNotExist();
    }

    @Test
    void should_stay_quiet_when_discarding_an_artifact_that_has_no_temporary_file() {
        assertThatCode(() -> storageService.discardTemporary(VIDEO_ID, "audio.wav")).doesNotThrowAnyException();
    }

    @Test
    void should_keep_each_video_in_its_own_directory() {
        assertThat(storageService.videoDirectory(VIDEO_ID)).isEqualTo(root.resolve(VIDEO_ID));
    }
}
