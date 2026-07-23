package ai.dashaun.kirinuki.video;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        return ResponseEntity.created(URI.create("/videos/" + video.id())).body(video);
    }

    @GetMapping("/{videoId}")
    public ResponseEntity<VideoResponse> findById(@PathVariable UUID videoId) {
        return ResponseEntity.ok(videoService.findById(videoId));
    }
}
