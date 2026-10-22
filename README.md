# Nervos Network Wear OS Visualisers (Pixel Watch 3)

An interactive, high-performance **Wear OS (Pixel Watch 3)** housing and launcher for **Nervos Network (CKB)** blockchain visualizers.

Centering around the flagship visualizer — the **CKB Cell Laboratory** — this application transforms live on-chain UTXO state transitions into an interactive, scientific 3D exhibit powered by Three.js and Wear OS native hardware integrations (Rotary Crown, Haptic Engine, and AMOLED circular viewport).

---

## 🌟 Features & Highlights

### 1. Wear OS Housing & Launcher
- **Menu-Driven Selection**: Smooth rotary-scrollable list built for round watch faces (`ScalingLazyColumn`), allowing easy switching between visualizers.
- **Dynamic Visualiser Registry**: Extensible architecture (`VisualiserRegistry.kt`) designed to host multiple upcoming visualizers (Fiber Network, Mempool Particle Collider, Epoch Celestial Clock).
- **Pixel Watch 3 Hardware Integration**:
  - **Rotary Crown / Digital Crown Navigation**: Turning the physical watch crown rotates the 3D laboratory camera and zooms with smooth inertia.
  - **Haptic Engine**: Haptic feedback impulses on new block arrivals, rotary encoder clicks, and cell specimen interactions.
  - **AMOLED Dark Optimization**: Deep `#000000` blacks for battery efficiency on wearable displays.
  - **Screen Wake Lock**: Optional "Keep Screen On" toggle in settings to observe chain activity without display timeout.
- **Embedded & Self-Contained**: Visualizers are bundled directly inside `app/src/main/assets/visualisers/` for instant offline loading and zero-latency execution.

---

### 2. Flagship Visualizer: CKB Cell Laboratory
The live CKB blockchain visualizer runs as a mobile-first, self-contained Three.js 3D application (`index.html`).

#### Correct CKB Cell Model Implementation:
- **UTXO Cell Extraction**:
  $$\text{newCellCount} = \sum_{\text{tx} \in \text{block.transactions}} \text{tx.outputs.length}$$
  Includes all transactions (including the cellbase transaction).
- **Cell Identity**: Identified strictly by **OutPoint** (`txHash:outputIndex`).
- **Capacity**: Hex Shannons parsed and converted accurately ($1\text{ CKB} = 100,000,000\text{ Shannons}$).
- **Deterministic Biological Organisms**:
  - Microscopic cell/germ-like organisms materialize deterministically from `txHash + outputIndex`.
  - Translucent organic membrane with pulsating nucleus and soft cilia.
  - Capacity scales organism volume logarithmically.
  - Type Script presence manifests as an outer glowing organelle ring.
  - Data payload ($>0$ bytes) adds internal cytoplasmic granules.
- **The Glass Beaker & Liquid of Knowledge**:
  - Physically based transparent glass beaker with refractive Fresnel materials and measurement graduations.
  - Filled ~60% with swirling, translucent, luminescent "Liquid of Knowledge".
  - Internal Brownian motion and vortex particles.
- **Signature Absorption Choreography**:
  1. *Detection*: Laboratory instruments pulse, announcement banner appears with exact new Cell count.
  2. *Materialization*: Cells float in front of the camera for observation.
  3. *Swarm*: Liquid of Knowledge surges in luminescence; cells form a spiral swarm converging on the beaker rim.
  4. *Dissolution*: Cells enter the beaker, trigger liquid surface ripples, and dissolve into glowing particles.
  5. *Recycling*: Transient geometries and materials are disposed cleanly with zero GPU memory leaks.
- **Interactive Specimen Inspection**: Tap any cell or organism to inspect OutPoint, shortened transaction hash, capacity in CKB, Type Script status, and data payload, with one-tap copy.
- **Audio Synthesizer**: Optional lab ambient drone, block chimes, and dissolution fizz synthesized via Web Audio API (no external audio assets required).

---

## 📁 Project Structure

```text
watch-visualiser/
├── index.html                   # Standalone self-contained HTML/Three.js visualizer
├── server.py                    # Local development preview server (Python)
├── build.gradle.kts             # Root Gradle build script
├── settings.gradle.kts          # Gradle project configuration
├── gradle.properties            # JVM & AndroidX properties
├── gradlew                      # Gradle wrapper script
├── app/
│   ├── build.gradle.kts         # Wear OS application module (Compose + WebKit)
│   └── src/main/
│       ├── AndroidManifest.xml  # Wear OS manifest, permissions & activities
│       ├── assets/
│       │   └── visualisers/
│       │       └── ckb-cell-lab/
│       │           └── index.html # Bundled watch visualizer
│       ├── java/network/nervos/watchvisualiser/
│       │   ├── MainActivity.kt        # Menu-driven Wear OS Compose launcher
│       │   ├── VisualiserActivity.kt  # Hardware-accelerated WebView container
│       │   ├── SettingsActivity.kt    # Node RPC and haptic configuration
│       │   ├── bridge/
│       │   │   └── WatchBridge.kt     # Native Android <-> JavaScript bridge
│       │   ├── data/
│       │   │   ├── VisualiserRegistry.kt # Registry of visualizer modules
│       │   │   └── AppPreferences.kt     # SharedPreferences store
│       │   └── model/
│       │       └── VisualiserInfo.kt     # Visualizer item data model
│       └── res/                       # Drawables, colors, strings, mipmap
```

---

## 🚀 Running & Previewing

### A. Quick Browser Preview (Desktop / Mobile / Watch Browser)
Run the built-in development server:
```bash
python3 server.py
```
Open [http://localhost:8080/](http://localhost:8080/) in any modern web browser.
- **Desktop/Mobile**: Click and drag to orbit, scroll or pinch to zoom.
- **Simulation**: Click the settings gear and click **"TEST BLOCK"** to simulate a live CKB block with authentic UTXO organisms at any time!
- **Circular Screen Test**: Resize window to square ($450 \times 450$) or enable Chrome DevTools device mode.

### B. Building & Deploying to Pixel Watch 3
1. Connect Pixel Watch 3 via ADB (Wi-Fi debugging or USB cradle):
   ```bash
   adb connect <WATCH_IP>:<PORT>
   adb devices
   ```
2. Build the Wear OS debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
3. Install onto Pixel Watch 3:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. Launch **CKB Visualisers** from the watch app drawer and select **Cell Laboratory**.
   - Rotate the physical watch crown to smoothly spin around the glass beaker!
   - Tap any cell to view specimen metadata on the watch face.
