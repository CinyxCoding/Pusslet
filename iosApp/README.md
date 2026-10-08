# iosApp

SwiftUI-skal runt det delade Compose-gränssnittet (`shared/app` → framework `PussletShared`).

iOS är inte lanseringsmål ännu. Det som krävs innan iOS kan släppas:
- `actual`-implementationer som i dag är stubbar: Firestore-adapter (`RemoteStore`), notiser, kalenderintegration (EventKit),
  streckkodsskanning (AVFoundation/VisionKit), widgets (WidgetKit).
- Firebase iOS SDK via Swift Package Manager och `GoogleService-Info.plist`.

Bygga (endast macOS):
```bash
brew install xcodegen
cd iosApp && xcodegen generate && open Pusslet.xcodeproj
```
CI kompilerar och länkar frameworket för simulatorn i jobbet `ios` (`.github/workflows/ci.yml`).
