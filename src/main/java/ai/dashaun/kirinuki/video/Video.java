package ai.dashaun.kirinuki.video;

import java.time.Instant;
import java.util.UUID;

import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Video {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 16)
    private String youtubeId;

    @Column(nullable = false, length = 512)
    private String sourceUrl;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(nullable = false)
    private Integer durationSeconds;

    private String uploader;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PipelineStatus status;

    @Column(nullable = false)
    private Instant ingestedAt;
}
