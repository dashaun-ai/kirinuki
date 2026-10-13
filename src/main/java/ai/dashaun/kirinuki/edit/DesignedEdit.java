package ai.dashaun.kirinuki.edit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"video_id", "clip_index"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DesignedEdit {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID videoId;

    @Column(nullable = false)
    private int clipIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DesignedEditStatus status;

    @Column(columnDefinition = "text")
    private String lastError;

    @Version
    private Long version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
