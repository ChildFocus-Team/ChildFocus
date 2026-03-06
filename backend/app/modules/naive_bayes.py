"""
ChildFocus - Naïve Bayes Classifier Module
backend/app/modules/naive_bayes.py

Sprint 2 — Metadata-based probabilistic classification.

Loads pre-trained model from ml_training/outputs/:
  - nb_model.pkl    : trained MultinomialNB or ComplementNB classifier
  - vectorizer.pkl  : fitted TF-IDF vectorizer

Pipeline (per manuscript Chapter 2 + Figure 2):
  Input metadata (title, description, tags)
      ↓
  Text cleaning + tokenization
      ↓
  Stop-word removal
      ↓
  TF-IDF feature vectorization
      ↓
  Score_NB = (1/Z) × [log P(C_over) + Σ log P(token | C_over)]
      ↓
  Logistic normalization → Score_NB ∈ [0, 1]

Score_NB is then consumed by hybrid_fusion.py:
  Score_final = α × Score_NB + (1 - α) × Score_H
  where α = 0.4 (metadata weight, per manuscript)
"""

import os
import re
import pickle
import logging
from dataclasses import dataclass
from typing import Optional

import numpy as np

logger = logging.getLogger(__name__)

# ── Model paths ────────────────────────────────────────────────────────────────
_BASE_DIR      = os.path.dirname(os.path.abspath(__file__))
_OUTPUTS_DIR   = os.path.normpath(
    os.path.join(_BASE_DIR, "..", "..", "..", "ml_training", "outputs")
)
MODEL_PATH      = os.path.join(_OUTPUTS_DIR, "nb_model.pkl")
VECTORIZER_PATH = os.path.join(_OUTPUTS_DIR, "vectorizer.pkl")

# ── Class label → overstimulation index mapping ───────────────────────────────
# Maps classifier output labels to their overstimulation index position.
# Must match the label encoding used in train_nb.py.
LABEL_TO_IDX = {
    "Educational":    0,
    "Neutral":        1,
    "Overstimulating": 2,
}

# Overstimulation probability is the probability of the Overstimulating class.
OVERSTIM_CLASS = "Overstimulating"

# ── Stop words (lightweight, no NLTK dependency) ──────────────────────────────
_STOP_WORDS = {
    "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
    "of", "with", "by", "from", "is", "it", "this", "that", "was", "are",
    "be", "as", "at", "so", "we", "he", "she", "they", "you", "i", "my",
    "your", "his", "her", "its", "our", "their", "what", "which", "who",
    "will", "would", "could", "should", "has", "have", "had", "do", "does",
    "did", "not", "no", "if", "then", "than", "when", "where", "how",
    "all", "each", "more", "also", "just", "can", "up", "out", "about",
    "into", "than", "too", "very", "s", "t", "re", "ve", "ll", "d",
}


# ── Output dataclass ───────────────────────────────────────────────────────────
@dataclass
class NBResult:
    """Result from Naïve Bayes metadata classification."""
    score_nb:        float         # normalized overstimulation score [0, 1]
    predicted_label: str           # raw model prediction
    confidence:      float         # max class probability [0, 1]
    probabilities:   dict          # {label: probability} for all classes
    text_used:       str           # cleaned text that was classified
    model_loaded:    bool = True
    error:           Optional[str] = None


# ── Model loader (singleton) ──────────────────────────────────────────────────
class _ModelCache:
    """
    Loads nb_model.pkl and vectorizer.pkl once and caches them.
    Thread-safe for concurrent Flask requests.
    """
    _model      = None
    _vectorizer = None
    _classes    = None
    _loaded     = False
    _error      = None

    @classmethod
    def load(cls) -> bool:
        if cls._loaded:
            return cls._error is None
        try:
            if not os.path.exists(MODEL_PATH):
                raise FileNotFoundError(f"Model not found: {MODEL_PATH}")
            if not os.path.exists(VECTORIZER_PATH):
                raise FileNotFoundError(f"Vectorizer not found: {VECTORIZER_PATH}")

            with open(MODEL_PATH, "rb") as f:
                cls._model = pickle.load(f)
            with open(VECTORIZER_PATH, "rb") as f:
                cls._vectorizer = pickle.load(f)

            # Get class labels from model
            cls._classes = list(cls._model.classes_)
            cls._loaded  = True
            cls._error   = None
            logger.info(f"[NB] Model loaded. Classes: {cls._classes}")
            print(f"[NB] ✓ Model loaded from {_OUTPUTS_DIR}")
            print(f"[NB] ✓ Classes: {cls._classes}")
            return True

        except Exception as e:
            cls._error  = str(e)
            cls._loaded = True   # mark as attempted so we don't retry every request
            logger.error(f"[NB] Failed to load model: {e}")
            print(f"[NB] ✗ Model load failed: {e}")
            return False

    @classmethod
    def get(cls):
        cls.load()
        return cls._model, cls._vectorizer, cls._classes, cls._error


# ── Text preprocessing ────────────────────────────────────────────────────────
def preprocess_text(title: str = "", description: str = "", tags: list = None) -> str:
    """
    Clean and combine metadata fields into a single text string.

    Pipeline:
      1. Combine title (weighted 3x) + tags (weighted 2x) + description
         Title and tags are more signal-dense than descriptions.
      2. Lowercase
      3. Remove URLs, special characters, numbers
      4. Remove stop words
      5. Collapse whitespace

    Returns cleaned text ready for TF-IDF vectorization.
    """
    tags = tags or []

    # Weight title and tags higher — they are more signal-dense
    title_text = f"{title} " * 3
    tags_text  = f"{' '.join(tags)} " * 2
    desc_text  = description[:300] if description else ""   # truncate long descriptions

    raw = f"{title_text}{tags_text}{desc_text}"

    # Lowercase
    text = raw.lower()

    # Remove URLs
    text = re.sub(r"https?://\S+|www\.\S+", " ", text)

    # Remove special characters and digits (keep letters and spaces)
    text = re.sub(r"[^a-z\s]", " ", text)

    # Tokenize and remove stop words
    tokens = [t for t in text.split() if t not in _STOP_WORDS and len(t) > 1]

    return " ".join(tokens)


# ── Logistic normalization ────────────────────────────────────────────────────
def _logistic(x: float) -> float:
    """Sigmoid normalization → maps any real value to (0, 1)."""
    return float(1.0 / (1.0 + np.exp(-x)))


def _normalize_score(proba_overstim: float) -> float:
    """
    Normalize raw overstimulation probability to Score_NB ∈ [0, 1].
    Per manuscript formula:
      Score_NB = (1/Z) × [log P(C_over) + Σ log P(token | C_over)]
    The sklearn predict_proba already computes this — we apply logistic
    normalization to sharpen separation between classes.
    """
    # Apply logistic stretch: values near 0.5 get pulled toward extremes
    # This makes the NB score more decisive for hybrid fusion
    stretched = (proba_overstim - 0.5) * 6.0   # scale factor empirically tuned
    return round(float(np.clip(_logistic(stretched), 0.0, 1.0)), 4)


# ── Main inference function ───────────────────────────────────────────────────
def score_metadata(
    title:       str  = "",
    description: str  = "",
    tags:        list = None,
) -> NBResult:
    """
    Classify video metadata and return Score_NB ∈ [0, 1].

    Score_NB represents the overstimulation likelihood from metadata alone.
    Higher = more likely to be overstimulating based on title/tags/description.

    Used in:
      - Fast path: Score_NB + thumbnail → immediate allow/block decision
      - Full path: feeds into hybrid_fusion.py alongside Score_H

    Args:
        title:       Video title string
        description: Video description (truncated to 300 chars internally)
        tags:        List of YouTube tags

    Returns:
        NBResult dataclass with score_nb, predicted_label, confidence, probabilities
    """
    tags = tags or []

    # Load model (cached after first call)
    model, vectorizer, classes, load_error = _ModelCache.get()

    if load_error or model is None:
        # Graceful fallback: return neutral score if model unavailable
        print(f"[NB] ⚠ Model unavailable, returning neutral score. Error: {load_error}")
        return NBResult(
            score_nb        = 0.5,
            predicted_label = "Neutral",
            confidence      = 0.0,
            probabilities   = {},
            text_used       = "",
            model_loaded    = False,
            error           = load_error,
        )

    # Preprocess
    cleaned_text = preprocess_text(title, description, tags)

    if not cleaned_text.strip():
        # Empty metadata — return neutral
        return NBResult(
            score_nb        = 0.5,
            predicted_label = "Neutral",
            confidence      = 0.33,
            probabilities   = {c: 0.33 for c in classes},
            text_used       = cleaned_text,
            error           = "Empty metadata after preprocessing",
        )

    try:
        # TF-IDF vectorization
        X = vectorizer.transform([cleaned_text])

        # Predict probabilities
        proba = model.predict_proba(X)[0]           # shape: (n_classes,)
        pred  = model.predict(X)[0]                 # predicted class label

        # Build probability dict {label: probability}
        prob_dict = {cls: round(float(p), 4) for cls, p in zip(classes, proba)}

        # Get overstimulation probability
        overstim_idx   = list(classes).index(OVERSTIM_CLASS) if OVERSTIM_CLASS in classes else -1
        proba_overstim = float(proba[overstim_idx]) if overstim_idx >= 0 else 0.5

        # Normalize to Score_NB
        score_nb = _normalize_score(proba_overstim)

        print(f"[NB] '{title[:40]}' → {pred} | Score_NB={score_nb} | P(over)={proba_overstim:.3f}")

        return NBResult(
            score_nb        = score_nb,
            predicted_label = str(pred),
            confidence      = round(float(np.max(proba)), 4),
            probabilities   = prob_dict,
            text_used       = cleaned_text[:200],
        )

    except Exception as e:
        logger.error(f"[NB] Inference error: {e}")
        print(f"[NB] ✗ Inference error: {e}")
        return NBResult(
            score_nb        = 0.5,
            predicted_label = "Neutral",
            confidence      = 0.0,
            probabilities   = {},
            text_used       = cleaned_text,
            model_loaded    = True,
            error           = str(e),
        )


# ── Convenience wrapper for route integration ─────────────────────────────────
def score_from_metadata_dict(metadata: dict) -> NBResult:
    """
    Convenience wrapper accepting a dict from youtube_api.get_video_metadata().
    Extracts title, description, tags automatically.

    Usage:
        meta   = youtube_api.get_video_metadata(video_id)
        result = naive_bayes.score_from_metadata_dict(meta)
        score  = result.score_nb
    """
    return score_metadata(
        title       = metadata.get("title",       ""),
        description = metadata.get("description", ""),
        tags        = metadata.get("tags",        []),
    )


# ── Model status check ────────────────────────────────────────────────────────
def model_status() -> dict:
    """
    Returns model loading status. Used by /health endpoint.
    """
    model, _, classes, error = _ModelCache.get()
    return {
        "loaded":      model is not None,
        "model_path":  MODEL_PATH,
        "classes":     classes or [],
        "error":       error,
    }
