# Setup

Två delar: (1) så bygger och kör du projektet lokalt – helt utan nycklar, och (2) de manuella steg som bara ägaren kan göra.

## 1. Lokal utveckling (inga nycklar behövs)

**Krav:** JDK 17+ (Android Studios inbyggda JBR räcker), Android SDK med plattform 37 och 36, Node 22+ för Firebase-emulatorerna.

```bash
./gradlew :composeApp:assembleDemoDebug     # bygger demo-appen
./gradlew :composeApp:installDemoDebug      # installerar på emulator/enhet
```

### Demo-flavorn och Firebase Local Emulator Suite
`demo` pratar med Firebase-emulatorerna (projekt-id `demo-pusslet` – "demo-"-prefixet gör att emulatorerna inte kräver något
riktigt projekt) och fylls med påhittade "Familjen Lind". Appen fungerar även utan emulatorer (allt är lokalt först) – synk och
inloggning väntar då tills emulatorn finns.

```bash
npm install -g firebase-tools               # en gång
cd functions && npm ci && cd ..
firebase emulators:start --project demo-pusslet
```
Android-emulatorn når datorn via `10.0.2.2`. För en fysisk enhet: sätt `pusslet.emulatorHost=<datorns IP>` i `local.properties`.

### Android-emulator
Skapa en AVD i Android Studio (Device Manager → Pixel 8, API 36, Google APIs-avbild). Kör sedan `installDemoDebug`.

### Testa synk mellan två enheter
Två Android-emulatorer kräver mycket minne (>12 GB RAM rekommenderas). Med en emulator kan
`functions/scripts/second-device.mjs` spela "Johans telefon": den går med i demofamiljen genom en riktig inbjudan
(säkerhetsreglerna gäller precis som för en enhet) och skriver händelser.

```bash
cd functions
node scripts/second-device.mjs join                         # efter att demo-appen startats en gång
node scripts/second-device.mjs add "Tandläkare Simon" 14:00 15:00
node scripts/second-device.mjs rename <eventId> "Ny titel"  # prova med appen i flygplansläge
```
Regeltesterna (`npm test`) använder ett eget projekt-id (`demo-pusslet-rules`), så de rensar aldrig demoappens data även om
de körs mot samma emulator.

## 2. Manuella steg för ägaren

> Allt i appen är byggt så att detta kan göras sist. Inget av stegen behövs för demo-flavorn.

### 2.1 Firebase-projekt (EU)
1. Skapa ett Firebase-projekt (ett för `dev`, ett för `prod` rekommenderas).
2. Firestore: välj **EU-multiregion `eur3`** (Europa) eller en EU-region, t.ex. `europe-north1` (Finland). Platsen kan inte ändras senare.
3. Lägg till Android-appar med paketnamn `se.pusslet.app.dev` (dev) och `se.pusslet.app` (prod).
   **Bestäm paketnamnet innan första Play-uppladdningen – det kan aldrig ändras.**
4. Ladda ner `google-services.json` till `composeApp/src/dev/` respektive `composeApp/src/prod/` (filerna är gitignorerade).

### 2.2 Tjänster
1. Authentication: aktivera E-post/lösenord, Google och Anonym.
2. Cloud Messaging: aktiveras automatiskt.
3. App Check: registrera Play Integrity för Android-apparna, aktivera enforcement för Firestore och Functions efter testperiod.
4. Crashlytics: aktivera (appen samlar bara in efter samtycke).

### 2.3 Blaze-planen
Cloud Functions kräver **Blaze** (betala per användning). Sätt en **budgetvarning** (t.ex. 50 kr/månad) i Google Cloud Billing.

### 2.4 Deploy
```bash
firebase use --add                       # välj projektet
firebase deploy --only firestore:rules,firestore:indexes,functions,hosting
```
Hosting serverar familjens kalenderlänkar (`https://<din-domän>/feed/<token>.ics` → funktionen `calendarFeed`).
Standarddomänen är `<projekt-id>.web.app`; sätt samma värde som `linkHost` (se `BackendConfig`) om du byter domän.

### 2.5 SHA-fingeravtryck
Lägg till SHA-1 och SHA-256 för debug- och release-nyckeln i Firebase (krävs för Google Sign-In och App Links):
```bash
./gradlew :composeApp:signingReport
```

### 2.6 Kontaktuppgift för öppna datakällor
Sätt `AppConfig.CONTACT` (webbplats eller e-post) i `shared/core/src/commonMain/kotlin/se/pusslet/core/AppConfig.kt`.
Open Food Facts kräver att appen identifierar sig med kontaktuppgift i User-Agent.
