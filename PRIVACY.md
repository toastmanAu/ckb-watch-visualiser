# Privacy Policy for Nervos CKB Watch Visualisers

**Application:** Nervos CKB Watch Visualisers  
**Package Name:** `network.nervos.watchvisualiser`  
**Effective Date:** October 27, 2026  
**Hosted URL:** [https://toastmanau.github.io/ckb-watch-visualiser/privacy.html](https://toastmanau.github.io/ckb-watch-visualiser/privacy.html)

---

## 1. Overview
**Nervos CKB Watch Visualisers** is an open-source, read-only educational and contemplative visualizer application designed for Wear OS smartwatches and modern web browsers.

**We do not collect, store, transmit, process, or sell any personal data, device identifiers, or user information of any kind.**

---

## 2. Data Collection and Usage
- **No Personal Information:** We do not collect names, email addresses, phone numbers, locations, or contact information.
- **No Account Required:** The application does not require user registration, logins, passwords, or authentication.
- **No Financial Data or Wallets:** The application is purely a read-only visualizer. It does not contain wallet features, does not manage private keys, seed phrases, or credentials, and cannot initiate or sign transactions.
- **No Analytics or Tracking:** The application contains no tracking libraries, telemetry SDKs, crash analytics SDKs, advertising frameworks, or cookies.

---

## 3. Network Communications and Blockchain Data
To display real-time blockchain activity, the application communicates directly with public Nervos Network (CKB) JSON-RPC and WebSocket endpoints:
- All network requests are strictly **read-only** queries for publicly available blockchain data (such as block height, epoch progress, and block transactions).
- No user identifiers or personal data are transmitted with these queries.
- Users can configure custom RPC endpoint URLs in the settings menu at any time.

---

## 4. Device Permissions
The Wear OS application requests only the minimal permissions necessary for its visual display and user experience:
- `android.permission.INTERNET`: Required solely to query public CKB JSON-RPC and WebSocket nodes for live block information.
- `android.permission.ACCESS_NETWORK_STATE`: Used to detect internet availability and handle node reconnection gracefully.
- `android.permission.VIBRATE`: Used exclusively to provide subtle tactile haptic feedback for physical rotary crown rotations and block arrival events.
- `android.permission.WAKE_LOCK`: Used only if the user explicitly enables the "Keep Screen On" setting while actively observing a live visualization.

---

## 5. Children's Privacy
The application does not collect any personal information from anyone, including children under the age of 13.

---

## 6. Security and Open Source Verification
The complete source code of **Nervos CKB Watch Visualisers** is publicly available for independent audit and verification under the MIT License on GitHub:  
[https://github.com/toastmanAu/ckb-watch-visualiser](https://github.com/toastmanAu/ckb-watch-visualiser)

---

## 7. Contact
If you have questions or concerns regarding this privacy policy, you can open an issue on GitHub:  
[https://github.com/toastmanAu/ckb-watch-visualiser/issues](https://github.com/toastmanAu/ckb-watch-visualiser/issues)
