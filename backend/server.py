"""
Road Health Detection - Backend Server
Receives frames from Android, runs YOLO, draws bounding boxes,
returns annotated image + detection stats back to Android.
"""

from flask import Flask, request, jsonify
from flask_cors import CORS
from ultralytics import YOLO
import base64
import numpy as np
import cv2
import os

app = Flask(__name__)
CORS(app)

MODEL_PATH = os.path.join(os.path.dirname(__file__), "detection_model.pt")
print(f"[*] Loading model: {MODEL_PATH}")
model = YOLO(MODEL_PATH)
print("[OK] Model loaded!")


@app.route("/")
def home():
    return jsonify({"message": "Road Damage Detection API Running"})


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "online", "model_loaded": True})


@app.route("/detect", methods=["POST"])
def detect():
    try:
        data = request.get_json()
        if not data or "frame" not in data:
            return jsonify({"error": "No frame provided"}), 400

        # ── Decode base64 frame from Android ──────────────────────────────────
        img_bytes = base64.b64decode(data["frame"])
        img_array = np.frombuffer(img_bytes, dtype=np.uint8)
        frame = cv2.imdecode(img_array, cv2.IMREAD_COLOR)

        if frame is None:
            return jsonify({"error": "Could not decode image"}), 400

        # ── Run YOLO detection ────────────────────────────────────────────────
        results = model(frame)

        # ── Draw bounding boxes on the frame (like the demo picture) ──────────
        annotated = frame.copy()
        boxes     = results[0].boxes
        names     = results[0].names

        num_detections = len(boxes)

        for box in boxes:
            # Coordinates
            x1, y1, x2, y2 = map(int, box.xyxy[0].tolist())
            conf  = float(box.conf[0])
            cls   = int(box.cls[0])
            label = names[cls] if names and cls in names else "pothole"

            # Draw blue box (same style as demo)
            cv2.rectangle(annotated, (x1, y1), (x2, y2), (255, 100, 0), 2)

            # Label text with confidence
            text = f"{label} {conf:.2f}"
            (tw, th), _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, 0.6, 2)

            # Blue background for label
            cv2.rectangle(annotated,
                          (x1, y1 - th - 10),
                          (x1 + tw + 6, y1),
                          (255, 100, 0), -1)
            # White text
            cv2.putText(annotated, text,
                        (x1 + 3, y1 - 5),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6,
                        (255, 255, 255), 2)

        # ── Damage summary ────────────────────────────────────────────────────
        damage_pct = min(num_detections * 20, 100)

        if damage_pct == 0:
            status_label = "Good Road"
            damaged      = False
            status_color = (50, 200, 50)   # green
        elif damage_pct <= 40:
            status_label = "Minor Damage"
            damaged      = True
            status_color = (0, 165, 255)   # orange
        elif damage_pct <= 70:
            status_label = "Moderate Damage"
            damaged      = True
            status_color = (0, 100, 255)   # orange-red
        else:
            status_label = "Severe Damage"
            damaged      = True
            status_color = (0, 0, 255)     # red

        # ── Draw status bar at bottom of annotated image ──────────────────────
        h, w = annotated.shape[:2]
        bar_h = 50
        cv2.rectangle(annotated, (0, h - bar_h), (w, h), (0, 0, 0), -1)
        summary = f"{status_label}  |  Potholes: {num_detections}  |  Damage: {damage_pct}%"
        cv2.putText(annotated, summary,
                    (10, h - 15),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.65,
                    status_color, 2)

        # ── Encode annotated image back to base64 ─────────────────────────────
        _, buf = cv2.imencode(".jpg", annotated, [cv2.IMWRITE_JPEG_QUALITY, 80])
        annotated_b64 = base64.b64encode(buf).decode("utf-8")

        return jsonify({
            "success": True,
            "annotated_frame": annotated_b64,          # <-- annotated image
            "result": {
                "label":             status_label,
                "confidence":        round(damage_pct / 100, 2),
                "damaged":           damaged,
                "detections":        num_detections,
                "damage_percentage": damage_pct
            }
        })

    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({"error": str(e)}), 500


if __name__ == "__main__":
    print("\n" + "=" * 50)
    print("  Road Health Detection Server")
    print("  Running on http://0.0.0.0:5000")
    print("=" * 50 + "\n")
    app.run(host="0.0.0.0", port=5000, debug=False, use_reloader=False)
