# Allo•Alu (E2E Encrypted Private Messenger)

[![Build Android App](https://github.com/your-username/allochat/actions/workflows/android.yml/badge.svg)](https://github.com/your-username/allochat/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Algorand](https://img.shields.io/badge/Blockchain-Algorand-blue.svg)](https://algorand.co/)

An enterprise-grade, highly secure, decentralized private messaging application built for Android. **Allo•Alu** leverages the blazing-fast, forkless, and low-cost **Algorand Blockchain** to establish secure, end-to-end (E2E) encrypted channels between wallets, ensuring complete user sovereignty, cryptographic data protection, and absolute privacy.

---

## 🌟 Key Features

*   **Decentralized Secure Identity**: Your identity is your Algorand wallet address. No phone numbers, no email addresses, and no centralized databases.
*   **End-to-End Cryptographic Encryption**: Every message is secured using advanced public-key cryptography prior to being anchored on the blockchain. Only the recipient can decrypt your communication.
*   **Real-time Ledger State**: Check account balances directly from the main dashboard.
*   **Intelligent Balance Safeguard**: Includes automated pre-flight balance checks that warn you of low balances (< 0.101 ALGO) and temporarily disable transmission to prevent message drops and unnecessary fees.
*   **Privacy-Optimized Android Architecture**:
    *   **Anti-Screen Capture Protection**: Prevents system-level screenshots and screen recordings to defend against physical and spyware spying.
    *   **In-App QR Generator**: Seamlessly present your wallet address as a secure QR code directly from your avatar.
    *   **Search-On-Demand**: Clean, adaptive contact list that displays a search filter only when your contact directory scales (scroll threshold).
*   **Offline-First & Local Persistence**: Contacts and local chat history are secured using an encrypted local SQLite/Room database.

---

## ⚡ Why Algorand?

Our architecture utilizes the unique capabilities of the **Algorand Blockchain** to power next-generation communication:

1.  **Pure Proof of Stake (PPoS)**: Absolute decentralization with zero fork risk, ensuring your secure communication channels are never split or reorganized.
2.  **Instant Finality & Sub-second Block Times**: Messages are compiled and permanently anchored onto the ledger within seconds.
3.  **Microscopic Transaction Fees**: Each message anchor consumes only fractions of a penny (0.001 ALGO), offering cost-effective Web3 messaging.
4.  **Carbon Negative**: Eco-friendly blockchain operations aligning with modern sustainability standards.

---

## 📸 User Interface Preview

*   **Cosmic Slate Visual Theme**: Designed with custom color schemes, deep contrast accents, and Material 3 design parameters.
*   **Intelligent Warning Banners**: Instant user feedback when ALGO balance drops below critical levels required for gas/minimum balance requirements.
*   **Type-Safe Navigation**: Completely modern Jetpack Compose interface utilizing Android's latest navigation framework.

---

## 🛠️ Project Structure

```text
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/             # Kotlin Source Code
│   │   │   │   ├── MainActivity.kt           # Jetpack Compose UI Views & Router
│   │   │   │   └── ui/ChatViewModel.kt       # State Engine, Algorand Client Core
│   │   │   └── AndroidManifest.xml           # App Permissions and Configuration
│   │   └── build.gradle.kts                  # Module dependencies & compilation SDKs
├── gradle/                                   # Gradle Wrapper & Version Catalogs
├── build.gradle.kts                          # Project level build scripts
└── settings.gradle.kts                       # Project and sub-project registry
```

---

## 🚀 Getting Started (Self-Compiling)

### Prerequisites
*   Android Studio Ladybug (or newer)
*   JDK 17 or higher
*   An active Algorand Account (on Mainnet or Testnet)

### Clone & Build
1.  **Clone the Repository**:
    ```bash
    git clone https://github.com/YOUR_USERNAME/allochat.git
    cd allochat
    ```
2.  **Open in Android Studio** and let Gradle synchronize dependencies.
3.  **Run / Build**:
    *   To build the debug APK via CLI:
        ```bash
        ./gradlew assembleDebug
        ```
    *   The generated APK will be available in: `app/build/outputs/apk/debug/app-debug.apk`

---

## 🤖 Automated CI/CD (GitHub Actions)

This repository is pre-configured with **GitHub Actions**. Every time you push a commit or tag to the `main` branch, the workflow will automatically:
1.  Verify lint regulations and syntax.
2.  Compile a secure, debuggable Android Application Package (APK).
3.  Export the compiled binary (`.apk`) as an accessible download artifact in your GitHub Actions run!

See the configuration in `.github/workflows/android.yml`.

---

## 🔒 Security & Compliance Commitment

This product is created with strict adherence to **User Sovereignty** and **Cryptographic Integrity**. All communication schemas are fully open-source, allowing comprehensive auditing. The application:
*   Does not track telemetry, geolocation, or personal identifiers.
*   Implements the official Algorand crypto libraries to compile transaction payloads directly on-device.
*   Protects private keys inside secure local app isolation.

---

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.
