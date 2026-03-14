"""
ChildFocus - Metadata Route
backend/app/routes/metadata.py

Endpoints:
  GET /metadata?video_url=...  → fetch YouTube video metadata
  GET /search?title=...        → search YouTube by title, returns video_id
  GET /health                  → health check
  GET /config                  → current fusion config
"""

from flask import Blueprint, request, jsonify
from app.modules.youtube_api   import get_video_metadata, extract_video_id, search_video_by_title
from app.modules.hybrid_fusion import get_fusion_config
from app.modules.heuristic     import get_feature_weights
from app.utils.validators      import validate_video_url

metadata_bp = Blueprint("metadata", __name__)


@metadata_bp.route("/metadata", methods=["GET"])
def get_metadata():
    """
    Fetch YouTube metadata for a video.

    Query params:
        video_url: full YouTube URL or video ID

    Returns:
        title, description, tags, channel, thumbnail_url, duration, view_count
    """
    video_url = request.args.get("video_url", "")
    if not video_url:
        return jsonify({"error": "Missing video_url query parameter"}), 400

    error = validate_video_url(video_url)
    if error:
        return jsonify({"error": error}), 400

    video_id = extract_video_id(video_url)
    metadata = get_video_metadata(video_id)

    if "error" in metadata:
        return jsonify(metadata), 404

    return jsonify(metadata), 200


@metadata_bp.route("/search", methods=["GET"])
def search_by_title():
    """
    Search YouTube for a video by title string.
    Used by Android AccessibilityService when no video URL is available.

    Query params:
        title: video title string detected from YouTube app UI

    Returns:
        { video_id, title, channel, thumbnail_url }
    """
    title = request.args.get("title", "").strip()
    if not title or len(title) < 3:
        return jsonify({"error": "Missing or too short title"}), 400

    result = search_video_by_title(title)

    if "error" in result:
        return jsonify(result), 404

    return jsonify(result), 200


@metadata_bp.route("/health", methods=["GET"])
def health():
    """Health check endpoint for Android app connectivity test."""
    from app.modules.naive_bayes import model_status
    import sqlite3, os

    nb_status = model_status()
    db_ok = False
    count = 0
    try:
        _base = os.path.dirname(os.path.abspath(__file__))
        db_path = os.path.normpath(os.path.join(_base, "..", "..", "..", "database", "childfocus.db"))
        conn  = sqlite3.connect(db_path)
        count = conn.execute("SELECT COUNT(*) FROM videos").fetchone()[0]
        conn.close()
        db_ok = True
    except Exception:
        pass

    return jsonify({
        "status":   "ok" if nb_status["loaded"] and db_ok else "degraded",
        "nb_model": nb_status,
        "database": {"connected": db_ok, "cached_videos": count},
    }), 200


@metadata_bp.route("/config", methods=["GET"])
def config():
    """Returns current fusion config and heuristic weights for transparency."""
    return jsonify({
        "fusion":    get_fusion_config(),
        "heuristic": get_feature_weights(),
    }), 200