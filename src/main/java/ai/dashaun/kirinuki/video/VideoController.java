package ai.dashaun.kirinuki.video;

import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.dashaun.kirinuki.pipeline.Artifacts;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/videos")
public class VideoController {

    private final VideoService videoService;

    public VideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    @PostMapping
    public ResponseEntity<VideoResponse> ingest(@Valid @RequestBody IngestVideoRequest request) {
        VideoResponse video = videoService.ingest(request.url());
        return ResponseEntity.accepted()
                .location(URI.create("/videos/" + video.id()))
                .body(video);
    }

    @GetMapping("/{videoId}")
    public ResponseEntity<VideoResponse> findById(@PathVariable UUID videoId) {
        return ResponseEntity.ok(videoService.findById(videoId));
    }

    @PostMapping("/{videoId}/advance")
    public ResponseEntity<VideoResponse> advance(@PathVariable UUID videoId) {
        return ResponseEntity.accepted().body(videoService.advance(videoId));
    }

    @GetMapping(value = "/{videoId}/transcript", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> transcript(@PathVariable UUID videoId) {
        return ResponseEntity.ok(new FileSystemResource(videoService.artifactPath(videoId, Artifacts.TRANSCRIPT)));
    }

    @GetMapping(value = "/{videoId}/candidates", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> candidates(@PathVariable UUID videoId) {
        return ResponseEntity.ok(new FileSystemResource(videoService.artifactPath(videoId, Artifacts.CANDIDATES)));
    }

    @GetMapping(value = "/{videoId}/scored", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> scored(@PathVariable UUID videoId) {
        return ResponseEntity.ok(new FileSystemResource(videoService.artifactPath(videoId, Artifacts.SCORED)));
    }

    @GetMapping(value = "/{videoId}/clips", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> clips(@PathVariable UUID videoId) {
        return ResponseEntity.ok(new FileSystemResource(videoService.artifactPath(videoId, Artifacts.CLIPS)));
    }

    @GetMapping(value = "/{videoId}/content", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> content(@PathVariable UUID videoId) {
        return ResponseEntity.ok(new FileSystemResource(videoService.artifactPath(videoId, Artifacts.CONTENT)));
    }

    @GetMapping("/{videoId}/clips/{index}")
    public ResponseEntity<Resource> clip(@PathVariable UUID videoId, @PathVariable int index) {
        Path clip = videoService.clipPath(videoId, index);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(clip.getFileName().toString()).build().toString())
                .body(new FileSystemResource(clip));
    }
}
