# Road Health Detector - Complete Setup Guide

## 📁 Project Structure

```
RoadHealthDetector/
├── backend/                    ← Python server (runs on your PC/laptop)
│   ├── server.py               ← Flask server wrapping Imageservice.py
│   └── requirements.txt        ← Python dependencies
│
└── app/                        ← Android Studio project
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/roadhealth/detector/
        │   └── MainActivity.java
        └── res/
            ├── layout/activity_main.xml
            ├── values/strings.xml
            └── values/themes.xml
```

---

## 🖥️ PART 1 — Set up the Python Backend (your PC)

### Step 1 — Copy files
Put these 3 files in the **same folder**:
- `server.py`  (from this project's `backend/` folder)
- `Imageservice.py`  (from your friend's GitHub)
- `detection_model.pt`  (from your friend's GitHub)

### Step 2 — Install Python dependencies
Open a terminal/command prompt in that folder and run:

```bash
pip install flask flask-cors torch torchvision opencv-python Pillow numpy
```

### Step 3 — ⚠️ Edit server.py to match your friend's Imageservice.py

Open `server.py` and find the `run_detection()` function.

Check what functions your friend's `Imageservice.py` has. Common patterns:

**Pattern A** — if it has a function like `detect(model, frame)`:
```python
result = img_service.detect(model, frame)
```

**Pattern B** — if it has a function like `predict(frame)`:
```python
result = img_service.predict(frame)
```

**Pattern C** — if `Imageservice.py` handles loading differently:
```python
# change load_model to match what your friend uses
model = img_service.load_model(MODEL_PATH)
```

### Step 4 — Find your PC's IP address

**Windows:**
```
Press Win+R → type cmd → ipconfig
Look for "IPv4 Address" under your WiFi adapter
Example: 192.168.1.105
```

**Mac/Linux:**
```bash
ifconfig | grep inet
```

### Step 5 — Start the server
```bash
python server.py
```
You should see:
```
Road Health Detection Server
Running on http://0.0.0.0:5000
Android should connect to: http://192.168.1.105:5000
```

---

## 📱 PART 2 — Set up the Android App (Android Studio)

### Step 1 — Open the project
- Open Android Studio
- Click "Open" and select the `RoadHealthDetector/` folder

### Step 2 — Wait for Gradle sync
- Android Studio will automatically download dependencies
- This may take 2-5 minutes on first open

### Step 3 — Connect your Android phone
- Enable Developer Mode on your phone:
  `Settings → About Phone → tap "Build Number" 7 times`
- Enable USB Debugging:
  `Settings → Developer Options → USB Debugging → ON`
- Connect phone via USB cable

### Step 4 — Run the app
- Click the green ▶ button in Android Studio
- Select your phone from the device list
- The app will install and launch

### Step 5 — Connect app to server
1. Make sure **your phone and PC are on the same WiFi network**
2. In the app, type your PC's IP and port: `192.168.1.105:5000`
3. Tap **Connect**
4. You should see "Connected" in green

### Step 6 — Start detecting!
1. Point phone camera at the road
2. Tap **▶ Start Detection**
3. The app sends frames to your Python server every 0.5 seconds
4. Results show at the bottom:
   - 🟢 Green = Good road
   - 🔴 Red = Damaged road
   - Confidence % shown as a progress bar

---

## 🔧 Troubleshooting

| Problem | Solution |
|---|---|
| "Cannot reach server" | Check phone and PC are on same WiFi. Disable Windows Firewall temporarily or allow port 5000 |
| "ImportError: Imageservice" | Make sure Imageservice.py is in the same folder as server.py |
| "Failed to load model" | Check detection_model.pt is in the same folder. Edit `load_model()` call in server.py |
| App crashes on open | Make sure you granted Camera permission |
| Blank camera screen | Try restarting the app |

---

## 🔌 Architecture

```
📱 Android Phone                    💻 Your PC
─────────────────                   ─────────────────────────────
Camera → CameraX                    Flask Server (server.py)
       → Capture frame (JPEG)  →→→  /detect endpoint
       → Base64 encode               → decode frame
       → HTTP POST                   → Imageservice.py
                                     → detection_model.pt
       ←←← JSON result ←←←←←←←←←← → return {label, confidence}
       → Show result on screen
```
