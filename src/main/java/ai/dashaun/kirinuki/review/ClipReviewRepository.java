package ai.dashaun.kirinuki.review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClipReviewRepository extends JpaRepository<ClipReview, UUID> {

    List<ClipReview> findByVideoIdOrderByClipIndex(UUID videoId);

    Optional<ClipReview> findByVideoIdAndClipIndex(UUID videoId, int clipIndex);

    boolean existsByVideoId(UUID videoId);
}
