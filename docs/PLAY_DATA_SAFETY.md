# Underlag för Google Plays Data safety-formulär / Data safety form

> Svar för Play Console → App content → Data safety, för **prod**-flavorn som den är byggd i dag. Granska igen om
> kraschrapporter, betalning eller delade bilder i molnet läggs till. Detaljer och motiveringar: `docs/PRIVACY.md`.

## Svenska

### Översikt
| Fråga | Svar |
|---|---|
| Samlar appen in eller delar några av de obligatoriska datatyperna? | **Ja** |
| Krypteras all användardata under överföring? | **Ja** (TLS till Firebase och alla datakällor) |
| Kan användare begära att data raderas? | **Ja** – i appen (Mer → Integritet och dina data → Radera familjen / Radera mitt konto) och via e-post […] |
| Delas data med tredje part? | **Nej** (Firebase är personuppgiftsbiträde; anrop till SMHI m.fl. innehåller inga användaruppgifter utöver position avrundad till ≈ 1 km för vald ort) |
| Har appen genomgått oberoende säkerhetsgranskning (MASA)? | Nej |
| Följer appen Families-policyn / riktar sig till barn? | Nej – riktar sig till vuxna (föräldrar). Barn har inga konton. |

### Insamlade datatyper
Alla: **insamlas**, **delas inte**, **inte tillfälligt (lagras)**, syfte **Appfunktionalitet** (och **Kontohantering** där det står).
Valfrihet: "Obligatorisk" betyder att tjänsten inte fungerar utan den.

| Play-kategori → typ | Vad i Pusslet | Obligatorisk? | Syfte |
|---|---|---|---|
| Personuppgifter → Namn | Vuxnas visningsnamn, barnens förnamn, kontakters namn | Ja (namn) | Appfunktionalitet, Kontohantering |
| Personuppgifter → E-postadress | Inloggning; e-post till barnets kontakter (frivilligt) | Ja vid e-postinloggning | Kontohantering, Appfunktionalitet |
| Personuppgifter → Telefonnummer | Barnets kontakter (frivilligt) | Nej | Appfunktionalitet |
| Personuppgifter → Andra uppgifter | Födelsedatum, skola/klass, klädstorlekar, anteckningar | Nej | Appfunktionalitet |
| Hälsa och fitness → Hälsoinformation | Allergier och kost (opt-in med samtycke) | Nej | Appfunktionalitet |
| Plats → Ungefärlig plats | Vald ort för väder (koordinater); telefonens grova position används bara för att föreslå ort | Nej | Appfunktionalitet |
| Kalender → Kalenderhändelser | Familjens händelser, lämna/hämta, prenumerationslänkar | Ja | Appfunktionalitet |
| Appaktivitet → Annat användargenererat innehåll | Uppgifter, matsedel, recept, inköpslistor, butiker | Ja | Appfunktionalitet |
| Enhets-id eller andra id:n | FCM-token och slumpat enhets-id för notiser | Ja (för push) | Appfunktionalitet |

### Inte insamlat
- **Foton:** bilder som delas till appen sparas bara på telefonen (bearbetas lokalt – räknas inte som insamling).
- **Telefonens kalendrar:** läses och visas lokalt, laddas aldrig upp. Export till telefonens kalender sker på telefonen.
- **Kontakter, SMS, samtalslogg, filer, ljud, webbhistorik, appinteraktioner, diagnostik/kraschloggar, annonser-id,
  ekonomisk information, exakt position.**
- Köphistorik för basvaruförslag ligger bara på telefonen.

> När kraschrapporter (Crashlytics, efter samtycke) aktiveras: lägg till **Appinformation och prestanda → Kraschloggar
> och Diagnostik**, valfritt, syfte Analys/Appfunktionalitet, insamlas av Google som biträde.

---

## English

### Overview
| Question | Answer |
|---|---|
| Does the app collect or share any of the required user data types? | **Yes** |
| Is all user data encrypted in transit? | **Yes** (TLS to Firebase and every data source) |
| Can users request that data is deleted? | **Yes** – in the app (More → Privacy and your data → Delete the family / Delete my account) and by email […] |
| Is data shared with third parties? | **No** (Firebase is a processor; calls to SMHI etc. carry no user data beyond a position rounded to ≈ 1 km for the chosen place) |
| Independent security review (MASA)? | No |
| Families policy / directed at children? | No – directed at adults (parents). Children have no accounts. |

### Data collected
All: **collected**, **not shared**, **not ephemeral (stored)**, purpose **App functionality** (and **Account management** where noted).

| Play category → type | In Pusslet | Required? | Purpose |
|---|---|---|---|
| Personal info → Name | Adults' display names, children's first names, contacts' names | Yes (names) | App functionality, Account management |
| Personal info → Email address | Sign-in; email of a child's contacts (optional) | Yes with email sign-in | Account management, App functionality |
| Personal info → Phone number | A child's contacts (optional) | No | App functionality |
| Personal info → Other info | Date of birth, school/class, clothing sizes, notes | No | App functionality |
| Health and fitness → Health info | Allergies and diets (opt-in with consent) | No | App functionality |
| Location → Approximate location | Chosen place for weather (coordinates); the phone's coarse position is only used to suggest a place | No | App functionality |
| Calendar → Calendar events | Family events, drop-off/pick-up, subscription links | Yes | App functionality |
| App activity → Other user-generated content | Tasks, meal plans, recipes, shopping lists, stores | Yes | App functionality |
| Device or other IDs | FCM token and a random device id for notifications | Yes (for push) | App functionality |

### Not collected
- **Photos:** pictures shared to the app stay on the phone (processed on device – not collection).
- **The phone's calendars:** read and shown on the phone, never uploaded. Export to the phone's calendar happens on the phone.
- **Contacts, SMS, call log, files, audio, web history, app interactions, diagnostics/crash logs, advertising id,
  financial info, precise location.**
- Purchase history for staple suggestions stays on the phone.

> When crash reports (Crashlytics, after consent) are switched on: add **App info and performance → Crash logs and
> Diagnostics**, optional, purpose Analytics/App functionality, collected by Google as processor.
