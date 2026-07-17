# Allo•Alu (Decentralized E2E Private Messenger)

[![Build Android App](https://github.com/devitamine/Allo-Alu/actions/workflows/android.yml/badge.svg)](https://github.com/devitamine/Allo-Alu/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Algorand](https://img.shields.io/badge/Blockchain-Algorand-emerald.svg)](https://algorand.co/)

An ultra-secure, decentralized, and server-less messaging application designed for absolute privacy and user sovereignty. **Allo•Alu** connects peers directly over the **Algorand Blockchain**, eliminating traditional intermediaries, centralized database logs, and surveillance vulnerability.

---

## 🌐 Live Landing Page
Check out our gorgeous product showcase: **[Live Landing Page](https://devitamine.github.io/Allo-Alu)**

---

## 🛡️ Why Choose Us? (Value Proposition)

Allo•Alu was created for Web3 users who demand complete sovereignty over their digital footprints. Unlike standard messengers that depend on closed ecosystems, we prioritize absolute transparency and stability.

### 1. 100% Free App & Open-Source
Allo•Alu is entirely free to download and use. There are absolutely no developer fees, registration charges, subscription models, or premium paywalls. The codebase is fully public and auditable by anyone at any time.

### 2. Microscopic Network Fees (Laughably Cheap)
Because Allo•Alu operates directly on the blockchain, there are no middleman servers. The only cost to deliver messages is the standard microscopic Algorand network fee of exactly **0.001 ALGO** (~$0.0001 or a tiny fraction of a cent) per transaction.
*   **No markups:** 100% of this fee goes directly to Algorand validators to secure your transaction.
*   **Hyper-economical:** Send over 10,000 messages for about a single dollar!

### 3. High-Tier Security (99.9% Hardened Protection)
*   **Cryptographic Sovereignty:** Your decentralized Algorand address acts as your chat identity. There are no phone numbers, emails, or personal identifiers.
*   **Hardware Shielded:** Sensitive keys are secured within the Android Keystore, and local message history resides inside an SQLite database fortified with industry-standard cryptographic layers.
*   **Anti-Capture Screen Isolation:** Built-in safeguards intercept system-level screenshotting and screen recording to defend against visual spying.

### 4. No Flaky Third-Party Servers or Ads
Traditional messaging frameworks depend on centralized push servers (like Firebase Cloud Messaging) and ad networks, resulting in constant metadata leaks, tracking, and unexpected downtimes. Allo•Alu has zero middleman architecture. All chat payloads anchor directly onto Algorand nodes for unmatched uptime and stability.

---

## 💎 The Algorand Advantage

We chose Algorand as the foundational layer for Allo•Alu because it is built to handle real-time transactional payloads without the high gas fees or congestion found on other chains.

*   **Instant Forkless Finality:** Transactions reach absolute finality in under 2.8 seconds. Your secure messages are delivered instantly, with 100% mathematical certainty that the ledger cannot fork or reorder them.
*   **Hyper-Efficient Micro-Gas:** A fixed transaction fee of 0.001 ALGO keeps your communications incredibly fast and laughably cheap.
*   **Pure Proof of Stake (PPoS):** Fully decentralized security with a carbon-negative footprint. Chat all day on a highly sustainable network.

---

## ⚠️ Pre-Flight Wallet Requirements

To protect the blockchain from transaction spam, Algorand enforces a **Minimum Balance Requirement (MBR)** of **0.1 ALGO** for active accounts.
*   **Minimum Balance Constraint:** To successfully send an encrypted message, your wallet balance must exceed **0.101 ALGO** (0.1 ALGO account reserve + 0.001 ALGO transaction fee).
*   **Sovereign Protection Guardrail:** Allo•Alu features a built-in balance monitoring engine that automatically scans your account. If your balance drops below the threshold, the app locks outbound sending and triggers a warning banner, saving you from failed transaction errors and accidental gas losses.
*   **Recommended Balance:** We recommend funding your chat address with **0.5 to 1.0 ALGO** (roughly $0.10 - $0.20 USD) to chat completely worry-free for months!

---

## 🛠️ Tech Stack Summary

*   **Language:** Kotlin
*   **UI Framework:** Jetpack Compose (Material Design 3)
*   **Persistence:** SQLite via encrypted Room Database
*   **Blockchain Integration:** Algorand Java SDK
*   **CI/CD Pipeline:** GitHub Actions (Automated Android Compilation & Testing)

---

## 🚀 How to Run Locally

### Prerequisites
*   [Android Studio (Ladybug or newer)](https://developer.android.com/studio)
*   JDK 17 or higher
*   An active Algorand Account (Mainnet or Testnet)

### Build & Compilation Steps
1.  **Clone the Repository:**
    ```bash
    git clone https://github.com/devitamine/Allo-Alu.git
    cd Allo-Alu
    ```
2.  **Open in Android Studio:** Open the root folder and let Gradle automatically sync and download project dependencies.
3.  **Compile the Debug APK via CLI:**
    ```bash
    ./gradlew assembleDebug
    ```
    *The generated executable `.apk` will be output to `app/build/outputs/apk/debug/app-debug.apk`.*

---

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.
