# SAS OFFLINE CONNECT

Early Android prototype for local offline text messaging.

Features in this first test build:
- Nearby Connections discovery and advertising.
- Automatic connection to other SAS app devices.
- Basic text chat.
- Basic app-level forwarding with duplicate suppression and a three-hop limit.

The Nearby Connections API uses Bluetooth/BLE/Wi-Fi to exchange data locally without requiring internet. Both phones must have this app installed, required permissions granted, and radios enabled.

Limitations: this is an early prototype, not a finished WhatsApp replacement. Discovery is active while the app is open; locked-screen/background operation, reliable store-and-forward, contact verification, group UI, media, voice, and production security review are not implemented. Test only with trusted devices. The prototype automatically accepts devices advertising this app's SAS service.

GitHub Actions builds a debug APK and uploads it as an artifact.
