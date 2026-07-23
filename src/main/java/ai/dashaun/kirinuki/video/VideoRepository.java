package ai.dashaun.kirinuki.video;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

import ai.dashaun.kirinuki.pipeline.PipelineStatus;

public interface VideoRepository extends ListCrudRepository<Video, UUID> {

    boolean existsByYoutubeId(String youtubeId);

    List<Video> findByStatusIn(List<PipelineStatus> statuses);
}
