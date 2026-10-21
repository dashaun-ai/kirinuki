package ai.dashaun.kirinuki.edit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

@Component
public class KineticRenderer {

    private static final Logger log = LoggerFactory.getLogger(KineticRenderer.class);

    private final ProcessRunner processRunner;
    private final String skillDir;
    private final String kit;
    private final String template;
    private final Duration timeout;

    public KineticRenderer(ProcessRunner processRunner, KirinukiPipelineProperties properties) {
        this.processRunner = processRunner;
        this.skillDir = properties.edit().kineticSkillDir();
        this.kit = properties.edit().kineticKit();
        this.template = properties.edit().kineticTemplate();
        this.timeout = properties.media().timeout();
    }

    public void render(KineticSpec kinetic, Path audioSlice, Path output) {
        if (kinetic == null || kinetic.items() == null || kinetic.items().isEmpty()) {
            throw new KirinukiException("Kinetic spec has no items");
        }
        if (skillDir == null || kit == null) {
            throw new KirinukiException("Kinetic skill directory or kit is not configured");
        }
        Path skillPath = Path.of(skillDir);
        Path kitPath = Path.of(kit);
        if (!Files.exists(skillPath)) {
            throw new KirinukiException("Kinetic skill directory not found: " + skillDir);
        }
        if (!Files.exists(kitPath)) {
            throw new KirinukiException("Kinetic kit not found: " + kit);
        }
        if (!Files.exists(audioSlice)) {
            throw new KirinukiException("Audio slice not found: " + audioSlice);
        }
        log.info("Rendering kinetic segment: {} -> {}", kinetic.title(), output.getFileName());
        Path scripts = skillPath.resolve("scripts");
        Path work = null;
        try {
            work = Files.createTempDirectory("kirinuki-kinetic");
            Path voice = work.resolve("voice.wav");
            Files.copy(audioSlice, voice);
            double duration = probeDuration(voice);

            runSetup(scripts, kitPath);
            fillTemplate(scripts, work, duration, kitPath);
            patchIndexHtml(work.resolve("index.html"), kinetic, duration);
            runRenderCues(scripts, work);
            runSynthAudio(scripts, work, duration);
            runRenderChunks(scripts, work, duration, voice, output);
        } catch (Exception exception) {
            throw new KirinukiException("Could not render kinetic segment to " + output, exception);
        } finally {
            deleteRecursively(work);
        }
    }

    private void runSetup(Path scripts, Path kitPath) {
        processRunner.run("kinetic_setup", List.of("bash", scripts.resolve("setup.sh").toString(), kitPath.toString()),
                timeout);
    }

    private void fillTemplate(Path scripts, Path work, double duration, Path kitPath) {
        processRunner.run("kinetic_fill_template",
                List.of("python3", scripts.resolve("fill_template.py").toString(), work.toString(),
                        String.valueOf(duration), "--format", "vertical", "--template", template,
                        "--brand", kitPath.toString()),
                timeout);
    }

    private void runRenderCues(Path scripts, Path work) {
        processRunner.run("kinetic_cues",
                List.of("node", scripts.resolve("render.js").toString(), "cues", work.resolve("index.html").toString(),
                        work.resolve("cues.json").toString()),
                timeout);
    }

    private void runSynthAudio(Path scripts, Path work, double duration) {
        processRunner.run("kinetic_synth_audio",
                List.of("python3", scripts.resolve("synth_audio.py").toString(),
                        work.resolve("cues.json").toString(), String.valueOf(duration), work.toString()),
                timeout);
    }

    private void runRenderChunks(Path scripts, Path work, double duration, Path voice, Path output) {
        processRunner.run("kinetic_render_chunks",
                List.of("bash", scripts.resolve("render-chunks.sh").toString(), work.resolve("index.html").toString(),
                        work.toString(), String.valueOf(duration), voice.toString(), output.toString()),
                timeout);
    }

    private double probeDuration(Path audio) {
        String binary = "ffprobe";
        String output = processRunner.run(binary, List.of(binary,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "csv=p=0",
                audio.toString()), timeout);
        try {
            return Double.parseDouble(output.strip());
        } catch (NumberFormatException exception) {
            throw new KirinukiException("Could not probe audio duration: " + output, exception);
        }
    }

    private void patchIndexHtml(Path index, KineticSpec kinetic, double duration) throws IOException {
        String html = Files.readString(index);
        html = replaceBetween(html, "<!-- BEGIN SHOTS -->", "<!-- END SHOTS -->", buildShotsHtml(kinetic));
        html = replaceBetween(html, "// BEGIN TIMELINE", "// END TIMELINE", buildTimeline(kinetic, duration));
        Files.writeString(index, html);
    }

    private String replaceBetween(String text, String start, String end, String replacement) {
        Pattern pattern = Pattern.compile(Pattern.quote(start) + ".*?" + Pattern.quote(end), Pattern.DOTALL);
        return pattern.matcher(text).replaceFirst(start + "\n" + replacement + "\n" + end);
    }

    private String buildShotsHtml(KineticSpec kinetic) {
        StringBuilder sections = new StringBuilder();
        int index = 1;
        if (kinetic.title() != null && !kinetic.title().isBlank()) {
            sections.append(titleShotHtml(index, kinetic.title()));
            index++;
        }
        for (KineticItem item : kinetic.items()) {
            sections.append(itemShotHtml(index, item));
            index++;
        }
        return sections.toString();
    }

    private String titleShotHtml(int index, String title) {
        return """
                <section class="shot" id="s%02d">
                  <div class="glow" style="width:1400px;height:1400px;left:-160px;top:200px"></div>
                  <div class="cx xl" id="s%02da" style="top:720px;font-size:140px;line-height:1.1">%s</div>
                </section>""".formatted(index, index, escapeHtml(title));
    }

    private String itemShotHtml(int index, KineticItem item) {
        String detail = item.detail() != null
                ? "  <div class=\"cx\" id=\"s%02dd\" style=\"top:1180px;font-size:54px;color:var(--brand-muted)\">%s</div>\n"
                        .formatted(index, escapeHtml(item.detail()))
                : "";
        return """
                <section class="shot" id="s%02d">
                  <div class="glow" style="width:1200px;height:1200px;left:-80px;top:240px"></div>
                  <div class="cx" id="s%02dl" style="top:420px;font-size:56px;color:var(--brand-muted)">%s</div>
                  <div class="cx xl" id="s%02dv" style="top:540px;font-size:170px;line-height:1.05">%s</div>
                %s</section>""".formatted(index, index, escapeHtml(item.label()), index, escapeHtml(item.value()), detail);
    }

    private String buildTimeline(KineticSpec kinetic, double duration) {
        int count = ((kinetic.title() != null && !kinetic.title().isBlank()) ? 1 : 0) + kinetic.items().size();
        double slot = duration / Math.max(1, count);
        StringBuilder lines = new StringBuilder();
        int index = 1;
        double start = 0.0;
        if (kinetic.title() != null && !kinetic.title().isBlank()) {
            double end = Math.min(duration, slot);
            lines.append("shot(\"s%02d\",%.3f,%.3f,\"whip\");\n".formatted(index, start, end));
            lines.append("rise(\"#s%02da\",%.3f,.5);\n".formatted(index, start + 0.05));
            lines.append("hit(%.3f,.6);\n".formatted(start + 0.35));
            start = end;
            index++;
        }
        for (KineticItem item : kinetic.items()) {
            double end = Math.min(duration, start + slot);
            lines.append("shot(\"s%02d\",%.3f,%.3f,\"whip\");\n".formatted(index, start, end));
            lines.append("rise(\"#s%02dl\",%.3f,.35);\n".formatted(index, start + 0.05));
            lines.append("slam(\"#s%02dv\",%.3f,1.0);\n".formatted(index, start + 0.3));
            if (item.detail() != null) {
                lines.append("rise(\"#s%02dd\",%.3f,.35);\n".formatted(index, start + 0.8));
            }
            start = end;
            index++;
        }
        return lines.toString();
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void deleteRecursively(Path root) {
        if (root == null) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    log.warn("Could not delete temporary file {}", path, exception);
                }
            });
        } catch (IOException exception) {
            log.warn("Could not clean up temporary directory {}", root, exception);
        }
    }
}
