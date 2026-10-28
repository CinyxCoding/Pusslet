# Integritetspolicy för Pusslet (utkast)

> **Utkast att granska innan publicering.** Fyll i det som står inom [hakparentes], låt gärna någon med GDPR-kunskap läsa
> igenom, och publicera policyn på en öppen webbadress (Play kräver en länk). Texten beskriver appen som den är byggd
> 2026-10-21; uppdatera den när appen ändras (se "Att hålla policyn sann" sist). English version below.

---

## Svenska

**Senast uppdaterad:** [datum]

### Vem ansvarar för uppgifterna
Personuppgiftsansvarig är [namn på ägaren eller företaget], [ort]. Frågor om integritet: [e-postadress].

### Kort sagt
- Pusslet är en planeringsapp för familjer: kalender, lämna och hämta, listor, matsedel och uppgifter.
- Familjens uppgifter sparas på telefonen och, när ni loggar in, i Google Firebase (Firestore) i EU (Finland,
  `europe-north1`), så att alla i familjen ser samma sak.
- Barn behöver bara ett förnamn. Allergier och kost sparas bara om en förälder aktivt väljer det.
- Ingen reklam, ingen försäljning av uppgifter, ingen profilering och ingen AI-tjänst.
- Du kan exportera allt och radera din familj eller ditt konto direkt i appen.

### Vilka uppgifter och varför

| Uppgifter | Varifrån | Varför | Rättslig grund |
|---|---|---|---|
| Konto: e-postadress, inloggningssätt och ett konto-id (vid Google-inloggning även namn från Google-kontot) | Du | Logga in och koppla dig till din familj | Avtal (art. 6.1 b) |
| Familjen: familjens namn, vuxnas namn eller smeknamn, roll (förälder eller hjälpare), färg | Du och de andra i familjen | Att dela planeringen | Avtal |
| Barn: förnamn, färg och valfritt födelsedatum, skola, klass, klädstorlekar, kontaktpersoner och anteckningar | Förälder | Visa barnens dag, klädråd utifrån ålder och väder, skollunch | Avtal |
| Allergier och kost för ett barn | Förälder, bara efter ett aktivt val | Varna i matsedel och inköp | **Uttryckligt samtycke** (art. 9.2 a), kan tas tillbaka när som helst |
| Kalender, uppgifter, sysslor, listor, recept, matsedel, skafferi, butiker | Ni | Själva tjänsten | Avtal |
| Lämna/hämta-förfrågningar och svar, "Jag handlar nu" (vilken lista och till när) | Ni | Att familjen ska kunna hjälpa varandra | Avtal |
| Kvitto på hämtning: vem som bockade av att ett barn är hämtat, och när | Den som hämtar | Att de andra ska veta att barnet är hämtat, och varna om ingen har bockat av | Avtal |
| Sjukdagar: vilket barn som är sjukt vilken dag, vem som stannar hemma, VAB och om frånvaro och VAB är anmälda (aldrig symtom eller diagnos) | Förälder | Ställa in dagen, säga till de andra och räkna VAB | **Uttryckligt samtycke** (art. 9.2 a), givet när du sjukanmäler; arket säger vad som sparas. [Kontrollera före publicering.] |
| Medicin och feber: vilken medicin och dos som gavs till ett barn och när, temperaturer, och kurer (medicin, dos, tider, antal dagar). Bara det föräldern skriver, aldrig symtom eller diagnos | Förälder | En delad logg så att föräldrarna vet vad som är givet, och påminnelser om kurens doser | **Uttryckligt samtycke** (art. 9.2 a), givet när du loggar; vyn säger vad som sparas. Bara föräldrar ser loggen. [Kontrollera före publicering.] |
| Väderort: ett ortsnamn och en position avrundad till ungefär 1 km | Du väljer ort, eller telefonens ungefärliga position om du tillåter det | Väder och klädråd på Idag | Avtal |
| Notis-token och telefonens språk | Telefonen | Skicka förfrågningar, svar och inköp till rätt telefon | Avtal |
| Inställningar på telefonen (tema, notiser, tysta timmar, vilka kalendrar som visas) | Du | Appens funktion | Avtal; stannar på telefonen |

**Stannar på telefonen och skickas aldrig till oss:** telefonens egna kalendrar (läses för att visa krockar), kamerabilden
vid streckkods- och QR-skanning (tolkas på telefonen), köphistoriken som ger förslag på basvaror, bilder du delar till appen
(till exempel ett fotat veckobrev; de säkerhetskopieras inte heller) och inställningarna ovan.
Familjens händelser kan du själv välja att skriva till telefonens kalender.

**Inga kraschrapporter eller analysdata** samlas in i den här versionen. Om det läggs till sker det bara efter att du har
sagt ja i appen, och policyn uppdateras först.

### Tjänster som får uppgifter
- **Google Firebase** (Google Ireland Ltd) är personuppgiftsbiträde: Firestore och Cloud Functions i EU (Finland) för
  familjens data, Firebase Authentication för konton och Firebase Cloud Messaging för notiser. Inloggning och notiser är
  globala tjänster som kan behandla uppgifter utanför EU; Google skyddar sådana överföringar med EU:s
  standardavtalsklausuler och EU–USA-ramverket för dataskydd. [Kontrollera mot Googles aktuella villkor före publicering.]
- **Öppna datakällor**, som bara får det som behövs för frågan och inga namn: SMHI (avrundad position för väder),
  Skolverket (sökord vid skolsökning), skolmaten.se (skolans id för skollunchen), Open Food Facts (streckkoden),
  Livsmedelsverket (livsmedelsdatabasen hämtas till telefonen).
- **Adresser du själv lägger in**: receptsidor du importerar från och kalenderlänkar (ICS) ni prenumererar på hämtas av
  telefonen, som vilken webbläsare som helst.
- **Familjens kalenderlänk**: om en förälder skapar den ser den som har länken familjens händelser (titel, tid, plats och
  vilka som deltar). Länken kan stoppas när som helst under Inställningar → Kalendrar.

Vi säljer inte uppgifter och lämnar inte ut dem till någon annan, utom om lag kräver det.

### Andra hemmet
Bor barnen i två hem kan ni dela dem med det andra hemmet. Då ser föräldrarna i båda hemmen barnens namn och profil,
vårdnadsschema och det som bara handlar om dem (händelser, packning, läxor och sysslor, önskelistor, skollunch, sjukdagar och vistelsetider).
Allergier, kost och medicinloggen delas bara om ni slår på det. Delade kostnader för barnen (vad, belopp, vem som
betalade) förs i samma utrymme och ses av föräldrarna i båda hemmen. Resten av er familj – vuxnas händelser, matsedel, inköpslistor,
recept, veckopeng och det som också gäller barn ni inte delar – syns aldrig för det andra hemmet. Slutar ni dela behåller
båda hemmen en egen kopia.

### Hur länge
- Så länge familjen finns. "Radera familjen för alla" tar bort familjen och allt i den från servern; "Radera mitt konto"
  tar bort ditt konto, dina telefoner och din koppling till familjen (de andra behåller familjen).
- Det ni tar bort i appen markeras som borttaget och kan ångras en kort stund. Efter 30 dagar tas innehållet bort från
  servern och från telefonerna; kvar på servern finns bara en markering med tiden för borttagningen, så att en telefon
  som varit avstängd länge också får veta det. Markeringen raderas efter ett år.
- Kalenderlänkar och inbjudningar gäller tills de stoppas eller går ut (inbjudningar efter 7 dagar).
- Sjukdagar sparas för att räkna VAB i år och förra året och tas sedan bort.
- Medicinloggen och kurerna tas bort 90 dagar efter dosen eller kurens sista dag.
- Notis-token tas bort när Google säger att den inte längre gäller, och när kontot raderas.
- Säkerhetskopior hos Google rensas enligt Googles rutiner.

### Barn
Appen är gjord för vuxna som planerar för sina barn. Barn har inga egna konton. Föräldrar bestämmer vad som skrivs om
barnen, och vi ber bara om förnamn.

### Dina rättigheter
Du har rätt att få tillgång till, rätta, radera och flytta dina uppgifter, att begränsa eller invända mot behandling och
att ta tillbaka ett samtycke. Det mesta gör du direkt i appen: **Inställningar → Integritet** har export (JSON), radering
av familjen och av kontot. Allergier och kost tar du bort under barnets profil. För annat, kontakta [e-postadress].
Du kan klaga hos Integritetsskyddsmyndigheten (imy.se).

### Säkerhet
Trafiken är krypterad. Bara inloggade medlemmar i en familj kan läsa familjens data, och hjälpare ser bara det föräldrarna
delar med dem (Firestores säkerhetsregler, testade). Appen innehåller inga hemliga nycklar.

### Ändringar
Vid ändringar uppdateras datumet ovan; större ändringar berättar vi om i appen.

---

## English

**Last updated:** [date]

### Who is responsible
The controller is [owner's name or company], [city], Sweden. Privacy questions: [email address].

### In short
- Pusslet is a planning app for families: calendar, drop-offs and pick-ups, lists, meal plans and chores.
- Your family's data is stored on the phone and, once you sign in, in Google Firebase (Firestore) in the EU (Finland,
  `europe-north1`), so everyone in the family sees the same thing.
- Children only need a first name. Allergies and diets are stored only if a parent actively chooses to.
- No ads, no selling of data, no profiling and no AI service.
- You can export everything and delete your family or your account right in the app.

### What we process and why

| Data | Source | Purpose | Legal basis |
|---|---|---|---|
| Account: email address, sign-in method and an account id (with Google sign-in also the name on the Google account) | You | Sign in and connect you to your family | Contract (Art. 6(1)(b)) |
| Family: family name, adults' names or nicknames, role (parent or helper), colour | You and the others in the family | Sharing the plan | Contract |
| Children: first name, colour and optional birth date, school, class, clothing sizes, contacts and notes | A parent | Showing the children's day, clothing advice from age and weather, school lunch | Contract |
| Allergies and diet for a child | A parent, only after an active choice | Warnings in meal plans and shopping | **Explicit consent** (Art. 9(2)(a)), can be withdrawn at any time |
| Calendar, tasks, chores, lists, recipes, meal plans, pantry, stores | You | The service itself | Contract |
| Drop-off/pick-up requests and answers, "I'm shopping now" (which list and until when) | You | Letting the family help each other | Contract |
| Pick-up receipts: who ticked off that a child is picked up, and when | The one picking up | Letting the others know the child is picked up, and warning when nobody has | Contract |
| Sick days: which child is sick on which day, who stays home, VAB and whether absence and VAB are reported (never symptoms or a diagnosis) | A parent | Cancelling the day, telling the others and counting VAB | **Explicit consent** (Art. 9(2)(a)), given when reporting; the sheet says what is stored. [Check before publishing.] |
| Weather place: a place name and a position rounded to about 1 km | You pick a place, or the phone's approximate location if you allow it | Weather and clothing advice in Today | Contract |
| Notification token and the phone's language | The phone | Sending requests, answers and shopping items to the right phone | Contract |
| Settings on the phone (theme, notifications, quiet hours, which calendars are shown) | You | How the app works | Contract; stays on the phone |

**Stays on the phone and is never sent to us:** the phone's own calendars (read to show clashes), the camera image when
scanning barcodes and QR codes (read on the phone), the purchase history that suggests staples, and the settings above.
You can choose to write the family's events to the phone's calendar.

**No crash reports or analytics** are collected in this version. If they are added, it will only be after you say yes in
the app, and this policy will be updated first.

### Services that receive data
- **Google Firebase** (Google Ireland Ltd) is our processor: Firestore and Cloud Functions in the EU (Finland) for the
  family's data, Firebase Authentication for accounts and Firebase Cloud Messaging for notifications. Sign-in and
  notifications are global services that may process data outside the EU; Google covers such transfers with the EU
  Standard Contractual Clauses and the EU–US Data Privacy Framework. [Check against Google's current terms before
  publishing.]
- **Open data sources**, which only get what the request needs and never names: SMHI (rounded position for weather),
  Skolverket (search words when finding a school), skolmaten.se (the school's id for school lunch), Open Food Facts (the
  barcode), Livsmedelsverket (the food database is downloaded to the phone).
- **Addresses you add yourself**: recipe pages you import from and calendar links (ICS) you subscribe to are fetched by
  the phone, like any web browser.
- **The family's calendar link**: if a parent creates it, anyone with the link sees the family's events (title, time,
  place and who takes part). The link can be stopped at any time under Settings → Calendars.

We do not sell data and do not hand it to anyone else, unless the law requires it.

### How long
- As long as the family exists. "Delete the family for everyone" removes the family and everything in it from the server;
  "Delete my account" removes your account, your phones and your link to the family (the others keep the family).
- What you remove in the app is marked as removed and can be undone for a short while. After 30 days the content is
  deleted from the server and from the phones; only a marker with the time of removal stays on the server, so a phone
  that has been switched off for a long time also learns about it. The marker is deleted after a year.
- Calendar links and invitations last until they are stopped or expire (invitations after 7 days).
- Sick days are kept to count VAB this year and last year, then removed.
- A notification token is removed when Google says it is no longer valid, and when the account is deleted.
- Backups at Google are cleared according to Google's routines.

### Children
The app is made for adults planning for their children. Children have no accounts of their own. Parents decide what is
written about the children, and we only ask for a first name.

### The other home
If the children live in two homes, you can share them with the other home. The parents in both homes then see the
children's names and profiles, their custody schedule and what is only about them (events, packing, homework and chores,
wishlists, school lunch, sick days and preschool hours). Allergies, diets and the medicine log are only shared if you turn that on. Shared costs for the children (what, amount,
who paid) are kept in the same space and seen by the parents in both homes. The rest of your family – the adults'
events, meal plan, shopping lists, recipes, pocket money and anything that also involves children you don't share – is
never visible to the other home. If you stop sharing, both homes keep their own copy.

### Your rights
You have the right to access, correct, delete and move your data, to restrict or object to processing and to withdraw
consent. Most of this you do right in the app: **Settings → Privacy** has export (JSON) and deletion of the family and of
the account. Allergies and diets are removed in the child's profile. For anything else, contact [email address]. You can
complain to the Swedish Authority for Privacy Protection (imy.se).

### Security
Traffic is encrypted. Only signed-in members of a family can read its data, and helpers only see what the parents share
with them (Firestore security rules, tested). The app contains no secret keys.

### Changes
When this policy changes the date above is updated; larger changes are announced in the app.

---

## Att hålla policyn sann
Uppdatera policyn och PLAY_DATA_SAFETY.md i samma ändring som någon av dessa:
- en ny samling eller ett nytt fält med personuppgifter i Firestore (`Collections.kt`, `firestore.rules`);
- en ny extern tjänst eller värd (`shared/data/.../sources`, `functions/src`);
- kraschrapporter, analys eller betalning slås på (MONETIZATION.md);
- ändrade lagringstider (radering, rensning av borttaget).
