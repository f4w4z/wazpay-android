# WazPay: Offline UPI USSD Payment Automation for Android

![Android](https://img.shields.io/badge/Platform-Android-brightgreen)
![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue)
![Architecture](https://img.shields.io/badge/Architecture-Clean--Modular-orange)
![License](https://img.shields.io/badge/License-GPLv3-blue)

WazPay is a specialized Android application designed to automate offline UPI payment flows using the USSD *99# infrastructure. By leveraging Android Accessibility Services, the app provides a hands free experience for mobile banking without internet connectivity. This solution is ideal for users in low connectivity areas or those seeking a reliable backup to data dependent payment apps.

## Benefits of Offline UPI Payments

Offline banking via USSD offers several critical advantages for modern users:

*   **Zero Internet Dependency:** Complete transactions in areas with no data coverage, such as rural regions, underground basements, or during network outages.
*   **Cost Efficiency:** Since USSD uses the signaling channel of the GSM network, it does not require an active data plan or high speed internet.
*   **Universal Accessibility:** Works on any SIM card registered with a bank for *99# services, providing a vital bridge for financial inclusion.
*   **Emergency Reliability:** When mobile apps fail due to server issues or poor 4G/5G signals, USSD remains a stable and resilient alternative for sending money.
*   **Battery Preservation:** Offline transactions consume significantly less power than data heavy payment applications.

## Key Features

*   **Automated *99# Navigation:** Automatically handles complex multi step menus including PIN entry, amount input, and recipient selection.
*   **Intelligent USSD Parser:** Uses advanced regex logic to identify screen states and extract transaction reference IDs in real time.
*   **Integrated QR Scanner:** Uses ML Kit and CameraX to scan UPI QR codes and extract payment details for immediate offline processing.
*   **Manufacturer Compatibility:** Built with a multi strategy button detection engine to ensure functionality across Samsung, Xiaomi, Pixel, and other Android skins.
*   **Modular Clean Architecture:** High performance codebase with a strict separation between UI components and core automation logic.

## Advantages of the New Architecture

The refactored modular structure provides significant technical benefits:

*   **Enhanced Reliability:** The multi strategy button detection logic solves the problem of manufacturer specific USSD dialog layouts.
*   **Real Time Adaptation:** The dynamic parser reads screen text to find menu options, making the app resilient to bank side menu changes.
*   **Scalable State Management:** Centralized PreferenceManager ensures all transaction data is handled securely and consistently.
*   **Optimized Performance:** Separating business logic from the UI reduces memory overhead and improves response times during automation.

## Project Structure

*   app/src/main/java/com/zeny/wazpay/MainActivity.kt: Core navigation controller and entry point.
*   app/src/main/java/com/zeny/wazpay/UssdService.kt: The Accessibility Service worker that executes the automation.
*   app/src/main/java/com/zeny/wazpay/logic/: Business rules including UssdParser, UssdState, and PreferenceManager.
*   app/src/main/java/com/zeny/wazpay/ui/screens/: Modular Jetpack Compose UI components.

## Technical Implementation

1.  **Accessibility Service:** The UssdService monitors window events to detect system dialogs and interacts with them using programmatic clicks and text entry.
2.  **Pattern Matching:** The logic layer identifies specific prompts (Welcome, IFSC, Pin, Amount) by analyzing screen text patterns.
3.  **Security:** All sensitive data like UPI PINs are handled within the app's secure private storage and only used during active automation sessions.

## Getting Started

1.  Clone the WazPay repository.
2.  Open the project in Android Studio and build the APK.
3.  Install the app and enable the Accessibility Service in Android Settings.
4.  Configure your Bank IFSC and other details in the App Settings.
5.  Dial *99# or scan a QR code to begin an offline transaction.

## Permissions

*   android.permission.CALL_PHONE: Required to initiate USSD dialer codes.
*   android.permission.READ_PHONE_STATE: Required for SIM selection logic.
*   android.permission.CAMERA: Required for UPI QR code scanning.
*   android.permission.BIND_ACCESSIBILITY_SERVICE: Mandatory to interact with USSD menus.

## License

WazPay is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [LICENSE](LICENSE) file for more details.
