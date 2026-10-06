#!/usr/bin/env python3
"""Render clip variants straight from storage artifacts, without running the app.

Reproduces ClipRenderer/SubtitleWriter/FfmpegClient.renderVertical in Python so
render experiments (docs/EXPERIMENTS.md) can be judged in seconds instead of a
pipeline run. The `control` variant is a faithful port of the current Java
output -- if it does not match storage/<run>/clips/clip-N.mp4, the port is stale
and every comparison below is worthless.

    scripts/render-variant.py --list
    scripts/render-variant.py control pop designed
    scripts/render-variant.py --clip 1,4 karaoke maximal
    scripts/render-variant.py --set subtitle_size=96 --set vignette=true control
"""

import argparse
import json
import subprocess
import sys
import textwrap
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
STORAGE = REPO / "storage"
SCRATCH = STORAGE / "_scratch"

WHITE = "&H00FFFFFF"
ACCENT = "&H006A19F0"
ACCENT_HEX = "0xF0196A"
ACCENT_LIGHT = "&H009D6BFF"

DEFAULTS = {
    "width": 1080,
    "height": 1920,
    "zoom": 1.0,
    "crf": 23,
    "preset": "veryfast",
    "fps": None,

    "words_per_caption": 3,
    "subtitle_font": "Arial",
    "subtitle_size": 78,
    "subtitle_margin_bottom": 200,
    "uppercase": False,
    "caption_style": "static",
    "caption_anim": "none",
    "caption_glow": False,
    "caption_outline": 4,

    "loudnorm": False,
    "sharpen": False,
    "vignette": False,
    "darken_background": False,
    "panel_border": 0,
    "panel_shadow": False,
    "progress_bar": 0,
    "handle": False,
    "title_card": False,
    "intro_fade": 0.0,
    "outro_fade": 0.0,
    "push_in": False,
}

VARIANTS = {
    "control": {},

    "caption-2word": {"words_per_caption": 2},
    "caption-4word": {"words_per_caption": 4},
    "caption-big": {"subtitle_size": 96},
    "caption-upper": {"uppercase": True},
    "caption-underlay": {"subtitle_margin_bottom": 380},

    "karaoke": {"caption_style": "karaoke"},
    "pop": {"caption_style": "pop"},
    "anim-fade": {"caption_anim": "fade"},
    "anim-popin": {"caption_anim": "popin"},
    "anim-rise": {"caption_anim": "rise"},
    "glow": {"caption_glow": True},

    "loudnorm": {"loudnorm": True},
    "fps30": {"fps": 30},
    "sharpen": {"sharpen": True},
    "vignette": {"vignette": True, "darken_background": True},
    "panel": {"panel_border": 6, "panel_shadow": True},
    "progress": {"progress_bar": 10},
    "handle": {"handle": True},
    "title": {"title_card": True},
    "push": {"push_in": True},

    "designed": {
        "caption_style": "pop",
        "caption_anim": "popin",
        "caption_glow": True,
        "subtitle_font": "Arial Black",
        "subtitle_size": 84,
        "words_per_caption": 4,
        "subtitle_margin_bottom": 300,
        "uppercase": True,
        "loudnorm": True,
        "sharpen": True,
        "darken_background": True,
        "vignette": True,
        "panel_border": 5,
        "panel_shadow": True,
        "progress_bar": 10,
        "handle": True,
        "title_card": True,
        "intro_fade": 0.3,
        "outro_fade": 0.4,
        "fps": 30,
    },
    "maximal": {
        "caption_style": "pop",
        "caption_anim": "rise",
        "caption_glow": True,
        "subtitle_font": "Arial Black",
        "subtitle_size": 88,
        "words_per_caption": 3,
        "subtitle_margin_bottom": 300,
        "uppercase": True,
        "loudnorm": True,
        "sharpen": True,
        "darken_background": True,
        "vignette": True,
        "panel_border": 6,
        "panel_shadow": True,
        "progress_bar": 14,
        "handle": True,
        "title_card": True,
        "intro_fade": 0.35,
        "outro_fade": 0.5,
        "push_in": True,
        "fps": 30,
    },
}


def timestamp(seconds):
    total = int(seconds)
    centis = min(99, round((seconds - total) * 100))
    return "%d:%02d:%02d.%02d" % (total // 3600, (total % 3600) // 60, total % 60, centis)


def read_words(run):
    transcript = json.loads((run / "transcript.json").read_text())
    words = []
    for segment in transcript.get("segments", []):
        for word in segment.get("words", []):
            words.append((word["word"], word["start"], word["end"]))
    if not words:
        sys.exit("transcript has no word-level timestamps: %s" % (run / "transcript.json"))
    return words


def read_title(run, number):
    path = run / "content.json"
    if not path.is_file():
        return None
    for entry in json.loads(path.read_text()):
        if entry.get("clipIndex") != number:
            continue
        for platform in entry.get("platforms", []):
            if platform.get("title"):
                return platform["title"]
        return entry.get("summary")
    return None


def group(words, per_caption):
    return [words[i:i + per_caption] for i in range(0, len(words), per_caption)]


def header(cfg, karaoke):
    outline = cfg["caption_outline"]
    outline_colour = "&H00000000"
    if karaoke:
        fields = ("Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, "
                  "BackColour, Bold, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        style = "Default,%s,%d,%s,%s,%s,&H80000000,-1,%d,2,2,60,60,%d,1" % (
            cfg["subtitle_font"], cfg["subtitle_size"], ACCENT, WHITE, outline_colour,
            outline, cfg["subtitle_margin_bottom"])
    else:
        fields = ("Name, Fontname, Fontsize, PrimaryColour, OutlineColour, BackColour, "
                  "Bold, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        style = "Default,%s,%d,%s,%s,&H80000000,-1,%d,2,2,60,60,%d,1" % (
            cfg["subtitle_font"], cfg["subtitle_size"], WHITE, outline_colour,
            outline, cfg["subtitle_margin_bottom"])
    return ("[Script Info]\n"
            "ScriptType: v4.00+\n"
            "PlayResX: %d\n"
            "PlayResY: %d\n\n"
            "[V4+ Styles]\n"
            "Format: %s\n"
            "Style: %s\n\n"
            "[Events]\n"
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
            ) % (cfg["width"], cfg["height"], fields, style)


def animation_prefix(cfg):
    anim = cfg["caption_anim"]
    glow = "\\shad4\\4c%s\\blur2" % ACCENT if cfg["caption_glow"] else ""
    if anim == "none":
        return glow
    if anim == "fade":
        return "\\fad(90,90)" + glow
    if anim == "popin":
        return "\\fad(60,60)\\t(0,110,\\fscx112\\fscy112)\\t(110,200,\\fscx100\\fscy100)" + glow
    if anim == "rise":
        x = cfg["width"] // 2
        y = cfg["height"] - cfg["subtitle_margin_bottom"]
        return "\\an2\\move(%d,%d,%d,%d,0,160)\\fad(90,60)%s" % (x, y + 46, x, y, glow)
    sys.exit("unknown caption_anim: %s" % anim)


def wrap(prefix, body):
    return "{%s}%s" % (prefix, body) if prefix else body


def cased(text, cfg):
    return text.upper() if cfg["uppercase"] else text


def write_ass(words, clip_start, clip_end, cfg, target):
    in_clip = [w for w in words if w[2] > clip_start and w[1] < clip_end]
    span = clip_end - clip_start
    style = cfg["caption_style"]
    prefix = animation_prefix(cfg)
    ass = header(cfg, style == "karaoke")

    for caption in group(in_clip, cfg["words_per_caption"]):
        start = max(0.0, caption[0][1] - clip_start)
        end = min(span, caption[-1][2] - clip_start)
        if end <= start:
            continue

        if style == "static":
            body = cased("".join(w[0] for w in caption).strip(), cfg)
            ass += "Dialogue: 0,%s,%s,Default,,0,0,0,,%s\n" % (
                timestamp(start), timestamp(end), wrap(prefix, body).replace("\n", " "))

        elif style == "karaoke":
            body = ""
            for text, word_start, word_end in caption:
                centis = max(1, round((word_end - word_start) * 100))
                body += "{\\kf%d}%s " % (centis, cased(text, cfg).strip())
            ass += "Dialogue: 0,%s,%s,Default,,0,0,0,,%s\n" % (
                timestamp(start), timestamp(end), wrap(prefix, body.strip()))

        elif style == "pop":
            for index, (_, word_start, word_end) in enumerate(caption):
                shown_start = max(0.0, word_start - clip_start)
                shown_end = min(span, word_end - clip_start)
                if shown_end <= shown_start:
                    continue
                body = ""
                for position, (text, _, _) in enumerate(caption):
                    token = cased(text, cfg).strip()
                    if position == index:
                        body += "{\\c%s\\fscx112\\fscy112}%s{\\c%s\\fscx100\\fscy100} " % (
                            ACCENT, token, WHITE)
                    else:
                        body += token + " "
                ass += "Dialogue: 0,%s,%s,Default,,0,0,0,,%s\n" % (
                    timestamp(shown_start), timestamp(shown_end), wrap(prefix, body.strip()))
        else:
            sys.exit("unknown caption_style: %s" % style)

    target.write_text(ass)


def escape_path(path):
    return str(path).replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")


def build_graph(cfg, ass_path, duration, title_path):
    width, height = cfg["width"], cfg["height"]
    blur_w = max(2, width // 8)
    blur_h = max(2, height // 8)
    zoomed = int(round(width * cfg["zoom"] / 2)) * 2
    gradient_h = height // 6
    gradient_top = height - gradient_h
    border = cfg["panel_border"]
    inner_scale = zoomed - 2 * border
    inner_crop = width - 2 * border
    panel_h = int(round(inner_crop * 9 / 16 / 2)) * 2 + 2 * border
    panel_top = (height - panel_h) // 2

    background = ("scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,boxblur=8:2,scale=%d:%d"
                  % (blur_w, blur_h, blur_w, blur_h, width, height))
    if cfg["darken_background"]:
        background += ",eq=brightness=-0.16:saturation=0.55"

    foreground = "scale=%d:-2,crop=%d:ih:(iw-%d)/2:0" % (inner_scale, inner_crop, inner_crop)
    if cfg["sharpen"]:
        foreground += ",unsharp=5:5:0.7:5:5:0.0"
    if cfg["push_in"]:
        fps = cfg["fps"] or 60
        foreground += (",zoompan=z='min(1+0.045*on/%d,1.045)':d=1:x='iw/2-(iw/zoom/2)'"
                       ":y='ih/2-(ih/zoom/2)':s=%dx%d:fps=%d"
                       % (max(1, int(duration * fps)), inner_crop, panel_h - 2 * border, fps))
    if border:
        foreground += ",pad=iw+%d:ih+%d:%d:%d:%s" % (border * 2, border * 2, border, border,
                                                     ACCENT_HEX)

    graph = "[0:v]split=2[bg][fg];[bg]%s[b];[fg]%s[f];" % (background, foreground)

    if cfg["panel_shadow"]:
        shadow_top = max(0, panel_top - 10)
        graph += ("[b]drawbox=x=0:y=%d:w=%d:h=%d:color=black@0.55:t=fill,gblur=sigma=14[bs];"
                  % (shadow_top, width, panel_h + 20))
        graph += "[bs][f]overlay=(W-w)/2:(H-h)/2[comp];"
    else:
        graph += "[b][f]overlay=(W-w)/2:(H-h)/2[comp];"

    graph += ("color=black:s=%dx%d,format=rgba,geq=r=0:g=0:b=0:a='220*Y/H'[grad];"
              "[comp][grad]overlay=0:%d:shortest=1" % (width, gradient_h, gradient_top))

    chain = []
    if cfg["vignette"]:
        chain.append("vignette=PI/5")
    if cfg["title_card"] and title_path:
        chain.append("drawtext=textfile=%s:fontcolor=white:fontsize=54:font=Arial Black"
                     ":line_spacing=14:x=(w-tw)/2:y=%d:box=0"
                     % (escape_path(title_path), max(60, panel_top // 2 - 90)))
        chain.append("drawbox=x=(iw-160)/2:y=%d:w=160:h=6:color=%s@0.95:t=fill"
                     % (max(60, panel_top // 2 - 90) - 34, ACCENT_HEX))
    chain.append("subtitles=%s" % escape_path(ass_path))
    if cfg["handle"]:
        chain.append("drawtext=text='@your_javaguy':fontcolor=white@0.72:fontsize=34"
                     ":font=Arial:x=(w-tw)/2:y=h-%d" % (cfg["progress_bar"] + 62))
    if cfg["progress_bar"]:
        bar = cfg["progress_bar"]
        chain.append("drawbox=x=0:y=ih-%d:w=iw:h=%d:color=white@0.18:t=fill" % (bar, bar))
        chain.append("drawbox=x=0:y=ih-%d:w='iw*t/%s':h=%d:color=%s@0.95:t=fill"
                     % (bar, duration, bar, ACCENT_HEX))
    if cfg["intro_fade"]:
        chain.append("fade=t=in:st=0:d=%s" % cfg["intro_fade"])
    if cfg["outro_fade"]:
        chain.append("fade=t=out:st=%s:d=%s"
                     % (round(duration - cfg["outro_fade"], 3), cfg["outro_fade"]))

    graph += "," + ",".join(chain)

    if not cfg["loudnorm"]:
        return graph, []
    graph += "[v];[0:a]loudnorm=I=-14:TP=-1.5:LRA=11[a]"
    return graph, ["-map", "[v]", "-map", "[a]"]


def render(source, start, duration, cfg, ass_path, title_path, target):
    graph, mapping = build_graph(cfg, ass_path, duration, title_path)
    command = ["ffmpeg", "-hide_banner", "-nostats", "-y",
               "-ss", str(start), "-i", str(source), "-t", str(duration),
               "-filter_complex", graph] + mapping + [
        "-c:v", "libx264", "-preset", cfg["preset"], "-crf", str(cfg["crf"]),
        "-c:a", "aac", "-movflags", "+faststart"]
    if cfg["fps"]:
        command += ["-r", str(cfg["fps"])]
    command.append(str(target))

    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        print(result.stderr[-2500:], file=sys.stderr)
        sys.exit("ffmpeg failed for %s" % target)


def probe(target):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height,r_frame_rate",
         "-show_entries", "format=duration", "-of", "json", str(target)],
        capture_output=True, text=True).stdout
    data = json.loads(out)
    stream = data["streams"][0]
    num, den = stream["r_frame_rate"].split("/")
    return {
        "size": "%dx%d" % (stream["width"], stream["height"]),
        "fps": round(int(num) / int(den), 2),
        "duration": round(float(data["format"]["duration"]), 2),
        "bytes": target.stat().st_size,
    }


def loudness(target):
    result = subprocess.run(
        ["ffmpeg", "-hide_banner", "-nostats", "-i", str(target),
         "-af", "ebur128=framelog=quiet", "-f", "null", "-"],
        capture_output=True, text=True)
    for line in result.stderr.splitlines():
        stripped = line.strip()
        if stripped.startswith("I:") and "LUFS" in stripped:
            return stripped.split()[1]
    return "?"


def pick_run(requested):
    if requested:
        run = STORAGE / requested
        if not run.is_dir():
            sys.exit("no such run: %s" % run)
        return run
    for run in sorted(STORAGE.iterdir()):
        manifest = run / "clips.json"
        if manifest.is_file() and json.loads(manifest.read_text()):
            return run
    sys.exit("no run in storage/ has a non-empty clips.json -- pass --run explicitly")


def coerce(value):
    for cast in (int, float):
        try:
            return cast(value)
        except ValueError:
            pass
    lowered = value.lower()
    if lowered in ("true", "false"):
        return lowered == "true"
    if lowered in ("none", "null"):
        return None
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("variants", nargs="*", default=["control"])
    parser.add_argument("--run", help="video id under storage/ (default: first with clips)")
    parser.add_argument("--clip", default="1", help="clip number(s) from clips.json, e.g. 1,4")
    parser.add_argument("--out", default=str(SCRATCH), help="output root (default: storage/_scratch)")
    parser.add_argument("--set", action="append", default=[], metavar="KEY=VALUE",
                        help="override a config key for every variant in this run")
    parser.add_argument("--list", action="store_true", help="list variants and exit")
    args = parser.parse_args()

    if args.list:
        for name, overrides in VARIANTS.items():
            print("%-18s %s" % (name, overrides or "(current Java output)"))
        print("\nkeys: %s" % ", ".join(DEFAULTS))
        return

    variants = args.variants or ["control"]
    unknown = [v for v in variants if v not in VARIANTS]
    if unknown:
        sys.exit("unknown variant(s): %s (try --list)" % ", ".join(unknown))

    ad_hoc = {}
    for override in args.set:
        key, _, value = override.partition("=")
        if key not in DEFAULTS:
            sys.exit("unknown key: %s (try --list)" % key)
        ad_hoc[key] = coerce(value)

    run = pick_run(args.run)
    source = run / "source.mp4"
    clips = {clip["index"]: clip for clip in json.loads((run / "clips.json").read_text())}
    words = read_words(run)
    numbers = [int(n) for n in args.clip.split(",")]

    missing = [n for n in numbers if n not in clips]
    if missing:
        sys.exit("clips.json has no clip %s (available: %s)" % (missing, sorted(clips)))

    print("run    %s" % run.name)
    print("source %s" % source)
    print("clips  %s\n" % numbers)

    rows = []
    for name in variants:
        cfg = dict(DEFAULTS)
        cfg.update(VARIANTS[name])
        cfg.update(ad_hoc)
        out_dir = Path(args.out) / name
        out_dir.mkdir(parents=True, exist_ok=True)

        for number in numbers:
            clip = clips[number]
            start, end = clip["start"], clip["end"]
            ass_path = out_dir / ("clip-%d.ass" % number)
            target = out_dir / ("clip-%d.mp4" % number)

            title_path = None
            if cfg["title_card"]:
                title = read_title(run, number)
                if title:
                    title_path = out_dir / ("clip-%d.txt" % number)
                    title_path.write_text("\n".join(textwrap.wrap(title, 26)[:3]))

            write_ass(words, start, end, cfg, ass_path)
            print("rendering %s/clip-%d.mp4 ..." % (name, number), flush=True)
            render(source, start, end - start, cfg, ass_path, title_path, target)

            info = probe(target)
            rows.append((name, number, info["size"], info["fps"], info["duration"],
                         info["bytes"] / 1_000_000, loudness(target)))

    print("\n%-18s %4s %-10s %6s %8s %9s %10s" %
          ("variant", "clip", "size", "fps", "seconds", "MB", "LUFS"))
    for name, number, size, fps, duration, megabytes, lufs in rows:
        print("%-18s %4d %-10s %6s %8s %9.2f %10s" %
              (name, number, size, fps, duration, megabytes, lufs))
    print("\noutput: %s" % Path(args.out).resolve())
    print("judge on a phone, not this monitor.")


if __name__ == "__main__":
    main()
