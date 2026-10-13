package ai.dashaun.kirinuki.edit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

public interface DesignedEditRepository extends ListCrudRepository<DesignedEdit, UUID> {

    List<DesignedEdit> findByVideoIdOrderByClipIndex(UUID videoId);

    Optional<DesignedEdit> findByVideoIdAndClipIndex(UUID videoId, int clipIndex);

    boolean existsByVideoIdAndClipIndex(UUID videoId, int clipIndex);
}
