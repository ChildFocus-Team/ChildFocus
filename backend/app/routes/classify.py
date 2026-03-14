import os
import sqlite3
import time

from flask import Blueprint, jsonify, request

from app.modules.frame_sampler import sample_video
from app.modules.naive_bayes import score_from_metadata_dict, score_metadata

classify_bp = Blueprint("classify", __name__)

# ── Database path ─────────────────────────────────────────────────────────────
DB_PATH = os.path.join(
    os.path.dirname(__file__), "..", "..", "..", "database", "childfocus.db"
)


# ── Helpers ───────────────────────────────────────────────────────────────────

def extract_video_id(url: str) -> str:
    import re
    patterns = [
        r"(?:v=)([a-zA-Z0-9_-]{11})",
        r"(?:youtu\.be/)([a-zA-Z0-9_-]{11})",
        r"(?:embed/)([a-zA-Z0-9_-]{11})",
    ]
    for pattern in patterns:
        match = re.search(pattern, url)
        if match:
            return match.group(1)
    if re.fullmatch(r"[a-zA-Z0-9_-]{11}", url.strip()):
        return url.strip()
    return url.strip()


def _fuse(score_nb: float, score_h: float) -> tuple:
    """
    Hybrid fusion: Score_final = 0.4×NB + 0.6×H
    Returns (score_final, oir_label, action)
    """
    score_final = round((0.4 * score_nb) + (0.6 * score_h), 4)
    if score_final >= 0.75:
        return score_final, "Overstimulating", "block"
    elif score_final <= 0.35:
        return score_final, "Educational", "allow"
    else:
        return score_final, "Neutral", "allow"


def _save_to_db(result: dict):
    try:
        conn = sqlite3.connect(DB_PATH)
        cur  = conn.cursor()
        cur.execute("""
            INSERT OR REPLACE INTO videos
            (video_id, label, final_score, last_checked, checked_by,
             video_title, nb_score, heuristic_score, runtime_seconds)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?)
        """, (
            result["video_id"],
            result.get("oir_label", ""),
            result.get("score_final", 0.0),
            "hybrid_full",
            result.get("video_title", ""),
            result.get("score_nb", 0.0),
            result.get("score_h", 0.0),
            result.get("runtime_seconds", 0.0),
        ))
        for seg in result.get("heuristic_details", {}).get("segments", []):
            cur.execute("""
                INSERT INTO segments
                (video_id, segment_id, offset_seconds, length_seconds,
                 fcr, csv, att, score)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                result["video_id"],
                seg.get("segment_id"),
                seg.get("offset_seconds"),
                seg.get("length_seconds"),
                seg.get("fcr"),
                seg.get("csv"),
                seg.get("att"),
                seg.get("score_h"),
            ))
        conn.commit()
        conn.close()
        print(f"[DB] ✓ Saved result for {result['video_id']}")
    except Exception as e:
        print(f"[DB] ✗ Save error: {e}")


def _check_cache(video_id: str):
    try:
        conn = sqlite3.connect(DB_PATH)
        cur  = conn.cursor()
        cur.execute(
            "SELECT label, final_score, last_checked FROM videos WHERE video_id = ?",
            (video_id,)
        )
        row = cur.fetchone()
        conn.close()
        return row
    except Exception as e:
        print(f"[CACHE] Check failed: {e}")
        return None


# ── /classify_fast ────────────────────────────────────────────────────────────

@classify_bp.route("/classify_fast", methods=["POST"])
def classify_fast():
    data        = request.get_json(silent=True) or {}
    title       = data.get("title", "")
    tags        = data.get("tags", [])
    description = data.get("description", "")

    if not title:
        return jsonify({"error": "title is required", "status": "error"}), 400

    try:
        result = score_metadata(title=title, tags=tags, description=description)
        return jsonify({
            "score_nb":   result["score_nb"],
            "oir_label":  result["label"],
            "label":      result["label"],
            "confidence": result.get("confidence", 0.0),
            "status":     "success",
        }), 200
    except Exception as e:
        return jsonify({"error": str(e), "status": "error"}), 500


# ── /classify_full ────────────────────────────────────────────────────────────

@classify_bp.route("/classify_full", methods=["POST"])
def classify_full():
    """
    Full hybrid classification.

    FIX: Stopped calling compute_heuristic_score(sample) — that was passing
    the entire sample dict as a video_id string to heuristic.py, which then
    called sample_video(dict) a SECOND time causing a double download and
    a URL parse error.

    Now: score_h is extracted directly from the sample dict returned by
    sample_video() — it already contains aggregate_heuristic_score.

    Falls back to NB-only when video is age-restricted or unavailable.
    """
    data          = request.get_json(silent=True) or {}
    video_url     = data.get("video_url", "").strip()
    thumbnail_url = data.get("thumbnail_url", "")
    title_hint    = data.get("title_hint", "")

    if not video_url:
        return jsonify({"error": "video_url is required", "status": "error"}), 400

    video_id = extract_video_id(video_url)

    # ── Cache check ───────────────────────────────────────────────────────────
    cached = _check_cache(video_id)
    if cached:
        label, final_score, last_checked = cached
        print(f"[CACHE] ✓ Hit for {video_id} → {label}")
        return jsonify({
            "video_id":     video_id,
            "oir_label":    label,
            "score_final":  final_score,
            "last_checked": last_checked,
            "cached":       True,
            "action":       "block" if label == "Overstimulating" else "allow",
            "status":       "success",
        }), 200

    t_start = time.time()

    try:
        # ── Step 1: Frame sampling (already computes score_h internally) ──────
        sample = sample_video(video_id, thumbnail_url=thumbnail_url)
        status = sample.get("status", "error")

        if status == "success":
            # ── FIX: extract score_h directly — do NOT call
            # compute_heuristic_score(sample) which would re-run sample_video!
            score_h   = sample.get("aggregate_heuristic_score", 0.5)
            h_details = {"segments": sample.get("segments", [])}

            # ── Step 2: NB classification ─────────────────────────────────────
            nb_obj = score_from_metadata_dict({
                "title":       sample.get("video_title", title_hint),
                "tags":        sample.get("tags", []),
                "description": sample.get("description", ""),
            })
            score_nb        = nb_obj.score_nb
            predicted_label = nb_obj.predicted_label

            # ── Step 3: Fusion (0.4×NB + 0.6×H) ─────────────────────────────
            score_final, oir_label, action = _fuse(score_nb, score_h)
            method = "hybrid"

            print(f"[ROUTE] NB={score_nb:.4f} | H={score_h:.4f} | Final={score_final:.4f} → {oir_label}")

        else:
            # ── Fallback: NB-only for age-restricted / unavailable ────────────
            reason = sample.get("reason", sample.get("message", "unavailable"))
            print(f"[ROUTE] Fallback NB-only — reason: {reason}")

            nb_obj = score_from_metadata_dict({
                "title":       title_hint or video_id,
                "tags":        [],
                "description": "",
            })
            score_nb        = nb_obj.score_nb
            predicted_label = nb_obj.predicted_label
            score_h         = score_nb
            h_details       = {}

            score_final = round(score_nb, 4)
            if score_final >= 0.75:
                oir_label, action = "Overstimulating", "block"
            elif score_final <= 0.35:
                oir_label, action = "Educational", "allow"
            else:
                oir_label, action = "Neutral", "allow"

            method = "nb_only"

        runtime = round(time.time() - t_start, 3)

        result = {
            "video_id":          video_id,
            "video_title":       sample.get("video_title", title_hint),
            "oir_label":         oir_label,
            "score_nb":          round(score_nb, 4),
            "score_h":           round(score_h, 4),
            "score_final":       score_final,
            "cached":            False,
            "action":            action,
            "method":            method,
            "runtime_seconds":   runtime,
            "status":            "success",
            "heuristic_details": h_details,
            "nb_details": {
                "predicted":  predicted_label,
                "confidence": round(nb_obj.confidence, 4),
            },
        }

        _save_to_db(result)
        print(f"[ROUTE] /classify_full {video_id} → {oir_label} ({score_final}) [{method}] in {runtime}s")
        return jsonify(result), 200

    except Exception as e:
        import traceback
        print(f"[ROUTE] /classify_full CRASH: {e}")
        traceback.print_exc()
        return jsonify({"error": str(e), "status": "error"}), 500


# ── /classify_by_title ────────────────────────────────────────────────────────

@classify_bp.route("/classify_by_title", methods=["POST"])
def classify_by_title():
    data  = request.get_json(silent=True) or {}
    title = data.get("title", "").strip()

    if not title:
        return jsonify({"error": "title is required", "status": "error"}), 400

    print(f"[TITLE_ROUTE] Searching for: {title!r}")

    try:
        import yt_dlp

        ydl_opts = {
            "quiet":        True,
            "no_warnings":  True,
            "extract_flat": True,
        }

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(f"ytsearch1:{title}", download=False)

        entries = info.get("entries", [])
        if not entries:
            return jsonify({"error": "No video found", "status": "error"}), 404

        video_id  = entries[0].get("id", "")
        video_url = f"https://www.youtube.com/watch?v={video_id}"
        thumb_url = f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"

        print(f"[TITLE_ROUTE] Resolved: {title!r} → {video_id}")

        # Cache check
        cached = _check_cache(video_id)
        if cached:
            label, final_score, last_checked = cached
            print(f"[CACHE] ✓ Hit for {video_id} → {label}")
            return jsonify({
                "video_id":     video_id,
                "oir_label":    label,
                "score_final":  final_score,
                "last_checked": last_checked,
                "cached":       True,
                "action":       "block" if label == "Overstimulating" else "allow",
                "status":       "success",
            }), 200

        # Not cached — full analysis
        from flask import current_app
        with current_app.test_request_context(
            "/classify_full",
            method="POST",
            json={
                "video_url":     video_url,
                "thumbnail_url": thumb_url,
                "title_hint":    title,
            },
        ):
            return classify_full()

    except Exception as e:
        import traceback
        print(f"[TITLE_ROUTE] CRASH: {e}")
        traceback.print_exc()
        return jsonify({"error": str(e), "status": "error"}), 500


# ── /health ───────────────────────────────────────────────────────────────────

@classify_bp.route("/health", methods=["GET"])
def health():
    from app.modules.naive_bayes import model_status
    return jsonify({
        "status":    "ok",
        "nb_model":  model_status(),
        "db_path":   DB_PATH,
        "db_exists": os.path.exists(DB_PATH),
    }), 200
