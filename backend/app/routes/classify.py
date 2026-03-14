from flask import Blueprint, request, jsonify
from app.modules.frame_sampler import sample_video
from app.modules.heuristic import compute_heuristic_score
from app.modules.naive_bayes import score_from_metadata_dict, score_metadata

classify_bp = Blueprint("classify", __name__)

# ── Database path ─────────────────────────────────────────────────────────────
DB_PATH = os.path.join(
    os.path.dirname(__file__), "..", "..", "..", "database", "childfocus.db"
)


# ── Helpers ───────────────────────────────────────────────────────────────────

def extract_video_id(url: str) -> str:
    """Extract the 11-character YouTube video ID from a URL or return ID as-is."""
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
    # If already an 11-char ID, return it directly
    if re.fullmatch(r"[a-zA-Z0-9_-]{11}", url.strip()):
        return url.strip()
    return url.strip()


def _run_full_analysis(video_id: str, thumb_url: str = "") -> tuple[dict, int]:
    """
    Core hybrid classification logic shared by /classify_full and /classify_by_title.

    FIX: sample_video() expects a plain video_id (11 chars), NOT a full URL.
    Previously classify_full was passing video_url here, causing:
      ERROR: Unsupported URL: https://...watch?v=https://...watch?v=<id>
    """
    t_start = time.time()

    # 1. Frame sampling + heuristic  ← pass video_id only
    sample   = sample_video(video_id, thumbnail_url=thumb_url)
    h_result = compute_heuristic_score(sample)
    score_h  = h_result["score_h"]
    h_details = h_result.get("details", {})

    # 2. NB classification
    nb_obj = score_from_metadata_dict({
        "title":       sample.get("title", ""),
        "tags":        sample.get("tags", []),
        "description": sample.get("description", ""),
    })
    score_nb        = nb_obj.score_nb
    predicted_label = nb_obj.predicted_label

    # 3. Hybrid fusion  Score_final = 0.4×NB + 0.6×H
    score_final = round((0.4 * score_nb) + (0.6 * score_h), 4)
    if score_final >= 0.75:
        oir_label = "Overstimulating"
        action    = "block"
    elif score_final <= 0.35:
        oir_label = "Educational"
        action    = "allow"
    else:
        oir_label = "Neutral"
        action    = "allow"

    runtime = round(time.time() - t_start, 3)

    result = {
        "video_id":          video_id,
        "video_title":       sample.get("title", ""),
        "oir_label":         oir_label,
        "score_nb":          round(score_nb, 4),
        "score_h":           round(score_h, 4),
        "score_final":       round(score_final, 4),
        "cached":            False,
        "action":            action,
        "runtime_seconds":   runtime,
        "status":            "success",
        "heuristic_details": h_details,
        "nb_details": {
            "predicted":  predicted_label,
            "confidence": round(nb_obj.confidence, 4),
        },
    }

    _save_to_db(result)
    print(f"[ROUTE] /classify_full {video_id} → {oir_label} ({score_final}) in {runtime}s")
    return result, 200


def _save_to_db(result: dict):
    """Persist classification result to SQLite."""
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
    """Return cached row (label, final_score, last_checked) or None."""
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
    """
    Fast classification using only metadata + snapshot heuristic.
    Body: { "video_url": "https://youtube.com/watch?v=..." }
    """
    data = request.get_json()
    if not data or "video_url" not in data:
        return jsonify({"error": "Missing video_url"}), 400

    video_id = extract_video_id(data["video_url"])

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


@classify_bp.route("/classify_full", methods=["POST"])
def classify_full():
    """
    Full hybrid classification: frame sampling + heuristic + NB fusion.
    Takes 4–30 seconds for uncached videos.
    """
    data = request.get_json()
    if not data or "video_url" not in data:
        return jsonify({"error": "Missing video_url"}), 400

    video_url = data.get("video_url", "")
    thumbnail_url = data.get("thumbnail_url", "")

    if not video_url:
        return jsonify({"error": "video_url is required", "status": "error"}), 400

    # Always extract the bare 11-char ID first
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

    # ── Full analysis — pass video_id (NOT video_url) to sample_video ─────────
    try:
        result, status = _run_full_analysis(video_id, thumb_url=thumbnail_url)
        return jsonify(result), status
    except Exception as e:
        print(f"[ROUTE] /classify_full error: {e}")
        return jsonify({"error": str(e), "status": "error"}), 500


# ── /classify_by_title ────────────────────────────────────────────────────────

@classify_bp.route("/classify_by_title", methods=["POST"])
def classify_by_title():
    """
    Accepts a video title string (detected by Android AccessibilityService).
    Uses yt-dlp to find the matching video, then runs full hybrid classification.
    """
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
            print(f"[TITLE_ROUTE] No results found for: {title!r}")
            return jsonify({"error": "No video found", "status": "error"}), 404

        video_id  = entries[0].get("id", "")
        thumb_url = f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"

        print(f"[TITLE_ROUTE] Resolved: {title!r} → {video_id}")

        # ── Cache check ───────────────────────────────────────────────────────
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

        # ── Full analysis — pass bare video_id directly ───────────────────────
        result, status = _run_full_analysis(video_id, thumb_url=thumb_url)
        return jsonify(result), status

    except Exception as e:
        print(f"[TITLE_ROUTE] Error: {e}")
        return jsonify({"error": str(e), "status": "error"}), 500


# ── /health ───────────────────────────────────────────────────────────────────

@classify_bp.route("/health", methods=["GET"])
def health():
    """Quick health check — confirms Flask and NB model are loaded."""
    from app.modules.naive_bayes import model_status
    return jsonify({
        "status":    "ok",
        "nb_model":  model_status(),
        "db_path":   DB_PATH,
        "db_exists": os.path.exists(DB_PATH),
    }), 200
