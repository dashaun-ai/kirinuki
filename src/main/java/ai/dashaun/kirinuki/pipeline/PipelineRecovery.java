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

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        Thread.ofVirtual().name("pipeline-recovery").start(this::resumeUnfinished);
    }

    private void resumeUnfinished() {
        try {
            List<Video> stranded = videoRepository.findAll().stream()
                    .filter(video -> video.getStatus().isResumable())
                    .filter(video -> pipelineOrchestrator.hasPendingWork(video.getId()))
                    .toList();
            if (stranded.isEmpty()) {
                return;
            }
            log.info("Resuming {} unfinished video(s)", stranded.size());
            stranded.forEach(video -> pipelineOrchestrator.startAsync(video.getId()));
        } catch (RuntimeException exception) {
            log.error("Recovery scan failed; videos can still be re-driven with POST /videos/{id}/advance",
                    exception);
        }
    }
}
