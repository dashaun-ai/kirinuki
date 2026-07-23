package ai.dashaun.kirinuki.video;

import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

public interface VideoRepository extends ListCrudRepository<Video, UUID> {

    boolean existsByYoutubeId(String youtubeId);
}
