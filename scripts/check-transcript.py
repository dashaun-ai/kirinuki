#!/usr/bin/env python3
import json
import sys
from glob import glob
from pathlib import Path

NO_SPEECH_LIMIT = 0.5
LOGPROB_LIMIT = -1.0
WEAK_FRACTION_LIMIT = 0.10


def load(path):
    return json.load(open(path))


def silence_report(segments):
    suspects = []
    for segment in segments:
        text = segment["text"].strip()
        no_speech = segment.get("no_speech_prob", 0)
        if no_speech > NO_SPEECH_LIMIT and len(text.split()) <= 4:
            suspects.append((segment["start"], segment["end"], no_speech, text))
    worst_no_speech = max((s.get("no_speech_prob", 0) for s in segments), default=0)
    worst_logprob = min((s.get("avg_logprob", 0) for s in segments), default=0)
    return suspects, worst_no_speech, worst_logprob


def window_confidence(segments, start, end):
    overlap = [s for s in segments if s["end"] > start and s["start"] < end]
    if not overlap:
        return None
    max_no_speech = max(s.get("no_speech_prob", 0) for s in overlap)
    min_logprob = min(s.get("avg_logprob", 0) for s in overlap)
    weak = sum(
        min(s["end"], end) - max(s["start"], start)
        for s in overlap
        if s.get("no_speech_prob", 0) > NO_SPEECH_LIMIT
    )
    return max_no_speech, min_logprob, weak / (end - start)


def candidate_report(segments, candidates):
    flagged = []
    for candidate in candidates:
        confidence = window_confidence(segments, candidate["start"], candidate["end"])
        if confidence is None:
            flagged.append((candidate["id"], candidate["start"], candidate["end"], "no overlapping segments"))
            continue
        max_no_speech, min_logprob, weak_fraction = confidence
        if weak_fraction > WEAK_FRACTION_LIMIT or max_no_speech > 0.6 or min_logprob < LOGPROB_LIMIT:
            reason = f"nsp_max={max_no_speech:.2f} logprob_min={min_logprob:.2f} weak={weak_fraction:.0%}"
            flagged.append((candidate["id"], candidate["start"], candidate["end"], reason))
    return flagged


def check(video_dir):
    transcript_path = video_dir / "transcript.json"
    if not transcript_path.exists():
        print(f"{video_dir.name}: no transcript.json, skipped")
        return True

    segments = load(transcript_path)["segments"]
    duration = segments[-1]["end"] / 60 if segments else 0
    print(f"\n=== {video_dir.name} ===  segments={len(segments)}  duration={duration:.1f}min")

    suspects, worst_no_speech, worst_logprob = silence_report(segments)
    print(f"  extremes: worst no_speech_prob={worst_no_speech:.2f}, worst avg_logprob={worst_logprob:.2f}")
    print(f"  silence-hallucination suspects: {len(suspects)}")
    for start, end, no_speech, text in suspects[:15]:
        print(f"    [{start:7.1f}-{end:7.1f}] nsp={no_speech:.2f}  {text!r}")

    passed = len(suspects) == 0

    candidates_path = video_dir / "candidates.json"
    if candidates_path.exists():
        flagged = candidate_report(segments, load(candidates_path))
        total = len(load(candidates_path))
        print(f"  candidates over weak audio: {len(flagged)} / {total}")
        for cid, start, end, reason in flagged[:15]:
            print(f"    cand#{cid} [{start:7.1f}-{end:7.1f}]  {reason}")
        passed = passed and len(flagged) == 0

    print(f"  verdict: {'PASS' if passed else 'FLAG'}")
    return passed


def main(args):
    if args:
        targets = [Path(a) for a in args]
    else:
        targets = sorted(Path(p).parent for p in glob("storage/*/transcript.json"))

    if not targets:
        print("no transcripts found under storage/")
        return 1

    results = [check(t) for t in targets]
    print(f"\n{'all clean' if all(results) else 'review the FLAG videos above'}")
    return 0 if all(results) else 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
