package ai.dashaun.kirinuki.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import tools.jackson.databind.json.JsonMapper;

class ScreenAnalyzerTest {

    @TempDir
    Path tempDir;

    private final ProcessRunner processRunner = new ProcessRunner();
    private final JsonMapper objectMapper = new JsonMapper();

    @Test
    void should_detect_screen_region_changes_and_code() throws Exception {
        Path source = createTestVideo(6);
        Path target = tempDir.resolve("screen-analysis.json");

        ScreenAnalyzer analyzer = new ScreenAnalyzer(processRunner, properties(), objectMapper);

        analyzer.analyze(source, target);

        ScreenAnalysis analysis = objectMapper.readValue(Files.readString(target), ScreenAnalysis.class);
        assertThat(analysis.screenRegion()).isNotNull();
        assertThat(analysis.screenRegion().x()).isGreaterThanOrEqualTo(0.0);
        assertThat(analysis.screenRegion().w()).isGreaterThan(0.0);
        assertThat(analysis.hasCode()).isTrue();
    }

    @Test
    void should_write_empty_analysis_for_missing_video() throws Exception {
        Path source = tempDir.resolve("missing.mp4");
        Path target = tempDir.resolve("screen-analysis.json");

        ScreenAnalyzer analyzer = new ScreenAnalyzer(processRunner, properties(), objectMapper);

        assertThatThrownBy(() -> analyzer.analyze(source, target))
                .isInstanceOf(KirinukiException.class)
                .hasMessageContaining("Source video not found");
    }

    private Path createTestVideo(int durationSeconds) throws Exception {
        Path source = tempDir.resolve("source.mp4");
        String text = "public class Hello {\\n  public static void main(String[] args) {\\n    System.out.println(\"hi\");\\n  }\\n}";
        processRunner.run("ffmpeg", List.of("ffmpeg",
                "-y",
                "-hide_banner",
                "-nostats",
                "-f", "lavfi",
                "-i", "color=c=white:s=1280x720:d=%d".formatted(durationSeconds),
                "-f", "lavfi",
                "-i", "anullsrc=channel_layout=mono:sample_rate=48000",
                "-t", String.valueOf(durationSeconds),
                "-vf", ("drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf:" +
                        "text='%s':x=140:y=80:fontsize=28:fontcolor=black:line_spacing=10," +
                        "format=yuv420p").formatted(text),
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-crf", "23",
                "-c:a", "aac",
                "-shortest",
                source.toString()), java.time.Duration.ofMinutes(2));
        return source;
    }

    private KirinukiPipelineProperties properties() {
        return new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("kirinuki.pipeline", KirinukiPipelineProperties.class);
    }
}
