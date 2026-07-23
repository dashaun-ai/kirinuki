package ai.dashaun.kirinuki.pipeline;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.video.Video;
import ai.dashaun.kirinuki.video.VideoRepository;

@Component
class PipelineRecovery {

    private static final Logger log = LoggerFactory.getLogger(PipelineRecovery.class);

    private final VideoRepository videoRepository;
    private final PipelineOrchestrator pipelineOrchestrator;

    PipelineRecovery(VideoRepository videoRepository, PipelineOrchestrator pipelineOrchestrator) {
        this.videoRepository = videoRepository;
        this.pipelineOrchestrator = pipelineOrchestrator;
    }

    // A crash or restart leaves videos stranded mid-stage; completed stages are skipped on their
    // artifact, so re-driving costs only the stage that was interrupted (FR-WF-6).
    @EventListener(ApplicationReadyEvent.class)
    void resumeUnfinished() {
        List<Video> unfinished = videoRepository.findByStatusIn(PipelineStatus.RESUMABLE);
        if (unfinished.isEmpty()) {
            return;
        }
        log.info("Resuming {} unfinished video(s)", unfinished.size());
        unfinished.forEach(video -> pipelineOrchestrator.startAsync(video.getId()));
    }
}
