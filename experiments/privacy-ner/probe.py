"""Synthetic-only NER experiment, not a network service or production scanner."""

import importlib.metadata
import json
from pathlib import Path
import re
import statistics
import sys
import time


MODEL = "ko_core_news_sm"
MODEL_VERSION = "3.8.0"
EMAIL = re.compile(r"(?<![\w@])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,63}(?![\w@])")
PHONE = re.compile(r"(?<![0-9])01[016789][ -]?[0-9]{3,4}[ -]?[0-9]{4}(?![0-9])")
TYPES = {"PERSON", "EMAIL", "PHONE"}
NER_LABELS = {"DT", "LC", "OG", "PS", "QT", "TI"}


def scan(nlp, title, body):
    """Only input fields reach the model; expected answers never enter this call."""
    found = set()
    for field, original in (("title", title), ("body", body)):
        for pattern, kind in ((EMAIL, "EMAIL"), (PHONE, "PHONE")):
            for match in pattern.finditer(original):
                found.add((field, match.start(), match.end(), kind))
        for entity in nlp(original).ents:
            if entity.label_ not in NER_LABELS:
                raise ValueError("PRIVACY_SCAN_INVALID_RESULT")
            # NER's other labels are not blanket PII categories. In particular,
            # preserve impact quantities and business deadline dates/times.
            if entity.label_ == "PS":
                found.add((field, entity.start_char, entity.end_char, "PERSON"))
    spans = [{"field": f, "start": start, "end": end, "type": kind}
             for f, start, end, kind in sorted(found)]
    result = {"completed": True, "spans": spans}
    apply_mask(title, body, result)  # No result is returned if any range conflicts.
    return result


def apply_mask(title, body, result):
    if (set(result) != {"completed", "spans"} or result["completed"] is not True
            or not isinstance(result["spans"], list)):
        raise ValueError("PRIVACY_SCAN_INVALID_RESULT")
    originals = {"title": title, "body": body}
    groups = {"title": set(), "body": set()}
    for span in result["spans"]:
        if (set(span) != {"field", "start", "end", "type"}
                or span["field"] not in originals or span["type"] not in TYPES
                or type(span["start"]) is not int or type(span["end"]) is not int
                or not 0 <= span["start"] < span["end"] <= len(originals[span["field"]])):
            raise ValueError("PRIVACY_SCAN_INVALID_RESULT")
        groups[span["field"]].add((span["start"], span["end"], span["type"]))
    masked = {}
    for field, spans in groups.items():
        end = 0
        pieces = []
        for start, stop, kind in sorted(spans):
            if start < end:
                raise ValueError("PRIVACY_SCAN_INVALID_RESULT")
            pieces.extend((originals[field][end:start], f"[{kind}_REDACTED]"))
            end = stop
        masked[field] = "".join(pieces) + originals[field][end:]
    return masked


def expected_ranges(case):
    gold = set()
    for entity in case["expected"]:
        original = case[entity["field"]]
        text = entity["text"]
        if original.count(text) != 1:
            raise ValueError("SYNTHETIC_DATASET_INVALID")
        start = original.index(text)
        gold.add((entity["field"], start, start + len(text), entity["type"]))
    return gold


def main():
    import resource  # Linux-only measurement; policy Unit Tests also run on Windows.
    if len(sys.argv) != 1:
        raise ValueError("SYNTHETIC_PROBE_TAKES_NO_INPUT")
    cases = json.loads(Path(__file__).with_name("synthetic-cases.json").read_text(encoding="utf-8"))
    started = time.perf_counter()
    import spacy
    spacy.require_cpu()
    imported = time.perf_counter()
    nlp = spacy.load(MODEL, exclude=["tok2vec", "tagger", "morphologizer", "parser",
                                   "lemmatizer", "attribute_ruler", "senter"])
    loaded = time.perf_counter()
    if nlp.meta["version"] != MODEL_VERSION or nlp.pipe_names != ["ner"]:
        raise ValueError("NER_MODEL_CONFIGURATION_INVALID")
    rows = []
    latencies = []
    correct = missed = extra = 0
    blocked = 0
    case_loop_cpu = time.process_time()
    for case in cases:
        before = time.perf_counter()
        try:
            result = scan(nlp, case["title"], case["body"])
        except ValueError:
            latencies.append((time.perf_counter() - before) * 1000)
            blocked += 1
            rows.append({"caseId": case["id"], "outcome": "INVALID_SCAN_RESULT", "pass": False})
            continue
        latencies.append((time.perf_counter() - before) * 1000)
        actual = {(s["field"], s["start"], s["end"], s["type"]) for s in result["spans"]}
        expected = expected_ranges(case)  # Evaluation only, AFTER model execution.
        masked = apply_mask(case["title"], case["body"], result)
        preserved = all(text in masked["body"] for text in case["preserve"])
        tp, fn, fp = len(actual & expected), len(expected - actual), len(actual - expected)
        correct += tp
        missed += fn
        extra += fp
        rows.append({"caseId": case["id"], "expected": len(expected), "detected": len(actual),
                     "exactMatches": tp, "missed": fn, "extra": fp,
                     "businessFactsPreserved": preserved,
                     "pass": actual == expected and preserved,
                     "detectedRanges": result["spans"]})
    # Includes rule/model execution, validation, masking and gold evaluation.
    case_loop_cpu_ms = (time.process_time() - case_loop_cpu) * 1000
    report = {
        "evidence": "LOCAL_CPU_KOREAN_NER_SYNTHETIC_PROBE",
        "model": MODEL, "modelVersion": MODEL_VERSION, "modelLicense": nlp.meta.get("license"),
        "pipeline": nlp.pipe_names,
        "dependencies": {d.metadata["Name"]: d.version for d in importlib.metadata.distributions()},
        "importMs": round((imported - started) * 1000, 2),
        "modelLoadMs": round((loaded - imported) * 1000, 2),
        "scanMedianMs": round(statistics.median(latencies), 2),
        "scanMaxMs": round(max(latencies), 2), "caseLoopCpuMs": round(case_loop_cpu_ms, 2),
        "processPeakRssMiB": round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024, 2),
        "caseCount": len(rows), "passed": sum(row["pass"] for row in rows),
        "blockedCases": blocked, "spanMetricScope": "COMPLETED_SCANS_ONLY",
        "exactSpanTruePositives": correct, "exactSpanFalseNegatives": missed,
        "exactSpanFalsePositives": extra, "cases": rows,
        "productionScanner": False, "browserE2e": False, "cloudValidation": False
    }
    peak = Path("/sys/fs/cgroup/memory.peak")
    if peak.exists():
        report["containerPeakMemoryMiB"] = round(int(peak.read_text(encoding="ascii")) / (1024 * 1024), 2)
    print(json.dumps(report, ensure_ascii=True, separators=(",", ":")))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # No input, model-produced text, environment, parser message or traceback.
        print("NER_SYNTHETIC_PROBE_FAILED", file=sys.stderr)
        sys.exit(1)
