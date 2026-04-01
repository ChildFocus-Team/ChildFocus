"""
ChildFocus — Test Suite: Heuristic Module
backend/tests/test_heuristic.py

Tests compute_heuristic_score() and get_feature_weights() from heuristic.py.

FIX: compute_heuristic_score() was refactored to accept a pre-sampled dict
     (not a video_id string + thumbnail_url). Tests updated to match the
     current function signature. Mock patch target updated from
     'app.modules.heuristic.sample_video' (not exposed) to passing mock
     dicts directly into compute_heuristic_score().

Run: py -m pytest tests/test_heuristic.py -v
"""

import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.modules.heuristic import compute_heuristic_score, get_feature_weights


class TestGetFeatureWeights:

    def test_returns_dict(self):
        """get_feature_weights must return a dict."""
        weights = get_feature_weights()
        assert isinstance(weights, dict)

    def test_required_weight_keys(self):
        """Must contain all four feature weight keys."""
        weights = get_feature_weights()
        required = ["w_fcr", "w_csv", "w_att", "w_thumb"]
        for key in required:
            assert key in weights, f"Missing weight key: {key}"

    def test_weights_are_positive(self):
        """All weights must be positive floats."""
        weights = get_feature_weights()
        for key in ["w_fcr", "w_csv", "w_att", "w_thumb"]:
            assert weights[key] > 0, f"{key} must be > 0"

    def test_weights_sum_to_one(self):
        """Feature weights must sum to 1.0 per thesis formula."""
        weights = get_feature_weights()
        total = weights["w_fcr"] + weights["w_csv"] + weights["w_att"] + weights["w_thumb"]
        assert abs(total - 1.0) < 0.01, (
            f"Feature weights sum to {total}, expected 1.0. "
            f"Check heuristic.py constants."
        )

    def test_thresholds_present(self):
        """Threshold keys must be present."""
        weights = get_feature_weights()
        assert "threshold_high" in weights
        assert "threshold_low"  in weights

    def test_threshold_ordering(self):
        """threshold_high must be greater than threshold_low."""
        weights = get_feature_weights()
        assert weights["threshold_high"] > weights["threshold_low"]


class TestComputeHeuristicScore:
    """
    compute_heuristic_score() accepts a pre-sampled dict from sample_video().
    Tests pass mock dicts directly — no video download, no network calls.
    """

    def test_unavailable_video_returns_thumbnail_only_score(self):
        """
        An unavailable sample dict (no segments) should still return a dict
        with a valid score_h computed from thumbnail alone.
        """
        mock_unavailable = {
            "status": "unavailable",
            "reason": "Video is private",
            "segments": [],
            "thumbnail_intensity": 0.0,
        }
        result = compute_heuristic_score(mock_unavailable)
        assert isinstance(result, dict)
        assert "score_h" in result
        assert 0.0 <= result["score_h"] <= 1.0

    def test_returns_dict_always(self):
        """Must always return a dict, never raise an exception."""
        try:
            mock_empty = {
                "status": "unavailable",
                "segments": [],
                "thumbnail_intensity": 0.0,
            }
            result = compute_heuristic_score(mock_empty)
            assert isinstance(result, dict)
        except Exception as e:
            assert False, f"compute_heuristic_score raised an exception: {e}"

    def test_mocked_success_response_structure(self):
        """
        Pass a known mock sample dict and verify compute_heuristic_score
        correctly maps it to score_h and details.
        """
        mock_sample = {
            "status": "success",
            "video_id": "test123",
            "video_duration_sec": 60.0,
            "thumbnail_intensity": 0.45,
            "segments": [
                {
                    "segment_id": "S1",
                    "offset_seconds": 0,
                    "length_seconds": 20,
                    "fcr": 0.30,
                    "csv": 0.25,
                    "att": 0.40,
                    "score_h": 0.315,
                },
                {
                    "segment_id": "S2",
                    "offset_seconds": 20,
                    "length_seconds": 20,
                    "fcr": 0.50,
                    "csv": 0.40,
                    "att": 0.60,
                    "score_h": 0.49,
                },
                {
                    "segment_id": "S3",
                    "offset_seconds": 40,
                    "length_seconds": 20,
                    "fcr": 0.20,
                    "csv": 0.15,
                    "att": 0.30,
                    "score_h": 0.215,
                },
            ],
            "aggregate_heuristic_score": 0.49,
        }

        result = compute_heuristic_score(mock_sample)

        assert isinstance(result, dict)
        assert "score_h"  in result
        assert "details"  in result
        assert isinstance(result["score_h"], float)
        assert 0.0 <= result["score_h"] <= 1.0

    def test_mocked_unavailable_video(self):
        """Pass an unavailable mock dict and verify clean handling."""
        mock_unavailable = {
            "status":  "unavailable",
            "reason":  "Video is private",
            "message": "Video cannot be analyzed: Video is private",
            "video_id": "private123",
            "segments": [],
            "thumbnail_intensity": 0.0,
        }

        result = compute_heuristic_score(mock_unavailable)

        assert isinstance(result, dict)
        assert "score_h" in result
        assert 0.0 <= result["score_h"] <= 1.0

    def test_score_h_uses_aggregate_if_present(self):
        """
        When aggregate_heuristic_score is in the sample dict,
        compute_heuristic_score should use it directly.
        """
        mock_sample = {
            "status": "success",
            "segments": [],
            "thumbnail_intensity": 0.0,
            "aggregate_heuristic_score": 0.42,
        }
        result = compute_heuristic_score(mock_sample)
        assert result["score_h"] == 0.42

    def test_score_h_in_valid_range(self):
        """score_h must always be between 0.0 and 1.0."""
        mock_sample = {
            "status": "success",
            "segments": [
                {"fcr": 1.0, "csv": 1.0, "att": 1.0, "score_h": 1.0},
            ],
            "thumbnail_intensity": 1.0,
        }
        result = compute_heuristic_score(mock_sample)
        assert 0.0 <= result["score_h"] <= 1.0

    def test_thumbnail_only_when_no_segments(self):
        """When segments list is empty, score_h should be derived from thumbnail only."""
        mock_sample = {
            "status": "thumbnail_only",
            "segments": [],
            "thumbnail_intensity": 0.80,
        }
        result = compute_heuristic_score(mock_sample)
        assert isinstance(result, dict)
        assert "score_h" in result
        # With w_thumb=0.20 and thumbnail=0.80: score_h = 0.20 * 0.80 = 0.16
        assert abs(result["score_h"] - 0.16) < 0.01