package ai.dashaun.kirinuki.pipeline.stage;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineStage;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;
import ai.dashaun.kirinuki.video.YtDlpClient;

@Component
@Order(1)
class DownloadStage implements PipelineStage {

    private final StorageService storageService;
    private final YtDlpClient ytDlpClient;

    DownloadStage(StorageService storageService, YtDlpClient ytDlpClient) {
        this.storageService = storageService;
        this.ytDlpClient = ytDlpClient;
    }

    @Override
    public String artifact() {
        return Artifacts.SOURCE;
    }

    @Override
    public PipelineStatus status() {
        return PipelineStatus.DOWNLOADING;
    }

    @Override
    public PipelineStatus completedStatus() {
        return PipelineStatus.UPLOADED;
    }

    @Override
    public void run(Video video) {
        ytDlpClient.download(video.getSourceUrl(),
                storageService.prepareFor(video.getId().toString(), Artifacts.SOURCE));
    }
}
