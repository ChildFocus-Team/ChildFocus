"""
ChildFocus - Regenerate hybrid_real_results.json
ml_training/scripts/regenerate_results.py

Calls the live Flask API (classify_full) for all 30 evaluation videos
and saves fresh Score_NB and Score_H values to hybrid_real_results.json.

Run from ml_training/scripts/ with Flask running on localhost:5000:
    python regenerate_results.py
"""

import os
import json
import time
import datetime
import requests

SCRIPT_DIR   = os.path.dirname(os.path.abspath(__file__))
OUTPUT_PATH  = os.path.join(SCRIPT_DIR, "..", "outputs", "hybrid_real_results.json")
FLASK_URL    = "http://localhost:5000/classify_full"

# 30 evaluation videos with ground truth labels
EVAL_VIDEOS = [
    {"video_id": "pCkLAjZACTY", "true_label": "Educational",      "title": "Fun ABC Song for Kids | Learn Alphabet with Engli the Monster"},
    {"video_id": "KGD1TJ_XjbI", "true_label": "Neutral",          "title": "Korean Cheesy Potato Pancake (4 Ingredients ONLY!)"},
    {"video_id": "xDOd-2wc84U", "true_label": "Overstimulating",  "title": "TOP FUNNIEST FAKE TIGER PRANK ON GRANDPA! | SAGOR BHUYAN"},
    {"video_id": "Mbc29yUtIxs", "true_label": "Neutral",          "title": "Microwave Meals: Lava Cake!"},
    {"video_id": "3khdMPRVysc", "true_label": "Overstimulating",  "title": "Baby Shark Dance Battle | Baby Shark Challenge"},
    {"video_id": "-q-jS9ftgew", "true_label": "Overstimulating",  "title": "Most Oddly Satisfying Slime Videos to watch before sleep"},
    {"video_id": "CTByqqMNb1c", "true_label": "Overstimulating",  "title": "60 Minutes Ultimate Cooking Toys Playset"},
    {"video_id": "LZFwXQKIaB4", "true_label": "Overstimulating",  "title": "Baby Shark Dance and more | Best Dance Along | Pinkfong"},
    {"video_id": "pDoUeQ26EDU", "true_label": "Educational",      "title": "Phonics Song, ABC Song | ABC in the Supermarket"},
    {"video_id": "5ajRc-YvLhg", "true_label": "Educational",      "title": "Best Learning Videos for Toddlers | Learning Colors and Shapes"},
    {"video_id": "MkU2dlkZIZU", "true_label": "Neutral",          "title": "The Manic Panic! | Funny Animated Read Aloud Kids Book | Vooks"},
    {"video_id": "JV-_9zw62tQ", "true_label": "Neutral",          "title": "Easy Scrambled Eggs by a 4 year old"},
    {"video_id": "Dqjf2SPGIg0", "true_label": "Neutral",          "title": "Silly Crocodile Tries to Be a Monkey and Gets Hurt | Kids Animation"},
    {"video_id": "ZA9DTkaR6ao", "true_label": "Educational",      "title": "Letter Sounds Phonics Song YouTube Plus More Learning Songs for Kids"},
    {"video_id": "OSWLxj4kTKA", "true_label": "Neutral",          "title": "The Ugly Duckling | Full Story | Fairytale | Bedtime Stories For Kids"},
    {"video_id": "bIsYZP9k9h4", "true_label": "Overstimulating",  "title": "Scary mask prank! Her reaction!! #shorts"},
    {"video_id": "AZnC2yqGMrU", "true_label": "Educational",      "title": "Blippi and Layla Go Down the Slide! #Shorts"},
    {"video_id": "PmOhUs9IMdk", "true_label": "Overstimulating",  "title": "Game On with the Louds! | The Loud House"},
    {"video_id": "xe9N-Cl-kgU", "true_label": "Overstimulating",  "title": "Jannie's Magic Slime Creation Experiment!"},
    {"video_id": "JI5thN4KtgI", "true_label": "Overstimulating",  "title": "Burger Shop Toy Playset ASMR Satisfying with Unboxing"},
    {"video_id": "kN-lfHr3pd0", "true_label": "Educational",      "title": "Meekah Learns Science and Does Fun Science Experiments!"},
    {"video_id": "Yt8GFgxlITs", "true_label": "Educational",      "title": "Counting 1 to 10 | Number Songs | PINKFONG Songs for Children"},
    {"video_id": "LhYtcadR9nw", "true_label": "Educational",      "title": "Squish the Fish | Yoga for Kids! A Cosmic Kids Yoga Adventure"},
    {"video_id": "ELbcakxFvfc", "true_label": "Neutral",          "title": "Hansel and Gretel story for children | Bedtime Stories and Fairy Tales"},
    {"video_id": "pckuS--UlV4", "true_label": "Neutral",          "title": "The Gingerbread Man | Full Story | Animated Fairy Tales For Children"},
    {"video_id": "r25DlDLEGXs", "true_label": "Overstimulating",  "title": "Satisfying Slime ASMR Videos | Relaxing Slime No Talking"},
    {"video_id": "dZUq8hpKICY", "true_label": "Neutral",          "title": "Train Choo Choo Song | Find the Animal + More Lalafun Nursery Rhymes"},
    {"video_id": "-9NXxlFnZcU", "true_label": "Neutral",          "title": "3 Little Pigs | Bedtime Stories for Kids in English | Storytime"},
    {"video_id": "sSRD1ZJEUbY", "true_label": "Educational",      "title": "Learn Shapes with Color Balls - Colors Videos Collection for Children"},
    {"video_id": "EbgwPx6mYu4", "true_label": "Educational",      "title": "Counting 1 to 20 | Number Songs | PINKFONG Songs for Children"},
]


def classify_video(video_id, title):
    url   = f"https://www.youtube.com/watch?v={video_id}"
    thumb = f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"
    try:
        resp = requests.post(FLASK_URL, json={
            "video_url":     url,
            "thumbnail_url": thumb,
            "hint_title":    title,
        }, timeout=300)
        resp.raise_for_status()
        return resp.json()
    except Exception as e:
        return {"error": str(e)}


def main():
    print("=" * 60)
    print("ChildFocus - Regenerating hybrid_real_results.json")
    print("=" * 60)
    print(f"Flask endpoint : {FLASK_URL}")
    print(f"Videos to process: {len(EVAL_VIDEOS)}")
    print(f"Estimated time : ~30-60 minutes")
    print()

    results   = []
    n_success = 0
    n_error   = 0

    for i, v in enumerate(EVAL_VIDEOS, 1):
        vid   = v["video_id"]
        label = v["true_label"]
        title = v["title"]

        print(f"[{i:02d}/{len(EVAL_VIDEOS)}] {vid} ({label})")
        print(f"         {title[:55]}")
        t0  = time.time()
        api = classify_video(vid, title)
        elapsed = round(time.time() - t0, 2)

        if "error" in api:
            print(f"  x ERROR: {api['error']}")
            results.append({
                "video_id":    vid,
                "title":       title,
                "true_label":  label,
                "pred_label":  "ERROR",
                "score_nb":    None,
                "score_h":     None,
                "score_final": None,
                "error":       api["error"],
            })
            n_error += 1
            continue

        score_nb    = api.get("score_nb", 0)
        score_h     = api.get("score_h", 0)
        score_final = api.get("score_final", 0)
        oir_label   = api.get("oir_label", "ERROR")
        sample_path = api.get("sample_path", "unknown")
        segments    = api.get("heuristic_details", {}).get("segments", [])
        thumb       = api.get("heuristic_details", {}).get("thumbnail_intensity", 0)

        mark = "OK" if oir_label == label else "WRONG"
        print(f"  [{mark}] NB={score_nb:.4f}  H={score_h:.4f}  "
              f"Final={score_final:.4f}  -> {oir_label}  ({elapsed}s)")

        results.append({
            "video_id":            vid,
            "title":               title,
            "true_label":          label,
            "pred_label":          oir_label,
            "score_nb":            score_nb,
            "score_h":             score_h,
            "score_final":         score_final,
            "nb_confidence":       api.get("nb_details", {}).get("confidence", 0),
            "sample_path":         sample_path,
            "segments":            segments,
            "thumbnail_intensity": thumb,
            "status":              api.get("status", "unknown"),
            "runtime":             elapsed,
        })
        n_success += 1

    # Save output
    output = {
        "generated": datetime.datetime.now().isoformat(),
        "config": {
            "alpha_nb":        0.6,
            "beta_heuristic":  0.4,
            "threshold_block": 0.20,
            "threshold_allow": 0.08,
            "normalization": {
                "C_MAX":       0.23,
                "S_MAX":       0.42,
                "ATT_divisor": 0.30,
            }
        },
        "summary": {
            "n_total":   len(EVAL_VIDEOS),
            "n_success": n_success,
            "n_error":   n_error,
        },
        "results": results,
    }

    with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(output, f, indent=2, ensure_ascii=False)

    print()
    print("=" * 60)
    print(f"Done. {n_success} succeeded, {n_error} failed.")
    print(f"Saved -> {OUTPUT_PATH}")
    print("Now run: python evaluate_final_hybrid.py")
    print("=" * 60)


if __name__ == "__main__":
    main()
