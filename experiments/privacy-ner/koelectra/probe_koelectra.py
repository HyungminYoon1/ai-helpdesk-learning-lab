"""Offline, fixed-synthetic comparison only. Not a deployable privacy scanner."""

from collections import Counter
import hashlib
import importlib.metadata
import json
from pathlib import Path
import statistics
import sys
import time

from probe import EMAIL, PHONE, apply_mask, expected_ranges


MODEL_DIR = Path("/opt/privacy-model")
DATASET_SHA256 = "c1c180fd9f110292e79226b88a810eccf85d8e13f1c58f4521da434ba84a7195"
MODEL_LABELS = {"PER", "LOC", "RRN", "EMA", "ID", "PWD", "ORG", "PHN", "CRD", "ACC", "PSP", "DLN"}
COMPARISON_TYPES = {"PER": "PERSON", "EMA": "EMAIL", "PHN": "PHONE"}


def decode_spans(text, offsets, labels):
    """The model uses PER-B/PER-I, not B-PER/I-PER. Never repair malformed BIO."""
    if len(offsets) != len(labels):
        raise ValueError("MODEL_ALIGNMENT_INVALID")
    spans = []
    current = None
    previous_end = 0
    for offset, label in zip(offsets, labels):
        if (len(offset) != 2 or any(type(value) is not int for value in offset)
                or not isinstance(label, str)):
            raise ValueError("MODEL_ALIGNMENT_INVALID")
        start, end = offset
        if (start, end) == (0, 0):
            if current is not None:
                spans.append(current)
                current = None
            continue  # CLS/SEP. A non-special empty token is rejected below.
        if not 0 <= start < end <= len(text) or start < previous_end:
            raise ValueError("MODEL_ALIGNMENT_INVALID")
        previous_end = end
        if label == "O":
            if current is not None:
                spans.append(current)
                current = None
            continue
        parts = label.rsplit("-", 1)
        if len(parts) != 2 or parts[0] not in MODEL_LABELS or parts[1] not in {"B", "I"}:
            raise ValueError("MODEL_LABEL_INVALID")
        kind, boundary = parts
        if boundary == "B":
            if current is not None:
                spans.append(current)
            current = (start, end, kind)
        elif current is None or current[2] != kind:
            raise ValueError("MODEL_BIO_INVALID")
        else:
            current = (current[0], end, kind)
    if current is not None:
        spans.append(current)
    return spans


def scan(predict, title, body):
    """Only originals reach predict; fixed expected answers stay in the evaluator."""
    found = set()
    model_counts = Counter()
    for field, original in (("title", title), ("body", body)):
        for pattern, kind in ((EMAIL, "EMAIL"), (PHONE, "PHONE")):
            for match in pattern.finditer(original):
                found.add((field, match.start(), match.end(), kind))
        for start, end, label in predict(original):
            if label not in MODEL_LABELS:
                raise ValueError("MODEL_LABEL_INVALID")
            model_counts[label] += 1
            # Scope matches the earlier 12 cases. Other model labels remain
            # visible in counts, NOT silently claimed as protected categories.
            if label in COMPARISON_TYPES:
                found.add((field, start, end, COMPARISON_TYPES[label]))
    result = {"completed": True, "spans": [
        {"field": f, "start": start, "end": end, "type": kind}
        for f, start, end, kind in sorted(found)]}
    apply_mask(title, body, result)  # Existing strict conflict policy, no merging.
    return result, dict(sorted(model_counts.items()))


def main():
    import resource
    if len(sys.argv) != 1:
        raise ValueError("SYNTHETIC_PROBE_TAKES_NO_INPUT")
    dataset_bytes = Path(__file__).with_name("synthetic-cases.json").read_bytes()
    if hashlib.sha256(dataset_bytes).hexdigest() != DATASET_SHA256:
        raise ValueError("SYNTHETIC_DATASET_CHANGED")
    cases = json.loads(dataset_bytes.decode("utf-8"))
    lock = json.loads(Path(__file__).with_name("model-lock.json").read_text(encoding="utf-8"))
    started = time.perf_counter()
    import torch
    from transformers import ElectraForTokenClassification, ElectraTokenizerFast
    imported = time.perf_counter()
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    torch.manual_seed(0)
    tokenizer = ElectraTokenizerFast.from_pretrained(
        MODEL_DIR, local_files_only=True, do_lower_case=False, strip_accents=False)
    model = ElectraForTokenClassification.from_pretrained(
        MODEL_DIR, local_files_only=True, use_safetensors=True).to("cpu").eval()
    if (model.config.model_type != "electra" or model.config.max_position_embeddings != 512
            or set(model.config.id2label.values()) != {"O"} | {
                f"{kind}-{boundary}" for kind in MODEL_LABELS for boundary in ("B", "I")}):
        raise ValueError("MODEL_CONFIGURATION_INVALID")
    loaded = time.perf_counter()

    def predict(text):
        batch = tokenizer(text, truncation=False, return_offsets_mapping=True,
                          return_special_tokens_mask=True, return_tensors="pt")
        offsets = batch.pop("offset_mapping")[0].tolist()
        special = batch.pop("special_tokens_mask")[0].tolist()
        if len(offsets) > 512:
            raise ValueError("MODEL_INPUT_TOO_LONG")
        if any((start, end) == (0, 0) and flag != 1
               for (start, end), flag in zip(offsets, special)):
            raise ValueError("MODEL_ALIGNMENT_INVALID")
        with torch.inference_mode():
            ids = model(**batch).logits.argmax(dim=-1)[0].tolist()
        return decode_spans(text, offsets, [model.config.id2label[index] for index in ids])

    rows, latencies = [], []
    correct = missed = extra = blocked = 0
    cpu_started = time.process_time()
    for case in cases:
        before = time.perf_counter()
        try:
            result, model_counts = scan(predict, case["title"], case["body"])
        except ValueError as error:
            # Only our own fixed markers, never a tokenizer/model exception body.
            marker = str(error)
            if marker not in {"PRIVACY_SCAN_INVALID_RESULT", "MODEL_INPUT_TOO_LONG",
                              "MODEL_ALIGNMENT_INVALID", "MODEL_LABEL_INVALID", "MODEL_BIO_INVALID"}:
                raise RuntimeError("NER_SYNTHETIC_PROBE_FAILED") from None
            rows.append({"caseId": case["id"], "outcome": marker, "pass": False})
            blocked += 1
        else:
            actual = {(s["field"], s["start"], s["end"], s["type"]) for s in result["spans"]}
            expected = expected_ranges(case)  # After inference; never passed to predict.
            masked = apply_mask(case["title"], case["body"], result)
            preserved = all(text in masked["body"] for text in case["preserve"])
            tp, fn, fp = len(actual & expected), len(expected - actual), len(actual - expected)
            correct += tp
            missed += fn
            extra += fp
            rows.append({"caseId": case["id"], "expected": len(expected), "detected": len(actual),
                         "exactMatches": tp, "missed": fn, "extra": fp,
                         "businessFactsPreserved": preserved, "pass": actual == expected and preserved,
                         "modelLabelCounts": model_counts, "detectedRanges": result["spans"]})
        latencies.append((time.perf_counter() - before) * 1000)
    report = {
        "evidence": "LOCAL_CPU_KOELECTRA_PRIVACY_SYNTHETIC_PROBE",
        "model": lock["repository"], "revision": lock["revision"],
        "declaredModelLicense": lock["declaredLicense"],
        "modelSha256": lock["files"]["model.safetensors"],
        "datasetSha256": DATASET_SHA256,
        "comparisonTypes": sorted(COMPARISON_TYPES.values()),
        "unmappedModelLabels": sorted(MODEL_LABELS - COMPARISON_TYPES.keys()),
        "dependencies": {d.metadata["Name"]: d.version for d in importlib.metadata.distributions()},
        "importMs": round((imported - started) * 1000, 2),
        "modelLoadMs": round((loaded - imported) * 1000, 2),
        "scanMedianMs": round(statistics.median(latencies), 2),
        "scanMaxMs": round(max(latencies), 2),
        "caseLoopCpuMs": round((time.process_time() - cpu_started) * 1000, 2),
        "processPeakRssMiB": round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024, 2),
        "caseCount": len(rows), "passed": sum(row["pass"] for row in rows),
        "blockedCases": blocked, "spanMetricScope": "COMPLETED_SCANS_ONLY",
        "exactSpanTruePositives": correct, "exactSpanFalseNegatives": missed,
        "exactSpanFalsePositives": extra, "cases": rows,
        "productionScanner": False, "browserE2e": False, "cloudValidation": False}
    peak = Path("/sys/fs/cgroup/memory.peak")
    if peak.exists():
        report["containerPeakMemoryMiB"] = round(int(peak.read_text(encoding="ascii")) / (1024 * 1024), 2)
    print(json.dumps(report, ensure_ascii=True, separators=(",", ":")))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("NER_SYNTHETIC_PROBE_FAILED", file=sys.stderr)
        sys.exit(1)
