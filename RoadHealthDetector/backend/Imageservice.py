from flask import Flask, request, jsonify
from flask_cors import CORS
from ultralytics import YOLO
import os
from datetime import datetime

app = Flask(__name__)
CORS(app)

model = YOLO("detection_model.pt")

UPLOAD_FOLDER = "./../../uploads"
os.makedirs(UPLOAD_FOLDER, exist_ok=True)

@app.route("/")
def home():
    return jsonify({
        "message": "Road Damage Detection API Running"
    })

@app.route("/api/public/detect", methods=["POST"])
def public_detect():

    if "image" not in request.files:
        return jsonify({"error": "No image uploaded"}), 400

    file = request.files["image"]

    filename = f"{datetime.now().timestamp()}_{file.filename}"
    filepath = os.path.join(UPLOAD_FOLDER, filename)
    file.save(filepath)

    results = model(filepath)

    boxes = results[0].boxes
    num_detections = len(boxes)

    damage_percentage = min(num_detections * 20, 100)
    return jsonify({
        "status": "success",
        "detections": num_detections,
        "damage_percentage": damage_percentage,
        "image_path": filepath
    })

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False, use_reloader=False)