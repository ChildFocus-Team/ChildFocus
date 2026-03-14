import os
import re
import requests
from dotenv import load_dotenv

load_dotenv()
API_KEY = os.getenv("YOUTUBE_API_KEY")

THUMBNAIL_QUALITY = ["maxres", "standard", "high", "medium", "default"]


def get_best_thumbnail_url(thumbnails: dict) -> str:
    """Returns the highest quality thumbnail URL from a thumbnails dict."""
    for quality in THUMBNAIL_QUALITY:
        if quality in thumbnails:
            return thumbnails[quality]["url"]
    return ""


def extract_video_id(url: str) -> str:
    """Extracts video ID from a YouTube URL. Always returns an 11-char ID."""
    url = url.strip()
    for pattern in [r"[?&]v=([A-Za-z0-9_-]{11})",
                    r"youtu\.be/([A-Za-z0-9_-]{11})",
                    r"shorts/([A-Za-z0-9_-]{11})"]:
        m = re.search(pattern, url)
        if m:
            return m.group(1)
    m = re.search(r"([A-Za-z0-9_-]{11})", url)
    if m:
        return m.group(1)
    return url


def get_video_metadata(video_id: str) -> dict:
    """
    Fetches title, tags, description, and duration from YouTube Data API v3.
    Returns a dict or raises an exception on failure.
    """
    if not API_KEY:
        return {"error": "YOUTUBE_API_KEY not set in .env"}

    url = "https://www.googleapis.com/youtube/v3/videos"
    params = {
        "part": "snippet,contentDetails,statistics",
        "id": video_id,
        "key": API_KEY
    }

    try:
        response = requests.get(url, params=params, timeout=10)
        response.raise_for_status()
        data = response.json()
    except requests.exceptions.RequestException as e:
        return {"error": f"API request failed: {e}"}

    if not data.get("items"):
        return {"error": f"Video not found: {video_id}"}

    item    = data["items"][0]
    snippet = item["snippet"]
    stats   = item.get("statistics", {})

    thumbnail_url = get_best_thumbnail_url(snippet.get("thumbnails", {}))

    return {
        "video_id":      video_id,
        "title":         snippet.get("title", ""),
        "description":   snippet.get("description", "")[:500],
        "tags":          snippet.get("tags", []),
        "channel":       snippet.get("channelTitle", ""),
        "duration":      item["contentDetails"].get("duration", ""),
        "view_count":    int(stats.get("viewCount", 0)),
        "like_count":    int(stats.get("likeCount", 0)),
        "comment_count": int(stats.get("commentCount", 0)),
        "thumbnail_url": thumbnail_url,
    }


def get_thumbnail_url(video_id: str) -> str:
    """Returns best available thumbnail URL for a video without using API quota."""
    direct_urls = [
        f"https://i.ytimg.com/vi/{video_id}/maxresdefault.jpg",
        f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg",
        f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg",
        f"https://i.ytimg.com/vi/{video_id}/default.jpg",
    ]
    for thumb_url in direct_urls:
        try:
            resp = requests.head(thumb_url, timeout=5)
            if resp.status_code == 200 and int(resp.headers.get("content-length", 0)) > 5000:
                return thumb_url
        except Exception:
            continue
    return f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"


def search_child_videos(query: str, max_results: int = 50) -> list:
    """Searches YouTube for child-directed videos matching the query."""
    if not API_KEY:
        return []

    url    = "https://www.googleapis.com/youtube/v3/search"
    params = {
        "part":       "snippet",
        "q":          query,
        "type":       "video",
        "maxResults": min(max_results, 50),
        "relevanceLanguage": "en",
        "key":        API_KEY,
    }
    try:
        resp  = requests.get(url, params=params, timeout=10)
        resp.raise_for_status()
        items = resp.json().get("items", [])
        return [item["id"]["videoId"] for item in items if "videoId" in item.get("id", {})]
    except Exception as e:
        print(f"[SEARCH] Error: {e}")
        return []


def search_video_by_title(title: str) -> dict:
    """
    Search YouTube for a video by title string.
    Used by Android AccessibilityService to resolve title → video_id.

    Cleans duplicate text (YouTube accessibility doubles text) and
    removes timestamp noise before searching.

    Returns: { video_id, title, channel, thumbnail_url }
    """
    if not API_KEY:
        return {"error": "YOUTUBE_API_KEY not set in .env"}

    # ── Clean the title ────────────────────────────────────────────────────────
    # 1. Remove timestamp noise like "0 minutes 0 seconds of 4 minutes 20 seconds"
    cleaned = re.sub(
        r'\d+\s+(?:minutes?|seconds?|hours?)\s+\d+\s+(?:minutes?|seconds?|hours?)[^A-Za-z]*',
        ' ', title, flags=re.IGNORECASE
    )
    cleaned = re.sub(
        r'\d+\s+(?:minutes?|seconds?|hours?)\s+of\s+\d+[^A-Za-z]*',
        ' ', cleaned, flags=re.IGNORECASE
    )

    # 2. Remove duplicate text (YouTube accessibility doubles the title)
    #    e.g. "FPJ's Batang Quiapo FPJ's Batang Quiapo" → "FPJ's Batang Quiapo"
    words = cleaned.split()
    half  = len(words) // 2
    if half > 3 and words[:half] == words[half:]:
        cleaned = " ".join(words[:half])

    # 3. Trim and take first 100 chars
    cleaned = cleaned.strip()[:100]

    print(f"[SEARCH] Searching YouTube for: {cleaned!r}")

    url    = "https://www.googleapis.com/youtube/v3/search"
    params = {
        "part":       "snippet",
        "q":          cleaned,
        "type":       "video",
        "maxResults": 1,
        "key":        API_KEY,
    }

    try:
        resp = requests.get(url, params=params, timeout=10)
        resp.raise_for_status()
        items = resp.json().get("items", [])
    except Exception as e:
        print(f"[SEARCH] API error: {e}")
        return {"error": f"YouTube search failed: {e}"}

    if not items:
        return {"error": f"No results for title: {cleaned!r}"}

    item      = items[0]
    video_id  = item["id"].get("videoId", "")
    snippet   = item.get("snippet", {})
    found_title = snippet.get("title", "")
    channel   = snippet.get("channelTitle", "")
    thumbs    = snippet.get("thumbnails", {})
    thumb_url = (thumbs.get("high") or thumbs.get("medium") or thumbs.get("default") or {}).get("url", "")

    print(f"[SEARCH] ✓ Found: {video_id} — {found_title}")

    return {
        "video_id":      video_id,
        "title":         found_title,
        "channel":       channel,
        "thumbnail_url": thumb_url,
    }