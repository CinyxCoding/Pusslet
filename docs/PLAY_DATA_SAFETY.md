# Underlag för Google Plays Data safety-formulär

> Svaren beskriver appen som den är byggd 2026-10-21 (`prod`-flavorn), i samma ordning som formuläret i Play Console
> (App content → Data safety). Ägaren fyller i formuläret och ansvarar för svaren; se över dem mot PRIVACY.md och koden
> före varje release som ändrar vilka uppgifter som skickas. English version below.

---

## Svenska

### Översikt
| Fråga | Svar |
|---|---|
| Samlar eller delar appen någon av de obligatoriska användardatatyperna? | **Ja** |
| Krypteras all användardata under överföring? | **Ja** (HTTPS/TLS till Firebase och alla datakällor) |
| Kan användare begära att deras data raderas? | **Ja**: i appen (Inställningar → Integritet) och via webbsidan för radering (se nedan) |
| Följer appen Families-policyn? | Nej. Målgrupp: vuxna (18+), appen är för föräldrar; barn har inga konton |
| Oberoende säkerhetsgranskning (MASA)? | Nej |

"Samlas in" betyder att uppgiften lämnar telefonen. Inget delas med tredje part i Googles mening: Firebase är
personuppgiftsbiträde, och frågor till öppna datakällor görs på användarens begäran med bara det som frågan behöver.

### Datatyper

| Kategori → typ | Samlas in | Delas | Tillfälligt | Krävs/valfritt | Syften |
|---|---|---|---|---|---|
| Plats → Ungefärlig plats | Ja | Nej | Nej | Valfritt | Appens funktion (väder och klädråd) |
| Personuppgifter → Namn | Ja | Nej | Nej | Krävs | Appens funktion, kontohantering |
| Personuppgifter → E-postadress | Ja | Nej | Nej | Krävs för konto | Kontohantering |
| Personuppgifter → Användar-id | Ja | Nej | Nej | Krävs för konto | Kontohantering, appens funktion |
| Personuppgifter → Andra uppgifter | Ja | Nej | Nej | Valfritt | Appens funktion |
| Hälsa och träning → Hälsoinformation | Ja | Nej | Nej | Valfritt | Appens funktion |
| Kalender → Kalenderhändelser | Ja | Nej | Nej | Valfritt | Appens funktion |
| Appaktivitet → Annat användargenererat innehåll | Ja | Nej | Nej | Valfritt | Appens funktion |
| Enhets-id eller andra id:n | Ja | Nej | Nej | Krävs | Appens funktion (notiser) |

**Inte insamlat:** exakt plats, adress, telefonnummer (användarens eget), ekonomisk information, meddelanden, foton och
video, ljud, filer, kontakter (telefonens kontaktlista), webbhistorik, appinteraktioner, installerade appar,
kraschloggar och diagnostik, reklam-id.

### Motivering per rad
- **Ungefärlig plats:** väderorten sparas i familjens inställningar. Den väljs som ortnamn eller hämtas från telefonens
  ungefärliga position (`ACCESS_COARSE_LOCATION`, ingen exakt position begärs). Positionen avrundas till två decimaler
  innan den sparas och skickas till SMHI. Valfritt: utan ort visas inget väder.
- **Namn:** vuxnas namn eller smeknamn och barnens förnamn, som familjen skriver. Krävs för att skapa en familj.
- **E-postadress och användar-id:** Firebase Authentication. Krävs för att logga in och synka.
- **Andra uppgifter:** barnens valfria födelsedatum, skola, klass, klädstorlekar, kontaktpersoner (namn, telefon, e-post
  som föräldern skriver in) och anteckningar.
- **Hälsoinformation:** allergier och kost, bara efter ett aktivt val per barn ("Spara allergier och kost för …").
- **Kalenderhändelser:** familjens händelser i appen synkas via Firestore. Telefonens egna kalendrar läses bara på
  telefonen och skickas inte (on-device-åtkomst räknas inte som insamling).
- **Annat användargenererat innehåll:** listor, recept, matsedlar, uppgifter, sysslor, lämna/hämta-förfrågningar.
- **Enhets-id:** Firebase Cloud Messaging-token och Firebase-installations-id för push mellan familjens telefoner.

### Saker att dubbelkolla innan formuläret skickas
- **Sökord till Skolverket och streckkoder till Open Food Facts** skickas direkt från telefonen till respektive tjänst när
  användaren söker, sparas inte av oss och identifierar ingen. Vår bedömning: inte "insamling" av sökhistorik. Vill du
  vara försiktigare, ange Appaktivitet → Sökhistorik i appen som insamlad, tillfälligt behandlad, valfri.
- **Kraschloggar:** ingen kraschrapportering finns i bygget (och switchen i Inställningar är dold). Slås Crashlytics på
  ska App info och prestanda → Kraschloggar och Diagnostik anges som insamlade, valfria (samtycke), syfte Analys.
- **Betalning:** slås premium på tillkommer Ekonomisk information → Köphistorik (MONETIZATION.md).

### Länk för radering av konto
Play kräver en webbsida där man kan begära radering även utan appen. Publicera en sida (t.ex.
`https://[din-domän]/radera`) med ungefär detta:

> **Radera ditt Pusslet-konto**
> I appen: Inställningar → Integritet → "Radera mitt konto" (ditt konto och din koppling till familjen tas bort, de andra
> behåller familjen) eller "Radera familjen för alla" (familjen och allt i den tas bort). Har du inte appen kvar? Skicka ett
> mejl till [e-postadress] från adressen du loggar in med, så raderar vi kontot inom 30 dagar och bekräftar det.
> Raderat: konto, e-postadress, notis-token och kopplingen till familjen; vid radering av familjen allt familjen har sparat.

---

## English

### Overview
| Question | Answer |
|---|---|
| Does the app collect or share any of the required user data types? | **Yes** |
| Is all user data encrypted in transit? | **Yes** (HTTPS/TLS to Firebase and every data source) |
| Can users request that their data is deleted? | **Yes**: in the app (Settings → Privacy) and on the account deletion web page (below) |
| Does the app follow the Families policy? | No. Target audience: adults (18+); the app is for parents and children have no accounts |
| Independent security review (MASA)? | No |

"Collected" means the data leaves the phone. Nothing is shared with third parties in Google's sense: Firebase is a
processor, and requests to open data sources are made at the user's request with only what the request needs.

### Data types

| Category → type | Collected | Shared | Ephemeral | Required/optional | Purposes |
|---|---|---|---|---|---|
| Location → Approximate location | Yes | No | No | Optional | App functionality (weather and clothing advice) |
| Personal info → Name | Yes | No | No | Required | App functionality, account management |
| Personal info → Email address | Yes | No | No | Required for an account | Account management |
| Personal info → User IDs | Yes | No | No | Required for an account | Account management, app functionality |
| Personal info → Other info | Yes | No | No | Optional | App functionality |
| Health and fitness → Health info | Yes | No | No | Optional | App functionality |
| Calendar → Calendar events | Yes | No | No | Optional | App functionality |
| App activity → Other user-generated content | Yes | No | No | Optional | App functionality |
| Device or other IDs | Yes | No | No | Required | App functionality (notifications) |

**Not collected:** precise location, address, phone number (the user's own), financial info, messages, photos and
videos, audio, files, contacts (the phone's contact list), web browsing, app interactions, installed apps, crash logs and
diagnostics, advertising ID.

### Reasons per row
- **Approximate location:** the weather place is stored in the family's settings. It is picked as a place name or taken
  from the phone's approximate position (`ACCESS_COARSE_LOCATION`, no precise location is requested). The position is
  rounded to two decimals before it is stored and sent to SMHI. Optional: without a place no weather is shown.
- **Name:** adults' names or nicknames and the children's first names, as the family writes them. Required to create a
  family.
- **Email address and user ID:** Firebase Authentication. Required to sign in and sync.
- **Other info:** the children's optional birth date, school, class, clothing sizes, contacts (name, phone, email typed
  in by the parent) and notes.
- **Health info:** allergies and diets, only after an active choice per child ("Save allergies and diet for …").
- **Calendar events:** the family's events in the app sync through Firestore. The phone's own calendars are only read on
  the phone and never sent (on-device access is not collection).
- **Other user-generated content:** lists, recipes, meal plans, tasks, chores, drop-off/pick-up requests.
- **Device IDs:** the Firebase Cloud Messaging token and the Firebase installation ID, for push between the family's
  phones.

### Check before submitting
- **Search words to Skolverket and barcodes to Open Food Facts** go straight from the phone to that service when the user
  searches, are not stored by us and identify no one. Our view: not collection of search history. To be more cautious,
  declare App activity → In-app search history as collected, processed ephemerally, optional.
- **Crash logs:** there is no crash reporting in the build (and the switch in Settings is hidden). If Crashlytics is
  switched on, declare App info and performance → Crash logs and Diagnostics as collected, optional (consent), purpose
  Analytics.
- **Payments:** if premium is switched on, add Financial info → Purchase history (MONETIZATION.md).

### Account deletion link
Play requires a web page where deletion can be requested without the app. Publish a page (e.g.
`https://[your-domain]/delete`) along these lines:

> **Delete your Pusslet account**
> In the app: Settings → Privacy → "Delete my account" (your account and your link to the family are removed; the others
> keep the family) or "Delete the family for everyone" (the family and everything in it is removed). Don't have the app
> any more? Email [email address] from the address you sign in with, and we delete the account within 30 days and confirm
> it. Deleted: account, email address, notification tokens and the link to the family; when the family is deleted,
> everything the family has stored.
