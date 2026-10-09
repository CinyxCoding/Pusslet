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

### 2.0 Få in koden på `main`
Arbetet görs på en egen gren och pushas dit. Öppna en pull request till `main` på GitHub (Pull requests → New → välj
grenen): då kör CI alla jobb, även iOS-länkningen på macOS (avsnitt 6). Slå ihop när allt är grönt och hämta `main`
lokalt (`git checkout main && git pull`).

### 2.1 Firebase-projekt (EU)
1. Skapa ett Firebase-projekt (ett för `dev`, ett för `prod` rekommenderas).
2. Firestore: välj **`europe-north1` (Finland)**. Funktionerna körs där (`REGION` i `functions/src/index.ts`) och
   Firestore-triggrarna måste ligga i databasens region. Platsen kan inte ändras senare.
3. Lägg till Android-appar med paketnamn `se.pusslet.app.dev` (dev) och `se.pusslet.app` (prod).
   **Bestäm paketnamnet innan första Play-uppladdningen – det kan aldrig ändras.**
4. Gör 2.2 punkt 1 och 2.5 (Google-inloggning och SHA-1) **innan** du laddar ner `google-services.json` – annars saknas
   OAuth-klienten som Google-inloggningen behöver i filen. Ladda sedan ner den till `composeApp/src/dev/` respektive
   `composeApp/src/prod/` (filerna är gitignorerade).
5. Sätt länkdomänen i `local.properties`: `pusslet.linkHost=<projekt-id>.web.app` (inbjudningslänkar, App Links och
   kalenderlänkar; standardvärdet `pusslet.web.app` är inte ditt).

### 2.2 Tjänster
1. Authentication: aktivera E-post/lösenord, Google och Anonym.
2. Cloud Messaging: aktiveras automatiskt. Push skickas av funktionerna `pushHandoffRequest`, `pushHandoffAnswer`,
   `pushShoppingItem` och `pushEventChange` (följer med i deploy nedan); appen registrerar sin token när man loggat in.
   Inga nycklar behövs i appen – `google-services.json` räcker.
3. App Check (ADR-031): appen skickar App Check-token till Firestore och Functions (dev och prod; demo-flavorn mot
   emulatorerna gör det inte).
   - Gå till **Build → App Check → Apps** och registrera Android-apparna med **Play Integrity** (SHA-256 som i 2.5).
   - Debugbyggen använder en debug-token: starta `devDebug` en gång och leta upp raden från `DebugAppCheckProvider`
     i Logcat ("Enter this debug secret …"). Lägg in den under appens **⋮ → Manage debug tokens**. Gör så för varje
     telefon/emulator du testar på.
   - Låt enforcement vara **av** tills App Check-sidan visar att nästan alla anrop är verifierade (några dagar efter att
     testarna börjat). Slå sedan på enforcement för Cloud Firestore och Cloud Functions. Release-byggen som inte kommer
     från Play (t.ex. en sidoinstallerad APK) nekas då.
4. Kraschrapporter: ingen SDK i appen (ADR-026) – se **Android vitals** i Play Console (krascher och ANR från användare
   som delar diagnostik med Google i telefonens inställningar).

### 2.3 Blaze-planen
Cloud Functions kräver **Blaze** (betala per användning). Sätt en **budgetvarning** (t.ex. 50 kr/månad) i Google Cloud Billing.
På gratisplanen (Spark) fungerar inloggning, synk och regler; det som kräver funktionerna är push-notiser, familjens
kalenderlänk och "Radera familjen/mitt konto". Man kan börja testa på Spark och byta när push ska provas.

### 2.4 Deploy
```bash
firebase login
firebase use --add                       # välj projektet, ge det aliaset dev (resp. prod)
cd functions && npm ci && cd ..
firebase deploy --only firestore:rules,firestore:indexes   # räcker på Spark
firebase deploy --only functions,hosting                   # kräver Blaze
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

## 3. Kontroller på riktig telefon (före lansering)
Saker som emulatorn inte kan visa:
1. **Telefonens kalender:** Inställningar → Kalendrar → "Lägg familjens händelser i" → en egen Pusslet-kalender. Öppna
   Google Kalender (inloggad) och kontrollera att förskola/skola syns som återkommande och att en ändring i Pusslet slår
   igenom inom någon minut.
2. **Push med två telefoner** (dev-flavorn mot ditt Firebase-projekt): Sara frågar "Kan någon hämta?" → Johans telefon
   får notisen med "Jag tar det" även när appen är stängd; svaret når Sara. Lägg till en vara medan den andra har tryckt
   "Jag handlar nu".
3. **Widgets:** lägg Idag och Inköpslista på hemskärmen; bocka av, lägg till med +, och se att Idag-widgeten byter
   "nästa händelse" när tiden passerat.
4. **Dela en bild** från Galleriet till Pusslet → "Till en händelse" → bilden syns i händelsen och öppnas i bildvisaren.

## 4. Releasebygge och signering
Releasebyggen krymps och optimeras med R8 (`isMinifyEnabled`, `isShrinkResources`, regler i `composeApp/proguard-rules.pro`).
Play App Signing rekommenderas: Google håller appens signeringsnyckel och du signerar bara uppladdningar med en
**uppladdningsnyckel**.

1. Skapa uppladdningsnyckeln en gång (förvara den och lösenorden i en lösenordshanterare, aldrig i repot):
   ```bash
   keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Skapa `keystore.properties` i repots rot (gitignorerad):
   ```properties
   storeFile=upload.jks
   storePassword=…
   keyAlias=upload
   keyPassword=…
   ```
   I CI används i stället miljövariablerna `PUSSLET_KEYSTORE`, `PUSSLET_KEYSTORE_PASSWORD`, `PUSSLET_KEY_ALIAS` och
   `PUSSLET_KEY_PASSWORD` (nyckelfilen läggs dit från en hemlighet).
3. Lägg `google-services.json` för prod i `composeApp/src/prod/` och bygg:
   ```bash
   ./gradlew :composeApp:bundleProdRelease
   ```
   AAB-filen hamnar i `composeApp/build/outputs/bundle/prodRelease/`. Utan nyckel byggs den osignerad (fungerar för att
   kontrollera R8, men Play tar inte emot den).
4. Prova den krympta appen på en telefon/emulator utan uppladdningsnyckeln:
   `./gradlew :composeApp:assembleDemoRelease -Ppusslet.debugSignRelease=true` och installera
   `composeApp-demo-release.apk`. Kontrollera start, alla flikar, snabbinmatning, widgets och "Dela bild".
5. Höj `app-versionCode` (och `app-versionName`) i `gradle/libs.versions.toml` inför varje uppladdning.

## 5. Lanseringschecklista (Google Play)
**Konto och app**
- [ ] Utvecklarkonto skapat och identitet verifierad. **Nya personliga konton måste köra ett stängt test med minst 12
      testare som är med i 14 dagar i följd** innan du kan ansöka om produktionsåtkomst – planera in det (familj, vänner,
      föräldragruppen) och be testarna faktiskt använda appen under perioden.
- [ ] Paketnamnet bestämt (`se.pusslet.app`) – kan aldrig ändras efter första uppladdningen.
- [ ] Play App Signing aktiverat, uppladdningsnyckel skapad (avsnitt 4).

**Backend**
- [ ] Firebase-projekt i EU, regler, index, funktioner och hosting deployade (avsnitt 2.4), budgetvarning satt.
- [ ] App Check med Play Integrity aktiverat och enforcement påslaget efter några dagars övervakning.
- [ ] SHA-1/SHA-256 för uppladdningsnyckeln **och** Play-signeringsnyckeln inlagda i Firebase (Google-inloggning och App Links).
- [ ] `assetlinks.json` för App Links publicerad på `linkHost` med Play-signeringsnyckelns fingeravtryck
      (lägg filen i `hosting/.well-known/assetlinks.json` och deploya hosting).
- [ ] `AppConfig.CONTACT` satt (avsnitt 2.6).

**Butikssidan** (`docs/STORE_LISTING.md`)
- [ ] Appnamn, kort och lång beskrivning på svenska och engelska.
- [ ] Ikon 512×512, funktionsbilder 1024×500 och skärmdumpar 1080×1920 på svenska och engelska finns i `docs/store/`.
      Gör om dem efter ändringar i appen:
      `PUSSLET_STORE_IMAGES=1 ./gradlew :composeApp:recordRoborazziDemoDebug --tests "*StoreImages*"`.
- [ ] Integritetspolicyn: fyll i `[…]` i `docs/PRIVACY.md` (namn, adress, e-post, datum) och deploya hosting – sidan
      `https://<linkHost>/privacy/` byggs då av sig själv (inte så länge något `[…]` finns kvar). Ange adressen i Play
      Console; appen länkar dit under "Integritet och dina data".
- [ ] Kategori Föräldraskap och kontaktuppgifter.

**App content i Play Console**
- [ ] Data safety ifyllt enligt `docs/PLAY_DATA_SAFETY.md`.
- [ ] Målgrupp: vuxna (18+); appen riktar sig inte till barn.
- [ ] Innehållsklassning (IARC-enkäten): inget våld, ingen användarkommunikation utanför familjen.
- [ ] Reklam: nej. Behörigheter förklarade (kalender, kamera, ungefärlig plats, notiser – alla frågas i sammanhang).
- [ ] Kontouppgifter för granskning: ett testkonto med en ifylld familj (eller instruktion om att demo-läget finns).

**Kvalitet**
- [ ] `./gradlew build` grönt och `cd functions && npm test` grönt.
- [ ] Kontrollerna på riktig telefon (avsnitt 3) gjorda med prod-flavorn.
- [ ] Internt test → stängt test (12+ testare, 14 dagar) → produktion med stegvis utrullning (t.ex. 20 %).
- [ ] Android vitals bevakas första veckan (krascher, ANR, långsam start).

## 6. iOS-bygget
iOS kan bara byggas på en Mac. CI-jobbet "iOS – compile shared framework" (`.github/workflows/ci.yml`) länkar det delade
ramverket och kör kärntesterna på macOS vid varje pull request och push till `main`. Se resultatet under fliken Actions
på GitHub. macOS-minuter räknas tio gånger på privata repon (gratiskvoten räcker för enstaka körningar).

