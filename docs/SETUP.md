# Setup

Två delar: (1) så bygger och kör du projektet lokalt – helt utan nycklar, och (2) de manuella steg som bara ägaren kan göra.

## 1. Lokal utveckling (inga nycklar behövs)

**Krav:** JDK 17+ (Android Studios inbyggda JBR räcker), Android SDK med plattform 37 och 36, Node 24.12+ för Firebase-emulatorerna och testerna i `functions/` (Firebase-SDK:n där kräver det; själva funktionerna körs på Node 22 i molnet).

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

### Testa notiser
Notiserna planeras i appen och schemaläggs med WorkManager (ADR-036), så de kommer även när appen är stängd. Exempel i demo:
1. Inställningar → Notiser → slå på **Morgonkoll** (godkänn notiser) och sätt tiden ett par minuter fram.
2. Eller skapa en händelse som börjar om 15 minuter med påminnelse **10 min innan**.
3. Tryck på notisen: den öppnar händelsen (eller Idag för Morgon- och Kvällskollen).

WorkManager kan i strömsparläge komma några minuter sent; en notis som skulle ha kommit för mer än 15 minuter sedan visas inte.
En påminnelse vars tid redan har passerat när den sparas planeras inte alls, så ge testhändelsen marginal (15 minuter fram
och 10 min innan ger notisen om ca 5 minuter). Appen räknar i telefonens tidszon: emulatorer står ofta på GMT, så räkna
från emulatorns klocka (`adb shell date`), inte datorns.
Planerade notiser syns med `adb shell dumpsys jobscheduler | grep pusslet`, och med namn (t.ex. `notify:event:…:10`) med
`adb shell am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS -p se.pusslet.app.demo` och sedan
`adb logcat -d -s WM-DiagnosticsWrkr`. `am force-stop` tar bort appens notiser; stäng processen med `am kill` (appen i
bakgrunden) för att prova ett tryck från kallstart.

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

### Molnsessioner (Claude Code på webben)
Den molnmiljö som sessionen körs i behöver nå **`dl.google.com`** (Googles Maven-repo och Android SDK) – lägg till domänen
under nätverksinställningarna för miljön (Allowed domains). Utan den går bara `shared/core` att kompilera och testa, via ett
fristående JVM-projekt. Maven Central begränsar ibland antalet anrop (HTTP 429) från molnmiljön; en lokal cachande proxy
med back-off löser det under en session.

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
firebase deploy --only firestore:rules,firestore:indexes,functions
```
Familjens kalenderflöde (ADR-041) är funktionen `familyCalendarFeed`; appen bygger länken
`https://europe-north1-<projekt-id>.cloudfunctions.net/familyCalendarFeed/<token>.ics`, så den behöver ingen egen
konfiguration. Prova efter deploy: Inställningar → Kalendrar → ”Skapa länk”, öppna länken i en webbläsare (en `.ics` laddas
ner) och lägg till den i Google Kalender under ”Andra kalendrar → Från webbadress”. Google hämtar prenumerationer med
några timmars mellanrum.

Nattlig rensning (ADR-046): funktionen `purgeDeleted` körs 03:30 varje natt via Cloud Scheduler (aktiveras av sig själv
vid deploy på Blaze). Den tar bort innehållet i det som raderades för mer än 30 dagar sedan och markeringarna efter ett
år; var den slutade står i `_maintenance/purge`, som ingen klient kan läsa. Loggen visar bara antal.

Push mellan telefonerna (ADR-042) är triggers på Firestore och måste ligga i databasens region. Med en EU-region
(`europe-north1`) behövs inget; med multiregionen `eur3`, skriv `TRIGGER_REGION=europe-west1` i `functions/.env` före deploy
(`functions/.env.local` gäller bara emulatorerna). Push kommer bara i `dev`- och `prod`-flavorn. Prova med två telefoner i
samma familj: be om hämtning från den ena (händelsen → ”Kan någon hämta?”), notisen kommer på den andra; svara, och
svaret kommer tillbaka. I emulatorerna skickas inget, men det som skulle ha skickats syns i samlingen `_pushOutbox`.

### 2.5 SHA-fingeravtryck
Lägg till SHA-1 och SHA-256 för debug- och release-nyckeln i Firebase (krävs för Google Sign-In och App Links):
```bash
./gradlew :composeApp:signingReport
```

### 2.6 Kontaktuppgift för öppna datakällor
Sätt `AppConfig.CONTACT` (webbplats eller e-post) i `shared/core/src/commonMain/kotlin/se/pusslet/core/AppConfig.kt`.
Open Food Facts kräver att appen identifierar sig med kontaktuppgift i User-Agent.

## 3. Release och lansering

### 3.1 Releasebygge
```bash
./gradlew :composeApp:bundleProdRelease      # AAB för Play: composeApp/build/outputs/bundle/prodRelease/
./gradlew :composeApp:assembleProdRelease    # APK att prova själv
```
Releasebygget kör R8 (kodkrympning och resurskrympning, `composeApp/proguard-rules.pro`). Kartfilen för att läsa
stackspår ligger i `composeApp/build/outputs/mapping/prodRelease/mapping.txt`; Play tar den från AAB:n, men spara den
för varje version du laddar upp. Höj `app-versionCode` (och `app-versionName`) i `gradle/libs.versions.toml` före varje
uppladdning; Play nekar en versionskod som redan använts.

Prova releasen innan uppladdning: installera `prod`-APK:n (signerad, se 3.2) på en telefon eller emulator och gå igenom
"Utforska med en exempelfamilj", alla flikar, snabbinmatning och "Handla för veckan" (gjort 2026-10-21 utan fel).

### 3.2 Signering (nycklarna checkas aldrig in)
Play App Signing rekommenderas: Google håller appens signeringsnyckel och du signerar uppladdningar med en egen
**uppladdningsnyckel**.
1. Skapa uppladdningsnyckeln en gång, utanför repot:
   ```bash
   keytool -genkeypair -v -keystore ~/pusslet-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 9125
   ```
2. Skapa `keystore.properties` i repots rot (gitignorerad, liksom `*.jks` och `*.keystore`):
   ```properties
   storeFile=C:/Users/<du>/pusslet-upload.jks
   storePassword=<lösenord>
   keyAlias=upload
   keyPassword=<lösenord>
   ```
   I CI används i stället miljövariablerna `PUSSLET_STORE_FILE`, `PUSSLET_STORE_PASSWORD`, `PUSSLET_KEY_ALIAS` och
   `PUSSLET_KEY_PASSWORD` (som hemligheter). Utan dem blir releasebygget osignerat, vilket är väntat lokalt.
3. **Säkerhetskopiera** nyckelfilen och lösenorden på två ställen (t.ex. lösenordshanterare + krypterad disk). Tappar du
   uppladdningsnyckeln kan Google byta den, men det tar tid.
4. Vid första uppladdningen: välj Play App Signing. Lägg sedan till SHA-1/SHA-256 för **både** uppladdningsnyckeln och
   Googles appsigneringsnyckel (Play Console → Test and release → App integrity) i Firebase (2.5) och i
   `assetlinks.json` för App Links.

### 3.3 Lanseringschecklista
**Konto och Play Console**
- [ ] Utvecklarkonto skapat och identitet verifierad. **Nya personliga konton måste köra ett stängt test med minst 12
      testare som är med i 14 dagar i följd** innan produktionsåtkomst kan sökas. Planera in det: bjud in fler än 12
      (någon hoppar av) och låt testet löpa minst 14 hela dagar.
- [ ] Appen skapad med paketnamnet `se.pusslet.app` (kan aldrig ändras), standardspråk svenska.

**Backend**
- [ ] Firebase `prod`-projekt i EU, `google-services.json` i `composeApp/src/prod/` (2.1).
- [ ] Auth-metoder, App Check med Play Integrity, Blaze med budgetvarning (2.2–2.3).
- [ ] Regler, index och funktioner deployade (2.4); `TRIGGER_REGION` satt om databasen ligger i `eur3`.
- [ ] SHA-fingeravtryck för uppladdnings- och appsigneringsnyckeln i Firebase (2.5, 3.2).
- [ ] `AppConfig.CONTACT` satt (2.6). Länkdomänen (`pusslet.linkHost` i `local.properties`) publicerar
      `/.well-known/assetlinks.json` med appsigneringsnyckelns SHA-256 (t.ex. via Firebase Hosting), annars öppnas
      inbjudningslänkar i webbläsaren.

**Butikssida och policy**
- [ ] Integritetspolicyn publicerad på en öppen adress (docs/PRIVACY.md, fyll i det inom hakparentes).
- [ ] Webbsida för radering av konto publicerad (docs/PLAY_DATA_SAFETY.md, sist).
- [ ] Data safety ifyllt (docs/PLAY_DATA_SAFETY.md), innehållsklassificering, målgrupp 18+, annonser: nej.
- [ ] Butikstexter, ikon, funktionsgrafik och skärmdumpar på svenska och engelska (docs/STORE_LISTING.md).

**Bygget**
- [ ] `./gradlew build` grönt och `npm test` i `functions/` grönt.
- [ ] Versionskod höjd, AAB byggd och signerad med uppladdningsnyckeln (3.1–3.2).
- [ ] Releasen provad på en riktig telefon: inloggning, gå med via inbjudan, synk mellan två telefoner, push
      (förfrågan och svar), notiser i tysta timmar, kalenderexport, widgets.
- [ ] En runda med TalkBack (Idag, snabbinmatning, kalender, inköpslistan i butiksläge, onboarding) och
      Accessibility Scanner på samma skärmar; de automatiska kontrollerna täcker namn, storlek och kontrast men inte
      läsordning och hur det låter.

**Test och lansering**
- [ ] Intern testning (upp till 100 testare, inga krav) för att få en första version genom granskningen.
- [ ] Stängt test med minst 12 testare i 14 dagar i följd; samla synpunkter och rätta det viktigaste.
- [ ] Ansök om produktionsåtkomst (frågor om testet och appen), släpp sedan stegvis (t.ex. 20 % → 100 %).
- [ ] Efter lansering: följ ANR och krascher i Play Console (Android vitals) och kostnaden i Google Cloud Billing.
