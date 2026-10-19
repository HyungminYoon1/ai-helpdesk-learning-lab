"""Decoder/adapter tests with fixed outputs, not evidence of model accuracy."""

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from probe import apply_mask, expected_ranges
from probe_koelectra import DATASET_SHA256, decode_spans, scan


class KoElectraProbeTest(unittest.TestCase):
    def test_suffix_bio_is_decoded_without_a_pipeline_prefix_assumption(self):
        self.assertEqual(decode_spans("홍길동입니다", [(0, 0), (0, 1), (1, 3), (3, 6), (0, 0)],
                                     ["O", "PER-B", "PER-I", "O", "O"]), [(0, 3, "PER")])

    def test_emoji_offsets_are_code_points(self):
        result = decode_spans("😀 홍길동 님", [(0, 1), (2, 5), (6, 7)], ["O", "PER-B", "O"])
        self.assertEqual(result, [(2, 5, "PER")])

    def test_consecutive_begin_labels_remain_distinct(self):
        self.assertEqual(decode_spans("가나", [(0, 1), (1, 2)], ["PER-B", "PER-B"]),
                         [(0, 1, "PER"), (1, 2, "PER")])

    def test_inside_without_begin_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "^MODEL_BIO_INVALID$"):
            decode_spans("가", [(0, 1)], ["PER-I"])

    def test_inside_of_another_type_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "^MODEL_BIO_INVALID$"):
            decode_spans("가나", [(0, 1), (1, 2)], ["PER-B", "EMA-I"])

    def test_inside_after_outside_is_not_silently_repaired(self):
        with self.assertRaisesRegex(ValueError, "^MODEL_BIO_INVALID$"):
            decode_spans("가나다", [(0, 1), (1, 2), (2, 3)], ["PER-B", "O", "PER-I"])

    def test_unknown_and_wrong_direction_labels_are_rejected(self):
        for label in ("NEW-B", "B-PER", "PER", "PER-X"):
            with self.subTest(label=label), self.assertRaisesRegex(ValueError, "^MODEL_LABEL_INVALID$"):
                decode_spans("가", [(0, 1)], [label])

    def test_alignment_length_mismatch_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "^MODEL_ALIGNMENT_INVALID$"):
            decode_spans("가", [(0, 1)], [])

    def test_invalid_ranges_are_rejected(self):
        for offsets in ([(0, 2)], [(-1, 1)], [(1, 0)], [(False, 1)], [(0, 1, 1)]):
            with self.subTest(offsets=offsets), self.assertRaisesRegex(ValueError, "^MODEL_ALIGNMENT_INVALID$"):
                decode_spans("가", offsets, ["PER-B"])

    def test_overlapping_tokens_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "^MODEL_ALIGNMENT_INVALID$"):
            decode_spans("가나다", [(0, 2), (1, 3)], ["PER-B", "PER-I"])

    def test_empty_detections_preserve_business_facts(self):
        body = "사용자 20명이 오늘 18시까지 로그인해야 합니다."
        result, counts = scan(lambda text: [], "문의", body)
        self.assertEqual(result, {"completed": True, "spans": []})
        self.assertEqual(counts, {})
        self.assertEqual(apply_mask("문의", body, result)["body"], body)

    def test_no_case_id_or_expected_answer_reaches_prediction(self):
        received = []
        def predict(text):
            received.append(text)
            return []
        scan(predict, "문의", "본문")
        self.assertEqual(received, ["문의", "본문"])

    def test_person_mapping_does_not_rewrite_recovery_information(self):
        body = "홍길동입니다. 새 링크로 로그인에 성공했습니다."
        result, counts = scan(lambda text: [(0, 3, "PER")] if text == body else [], "문의", body)
        self.assertEqual(counts, {"PER": 1})
        self.assertEqual(apply_mask("문의", body, result)["body"],
                         "[PERSON_REDACTED]입니다. 새 링크로 로그인에 성공했습니다.")

    def test_other_labels_are_visible_but_not_blanket_redacted(self):
        result, counts = scan(lambda text: [(0, 2, "LOC")] if text == "서울 지사" else [], "문의", "서울 지사")
        self.assertEqual(counts, {"LOC": 1})
        self.assertEqual(result["spans"], [])

    def test_unknown_label_is_not_treated_as_no_pii(self):
        with self.assertRaisesRegex(ValueError, "^MODEL_LABEL_INVALID$"):
            scan(lambda text: [(0, 1, "NEW")], "문의", "본문")

    def test_exact_rule_model_duplicate_is_deduplicated(self):
        body = "a@example.invalid"
        result, _ = scan(lambda text: [(0, len(body), "EMA")] if text == body else [], "문의", body)
        self.assertEqual(len(result["spans"]), 1)

    def test_partial_model_rule_overlap_is_rejected_not_merged(self):
        body = "a@example.invalid"
        with self.assertRaisesRegex(ValueError, "^PRIVACY_SCAN_INVALID_RESULT$"):
            scan(lambda text: [(0, 3, "EMA")] if text == body else [], "문의", body)

    def test_frozen_dataset_stays_twelve_cases_with_valid_expected_ranges(self):
        import json
        dataset = Path(__file__).resolve().parent / "synthetic-cases.json"
        if not dataset.exists():
            dataset = dataset.parent.parent / "synthetic-cases.json"
        import hashlib
        raw = dataset.read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(), DATASET_SHA256)
        cases = json.loads(raw.decode("utf-8"))
        self.assertEqual([case["id"] for case in cases], [f"P{i:02}" for i in range(1, 13)])
        for case in cases:
            expected_ranges(case)


if __name__ == "__main__":
    unittest.main()
