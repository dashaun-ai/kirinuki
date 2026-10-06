package ai.dashaun.kirinuki.edit;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DesignedEditController {

    private final DesignedEditService designedEditService;

    public DesignedEditController(DesignedEditService designedEditService) {
        this.designedEditService = designedEditService;
    }

    @PostMapping("/videos/{videoId}/clips/{clipIndex}/designed")
    public ResponseEntity<Void> render(@PathVariable UUID videoId, @PathVariable int clipIndex) {
        designedEditService.render(videoId, clipIndex);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/videos/{videoId}/clips/{clipIndex}/designed")
    public ResponseEntity<Resource> download(@PathVariable UUID videoId, @PathVariable int clipIndex)
            throws MalformedURLException {
        if (!designedEditService.isReady(videoId, clipIndex)) {
            return ResponseEntity.notFound().build();
        }
        Path path = designedEditService.outputPath(videoId, clipIndex);
        Resource resource = new UrlResource(path.toUri());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .body(resource);
    }
}
