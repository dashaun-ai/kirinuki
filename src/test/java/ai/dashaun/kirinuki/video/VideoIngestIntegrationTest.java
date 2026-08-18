package ai.dashaun.kirinuki.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

import ai.dashaun.kirinuki.AbstractIntegrationTest;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineOrchestrator;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.storage.StorageService;
import org.springframework.beans.factory.annotation.Autowired;

class VideoIngestIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

    @MockitoBean
    private YtDlpClient ytDlpClient;

    @MockitoBean
    private PipelineOrchestrator pipelineOrchestrator;

    @Autowired
    private StorageService storageService;

    @BeforeEach
    void stubMetadata() {
        when(ytDlpClient.fetchMetadata(anyString()))
                .thenReturn(new YouTubeMetadata("dQw4w9WgXcQ", "Spring Boot 4 in anger", 840, "your_javaguy"));
    }

    @Test
    void should_answer_accepted_at_downloading_when_a_video_is_ingested() {
        VideoResponse response = ingest(URL);

        assertThat(response.status()).isEqualTo(PipelineStatus.DOWNLOADING);
        assertThat(response.youtubeId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(response.title()).isEqualTo("Spring Boot 4 in anger");
    }

    @Test
    void should_point_the_location_header_at_the_new_video() {
        EntityExchangeResult<VideoResponse> result = client.post().uri("/videos")
                .contentType(MediaType.APPLICATION_JSON)
                .body(ingestBody(URL))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(VideoResponse.class)
                .returnResult();

        assertThat(result.getResponseHeaders().getLocation())
                .isEqualTo(URI.create("/videos/" + result.getResponseBody().id()));
    }

    @Test
    void should_serve_the_video_at_the_url_the_location_header_advertises() {
        VideoResponse response = ingest(URL);

        client.get().uri("/videos/{videoId}", response.id())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void should_persist_the_video_when_it_is_ingested() {
        VideoResponse response = ingest(URL);

        assertThat(videoRepository.findById(response.id()))
                .get()
                .extracting(Video::getStatus, Video::getSourceUrl)
                .containsExactly(PipelineStatus.DOWNLOADING, URL);
    }

    @Test
    void should_start_the_pipeline_when_a_video_is_ingested() {
        VideoResponse response = ingest(URL);

        verify(pipelineOrchestrator).startAsync(response.id());
    }

    @Test
    void should_answer_conflict_when_the_same_video_is_ingested_twice() {
        ingest(URL);

        ProblemDetail problem = client.post().uri("/videos")
                .contentType(MediaType.APPLICATION_JSON)
                .body(ingestBody("https://youtu.be/dQw4w9WgXcQ"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem.getTitle()).isEqualTo("Video already ingested");
    }

    @Test
    void should_answer_bad_request_when_the_url_is_not_a_youtube_video() {
        ProblemDetail problem = client.post().uri("/videos")
                .contentType(MediaType.APPLICATION_JSON)
                .body(ingestBody("https://vimeo.com/123456789"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem.getTitle()).isEqualTo("Invalid video URL");
        assertThat(problem.getDetail()).contains("Not a recognisable YouTube video URL");
    }

    @Test
    void should_answer_bad_request_when_the_url_is_blank() {
        client.post().uri("/videos")
                .contentType(MediaType.APPLICATION_JSON)
                .body(ingestBody(""))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ProblemDetail.class);
    }

    @Test
    void should_answer_bad_request_when_the_request_body_is_not_json() {
        client.post().uri("/videos")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{not json")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void should_answer_not_found_when_the_video_is_unknown() {
        ProblemDetail problem = client.get().uri("/videos/{videoId}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem.getTitle()).isEqualTo("Video not found");
    }

    @Test
    void should_carry_the_last_error_when_a_stalled_video_is_read() {
        Video video = persisted(PipelineStatus.AI_ANALYSIS);
        video.setLastError("model unavailable");
        videoRepository.save(video);

        VideoResponse response = client.get().uri("/videos/{videoId}", video.getId())
                .exchange()
                .expectStatus().isOk()
                .expectBody(VideoResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response.lastError()).isEqualTo("model unavailable");
    }

    @Test
    void should_answer_accepted_when_a_stalled_video_is_advanced() {
        Video video = persisted(PipelineStatus.AI_ANALYSIS);

        client.post().uri("/videos/{videoId}/advance", video.getId())
                .exchange()
                .expectStatus().isAccepted();

        verify(pipelineOrchestrator).startAsync(video.getId());
    }

    @Test
    void should_answer_conflict_when_advancing_a_video_that_waits_on_review() {
        Video video = persisted(PipelineStatus.READY_FOR_REVIEW);

        ProblemDetail problem = client.post().uri("/videos/{videoId}/advance", video.getId())
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem.getTitle()).isEqualTo("Video cannot be advanced");
    }

    @Test
    void should_answer_not_found_when_the_artifact_has_not_been_produced_yet() {
        Video video = persisted(PipelineStatus.DOWNLOADING);

        ProblemDetail problem = client.get().uri("/videos/{videoId}/transcript", video.getId())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem.getTitle()).isEqualTo("Artifact not found");
    }

    @Test
    void should_serve_the_transcript_when_the_artifact_exists() throws IOException {
        Video video = persisted(PipelineStatus.READY_FOR_REVIEW);
        writeArtifact(video.getId(), Artifacts.TRANSCRIPT, "{\"segments\":[]}");

        client.get().uri("/videos/{videoId}/transcript", video.getId())
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"segments\":[]}");
    }

    @Test
    void should_offer_the_clip_as_an_mp4_attachment() throws IOException {
        Video video = persisted(PipelineStatus.READY_FOR_REVIEW);
        writeArtifact(video.getId(), Artifacts.CLIP_DIRECTORY + "/clip-2.mp4", "not really an mp4");

        client.get().uri("/videos/{videoId}/clips/{index}", video.getId(), 2)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.parseMediaType("video/mp4"))
                .expectHeader().valueEquals(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"clip-2.mp4\"");
    }

    private VideoResponse ingest(String url) {
        return client.post().uri("/videos")
                .contentType(MediaType.APPLICATION_JSON)
                .body(ingestBody(url))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(VideoResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private String ingestBody(String url) {
        return "{\"url\": \"%s\"}".formatted(url);
    }

    private Video persisted(PipelineStatus status) {
        return videoRepository.save(new Video(UUID.randomUUID(), "dQw4w9WgXcQ", URL, "Spring Boot 4 in anger", 840,
                "your_javaguy", status, Instant.now(), null));
    }

    private void writeArtifact(UUID videoId, String artifact, String content) throws IOException {
        Path target = storageService.prepareFor(videoId.toString(), artifact);
        Files.writeString(target, content);
    }
}
