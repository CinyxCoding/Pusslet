# Integritetspolicy (utkast) / Privacy policy (draft)

> Utkast att granska innan publicering. Fyll i `[…]`. Policyn ska ligga på en offentlig webbadress som anges i Play
> Console och i appens "Integritet och dina data". Den beskriver appen som den är byggd i dag (se ADR-001, 021–025).

---

## Svenska

**Senast uppdaterad:** […datum…]
**Personuppgiftsansvarig:** […namn/företag…], […adress…], […e-post…] (samma kontaktuppgift som `AppConfig.CONTACT`).

### Kort sagt
- Pusslet är en planeringsapp för familjer. Vi samlar bara in det som behövs för att familjens kalender, listor och
  påminnelser ska fungera och synkas mellan er telefoner.
- Ingen reklam, ingen spårning, ingen analys, ingen försäljning av data och ingen AI. Allt "smart" i appen är regler som
  körs på telefonen.
- Allt sparas i EU. Ni kan exportera och radera allt i appen.

### Vilka uppgifter och varför
| Uppgifter | Varför | Var |
|---|---|---|
| Konto: e-postadress eller Google-konto-id, visningsnamn | Logga in och koppla dig till din familj | Firebase Authentication (Google, EU) |
| Familjen: vuxnas namn/smeknamn, roll, färg | Visa vem som gör vad | Firestore (EU) |
| Barn: förnamn (krävs); valfritt födelsedatum, skola/klass, skollunch, klädstorlekar, kontakter (namn, relation, telefon, e-post), anteckningar, emoji | Planering, klädråd efter ålder, skolmat | Firestore (EU) |
| Allergier och kost (**känsliga uppgifter**, frivilligt) | Varna för recept som inte passar | Firestore (EU), med datum för samtycke |
| Kalenderhändelser, lämna/hämta, förfrågningar, uppgifter, matsedel, recept, inköpslistor, butiker, "jag handlar nu" | Själva tjänsten | Firestore (EU) |
| Länkar till kalenderprenumerationer (föräldrar) och hemliga flödeslänkar | Visa skolans/klubbens kalender; dela familjens kalender | Firestore (EU) |
| Vald ort för väder (koordinater) | Väder och klädråd | Firestore (EU); avrundad position skickas till SMHI |
| Push-token, enhets-id, tysta timmar, språk | Skicka notiser till rätt telefon vid rätt tid | Firestore (EU) |

**Bara på din telefon (lämnar den aldrig):** händelser i telefonens egna kalendrar som du väljer att visa, bilder du delar
till appen, köphistorik för förslag på basvaror, väder-cache och inställningar. Om du väljer att lägga familjens händelser
i en kalender som tillhör ett Google-konto synkar Google den kalendern enligt sina villkor.

### Rättslig grund
- Avtal (art. 6.1 b GDPR) för att tillhandahålla tjänsten ni valt att använda.
- Uttryckligt samtycke (art. 9.2 a) för allergier och kost. Samtycket kan återkallas genom att ta bort uppgifterna.
- Samtycke för kraschrapporter (av som standard).

### Barn
Barn har inga konton. Bara förnamn krävs; allt annat är frivilligt och fylls i av en förälder. Hjälpare (t.ex. morfar)
ser bara händelser som delats med dem. Delade bilder kan visa barn och stannar därför på telefonen.

### Tredje parter
- **Google Firebase** (Authentication, Firestore, Cloud Functions, Cloud Messaging, Hosting) är vårt personuppgiftsbiträde.
  Data lagras i EU (`[europe-north1 / eur3]`). Notiser skickas via Firebase Cloud Messaging och innehåller förnamn,
  händelsens titel eller varans namn och tid – aldrig allergier, adresser eller anteckningar – och sparas högst 24 timmar.
- **Öppna datakällor** som appen anropar direkt: SMHI (väder, avrundad position ≈ 1 km), Skolverket (sökning på skolnamn),
  skolmaten.se (skolans lunchmeny), Livsmedelsverket (livsmedelsdata), Open Food Facts (streckkod). De får bara det som
  står i frågan och din IP-adress, som all internettrafik.
- **Webbsidor och kalenderlänkar du själv anger** (receptimport, kalenderprenumerationer) hämtas av appen.
- **Kalenderlänkar ni delar** (eget ICS-flöde): den som har länken kan se händelserna tills ni återkallar den.

### Hur länge
Så länge familjen finns. "Radera familjen" tar bort allt på servern direkt, inklusive delningslänkar och inbjudningar;
"Radera mitt konto" tar bort ditt konto, din medlemskap och dina enheter (är du sista förälder raderas familjen). Googles
driftkopior rensas enligt Google Clouds villkor.

### Dina rättigheter
Du kan när som helst se och ändra uppgifterna i appen, **exportera allt (JSON)** och **radera** familj eller konto under
Mer → Integritet och dina data. Du kan också kontakta oss på […e-post…]. Du har rätt att klaga hos
Integritetsskyddsmyndigheten (imy.se).

### Säkerhet
Krypterad trafik (TLS), säkerhetsregler som bara låter familjens medlemmar läsa familjens data, rollbaserade
skrivrättigheter, App Check, och inga personuppgifter i loggar.

### Ändringar
Vi meddelar väsentliga ändringar i appen innan de börjar gälla.

---

## English

**Last updated:** […date…]
**Controller:** […name/company…], […address…], […email…] (same contact as `AppConfig.CONTACT`).

### In short
- Pusslet is a planning app for families. We only collect what the family calendar, lists and reminders need to work
  and sync between your phones.
- No ads, no tracking, no analytics, no sale of data and no AI. Everything "smart" in the app is rules running on the phone.
- Everything is stored in the EU. You can export and delete everything in the app.

### What and why
| Data | Why | Where |
|---|---|---|
| Account: email address or Google account id, display name | Sign in and connect you to your family | Firebase Authentication (Google, EU) |
| Family: adults' names/nicknames, role, colour | Show who does what | Firestore (EU) |
| Children: first name (required); optional date of birth, school/class, school lunch, clothing sizes, contacts (name, relation, phone, email), notes, emoji | Planning, age-based clothing advice, school lunch | Firestore (EU) |
| Allergies and diets (**sensitive data**, optional) | Warn about recipes that do not fit | Firestore (EU), with the date of consent |
| Calendar events, drop-off/pick-up, requests, tasks, meal plans, recipes, shopping lists, stores, "I'm shopping now" | The service itself | Firestore (EU) |
| Calendar subscription links (parents) and secret feed links | Show the school's/club's calendar; share the family calendar | Firestore (EU) |
| Chosen place for weather (coordinates) | Weather and clothing advice | Firestore (EU); a rounded position is sent to SMHI |
| Push token, device id, quiet hours, language | Send notifications to the right phone at the right time | Firestore (EU) |

**Only on your phone (never leaves it):** events in the phone's own calendars that you choose to show, pictures you share
to the app, purchase history for staple suggestions, weather cache and settings. If you put the family's events in a
calendar that belongs to a Google account, Google syncs that calendar under its own terms.

### Legal basis
- Contract (Art. 6(1)(b) GDPR) to provide the service you chose to use.
- Explicit consent (Art. 9(2)(a)) for allergies and diets. Withdraw it by removing the information.
- Consent for crash reports (off by default).

### Children
Children have no accounts. Only a first name is required; everything else is optional and entered by a parent. Helpers
(e.g. a grandparent) only see events shared with them. Shared pictures may show children and therefore stay on the phone.

### Third parties
- **Google Firebase** (Authentication, Firestore, Cloud Functions, Cloud Messaging, Hosting) is our processor. Data is
  stored in the EU (`[europe-north1 / eur3]`). Notifications are sent through Firebase Cloud Messaging and contain first
  names, the event title or item name and a time – never allergies, addresses or notes – and are kept for at most 24 hours.
- **Open data sources** the app calls directly: SMHI (weather, rounded position ≈ 1 km), Skolverket (school name search),
  skolmaten.se (school lunch menu), the Swedish Food Agency (food data), Open Food Facts (barcodes). They only receive what
  is in the request and your IP address, like all internet traffic.
- **Web pages and calendar links you enter yourself** (recipe import, calendar subscriptions) are fetched by the app.
- **Calendar links you share** (the family feed): anyone with the link can see the events until you revoke it.

### Retention
As long as the family exists. "Delete the family" removes everything on the server immediately, including shared links
and invitations; "Delete my account" removes your account, membership and devices (if you are the last parent, the family
is deleted). Google's operational backups are purged under Google Cloud's terms.

### Your rights
You can view and change the data in the app at any time, **export everything (JSON)** and **delete** the family or your
account under More → Privacy and your data. You can also contact us at […email…]. You have the right to complain to the
Swedish Authority for Privacy Protection (imy.se).

### Security
Encrypted traffic (TLS), security rules that only let family members read the family's data, role-based write access,
App Check, and no personal data in logs.

### Changes
We announce material changes in the app before they take effect.
