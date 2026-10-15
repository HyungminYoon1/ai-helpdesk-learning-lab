"""Standard-library tests of wiring/policy; fake NER outputs are not model accuracy."""

from types import SimpleNamespace
import unittest

from probe import apply_mask, scan


def model_with(spans):
    return lambda text: SimpleNamespace(ents=[SimpleNamespace(label_=kind, start_char=start, end_char=end)
                                            for start, end, kind in spans] if text != "문의" else [])


class ProbePolicyTest(unittest.TestCase):
    def test_person_mapping_preserves_business_facts(self):
        body = "홍길동입니다. 직원 20명이 오늘 18시까지 로그인해야 합니다."
        result = scan(model_with([(0, 3, "PS")]), "문의", body)
        self.assertEqual(result["spans"], [{"field": "body", "start": 0, "end": 3, "type": "PERSON"}])
        self.assertEqual(apply_mask("문의", body, result)["body"],
                         "[PERSON_REDACTED]입니다. 직원 20명이 오늘 18시까지 로그인해야 합니다.")

    def test_rules_detect_only_the_declared_email_and_phone_formats(self):
        body = "mail@example.invalid / 010-0000-0000 / 오늘 18시 20명"
        result = scan(model_with([]), "문의", body)
        self.assertEqual([span["type"] for span in result["spans"]], ["EMAIL", "PHONE"])
        self.assertIn("오늘 18시 20명", apply_mask("문의", body, result)["body"])

    def test_ner_quantities_dates_and_times_are_not_blanket_redacted(self):
        result = scan(model_with([(0, 2, "QT"), (3, 5, "DT"), (6, 9, "TI")]), "문의", "20명 오늘 18시")
        self.assertEqual(result["spans"], [])

    def test_places_and_organizations_are_not_relabelled_as_person(self):
        self.assertEqual(scan(model_with([(0, 2, "LC"), (3, 5, "OG")]), "문의", "서울 회사")["spans"], [])

    def test_emoji_positions_use_code_points(self):
        body = "😀 홍길동 님"
        result = scan(model_with([(2, 5, "PS")]), "문의", body)
        self.assertEqual(apply_mask("문의", body, result)["body"], "😀 [PERSON_REDACTED] 님")

    def test_exact_duplicate_detections_are_applied_once(self):
        result = scan(model_with([(0, 3, "PS"), (0, 3, "PS")]), "문의", "홍길동 님")
        self.assertEqual(len(result["spans"]), 1)

    def test_overlap_between_a_rule_and_model_is_rejected_not_salvaged(self):
        with self.assertRaisesRegex(ValueError, "^PRIVACY_SCAN_INVALID_RESULT$"):
            scan(model_with([(0, 4, "PS")]), "문의", "mail@example.invalid")

    def test_incomplete_empty_result_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "^PRIVACY_SCAN_INVALID_RESULT$"):
            apply_mask("문의", "본문", {"completed": False, "spans": []})

    def test_different_types_on_the_same_range_are_rejected(self):
        spans = [{"field": "body", "start": 0, "end": 3, "type": kind} for kind in ("PERSON", "EMAIL")]
        with self.assertRaisesRegex(ValueError, "^PRIVACY_SCAN_INVALID_RESULT$"):
            apply_mask("문의", "ABC", {"completed": True, "spans": spans})

    def test_index_strings_are_not_silently_converted_to_integers(self):
        spans = [{"field": "body", "start": "0", "end": 3, "type": "PERSON"}]
        with self.assertRaisesRegex(ValueError, "^PRIVACY_SCAN_INVALID_RESULT$"):
            apply_mask("문의", "ABC", {"completed": True, "spans": spans})


if __name__ == "__main__":
    unittest.main()
