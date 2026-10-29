# Pusslet – gratis och premium

> Status: **ingen betalning är påslagen.** Varje bygge låser upp allt (`AllUnlocked`, BRIEF §7). Det här är underlaget
> för en senare Play-lansering med premium, och stegen för att slå på den. Uppdelningen gjordes om 2026-10-29 för appen
> som den är då (andra hemmet, delade kostnader, lov, barnläge, medicin, bilder, kalas).

## Principer
- **Gratisversionen ska räcka för en vanlig familj.** Det som håller ihop vardagen är gratis: kalendern, Idag, listorna,
  matsedeln och att dela med varandra. Premium är för familjer med två hem, många källor eller ett särskilt intresse,
  till exempel näring eller veckopeng.
- **Säkerhet, hälsa, integritet och att gå med är aldrig premium.** Det gäller lämna och hämta, förfrågningar,
  "Hämtad", sjukanmälan och VAB, medicin och feber, export och radering, tillgänglighet och att bli inbjuden – även
  till andra hemmets delade utrymme. Varje vuxen som går med gratis är reklam vi inte betalar för.
- **En spärr stoppar bara något nytt.** Det familjen redan har fortsätter att fungera om premium går ut: prenumerationer
  över gränsen hämtas och visas som förut, och bilder i molnet går att se, men det går inte att lägga till fler. Ingen data
  raderas eller göms.
- **Ingen funktion får bero på att betalningen fungerar.** Utan Play Store, utan nät eller om Google Play Billing svarar fel
  gäller det familjen senast hade. Demo och dev låser alltid upp allt.
- **Ingen reklam och ingen försäljning av data**, varken i gratis- eller premiumversionen.
- **Premium gäller hela familjen.** En förälder köper och alla i familjen får det, även hjälpare. Det kräver att köpet
  verifieras på servern och skrivs på familjen (se "Att slå på betalning").
- **I ett delat utrymme räcker det att ett hem betalar.** Utrymmet får premium när något av hemmen har det, så att två
  hem aldrig behöver göra upp om vem som betalar för barnens saker.
- **Spärren syns där behovet uppstår**, aldrig som en vägg mitt i ett flöde. Exempel:
  - den fjärde kalendern
  - "Starta delning"
  - "Synka bilden till alla"
  - årsöversikten över VAB

## Gratis och premium i appen

| Område | Gratis | Premium | Varför | `Feature` |
|---|---|---|---|---|
| Kalender, Idag, snabbinmatning, sök | Allt | – | Kärnan; utan den är appen inte värd att prova | – |
| Lämna och hämta, förfrågningar, "Hämtad", sjukdagar och VAB, medicin och feber | Allt | – | Säkerhet och hälsa | – |
| Listor, butiksläge, streckkod, basvaror | Allt | – | Kärnan | – |
| Matsedel, recept, receptimport, receptsamlingar, skollunch, väder | Allt | – | Kärnan | – |
| Näringsvärden | Per recept | Veckans näringsöversikt | Fördjupning; värdet per recept visar vad premium ger | `NutritionOverview` |
| Uppgifter, sysslor, fördelningsvy | Allt | Hur det delats över tid (nytt) | Det som gör delningen rättvis är gratis | `DistributionHistory` |
| Barnläge | Barnets dag och sysslor | Stjärnor och veckopeng | Ett tydligt extra för barnfamiljer | `KidRewards` |
| Lov och vistelsetider | Loven syns i kalendern | Planering dag för dag och vistelsetider | Periodisk planering med stor nytta när den behövs | `BreakPlanning` |
| Kalas och önskelistor | Allt | – | Önskelistorna når morföräldrar: spridning vi inte betalar för | – |
| ICS-prenumerationer | 3 kalendrar | Obegränsat | Skola, en förening och förskola räcker för de flesta; den som har fler har störst nytta och kostar mest i hämtningar | `UnlimitedSubscriptions` |
| Familjens eget kalenderflöde | En länk | En egen länk per person (nytt) | Kostar en funktion per hämtning; följ kostnaden | `PersonalFeeds` |
| Telefonens kalender, widgets Idag och Inköpslistan | Allt | – | Ingen kostnad för oss, stor nytta i vardagen | – |
| Fler widgets (t.ex. veckan, matsedeln) | – | Ja | Bekvämlighet utöver det nödvändiga | `ExtraWidgets` |
| Bilder till händelser och anteckningar | På telefonen | I molnet för hela familjen (nytt) | Lagring kostar | `CloudPictures` |
| Andra hemmet | Gå med och använda allt som delas | Starta delning | Störst värde för dem som behöver det; ett hem betalar för båda | `OtherHousehold` |
| Delade kostnader | – | Ja, för båda hemmen | Det som appar för två hem tar mest betalt för | `SharedCosts` |
| Familjer per konto | 1 | Flera (nytt) | Morföräldrar med flera familjer, bonusfamiljer | `SeveralFamilies` |
| Notiser, push, export och radering av data | Allt | – | Integritet och säkerhet är aldrig premium | – |

## Nytt för premium (inte byggt)
I den ordning de bör byggas, med insats och värde.

1. **Bilder och kvitton i molnet** (`CloudPictures`; medel insats, högt värde). Bilderna synkas till hela familjen och
   andra hemmet, och ett kvitto kan kopplas till en delad kostnad. I dag stannar bilderna på telefonen (ADR-049) och
   kvitton sparas inte (ADR-057).
2. **Rapporter för två hem** (`TwoHomeReports`; medel insats, högt värde):
   - kostnaderna per månad och år som PDF eller CSV
   - en färdig Swish-förfrågan när man reglerar
   - dagar per hem och ändringar i schemat

   Rapporterna är sammanställningar och inte juridiskt underlag.
3. **VAB-översikt per år** (`SickDayYear`; liten insats, medelvärde). Dagar per barn och förälder, som underlag till
   Försäkringskassan. Att registrera en sjukdag är fortfarande gratis.
4. **Familjeskärm** (`FamilyScreen`; medel insats). Ett läge för köksplattan där appen alltid är på och visar dagen och
   veckan.
5. **Matsedelsförslag** (`MenuSuggestions`; medel insats). Förslag ur familjens egna recept med regler (rotation,
   säsong, inte samma sak två dagar i rad), utan AI (ADR-001), och med inköpslistan.
6. **Fler påminnelser per händelse** (`ExtraReminders`; liten insats).
7. **En egen kalenderlänk per person** (`PersonalFeeds`; liten insats). Funktionen klarar redan `memberId`; det som
   saknas är ett val i appen.
8. **Hur sysslorna delats över tid** (`DistributionHistory`; liten insats).
9. **Flera familjer per konto** (`SeveralFamilies`; stor insats).
10. **Teman och ikoner** (`Themes`; liten insats). Kosmetiskt, så det skapar ingen irritation.
11. **Länk till barnvakten** (`SitterLink`; liten insats). Ett tillfälligt överlämningskort utan konto. Reglerna för
    `handoverShares` finns redan, men ingen funktion visar kortet än.

### Pris och villkor (ägarens val 2026-10-29)
- **Månad 39 kr och år 349 kr**, för hela familjen. Året motsvarar ungefär tre gratis månader.
- **14 dagars gratis provperiod** via Play (en gång per Google-konto).
- **Den som använder appen före lanseringen** (familjen och testarna) **får premium i ett år** från lanseringsdagen. Det
  skrivs på familjen av servern (`families/{id}/entitlement` med ett slutdatum) och kräver inget köp.
- **Att starta delning med andra hemmet är premium**, att gå med är gratis. Stjärnor och veckopeng samt lovplaneringen är
  premium.
- Jämförelse i oktober 2026 (priserna skiljer sig mellan källorna):

  | App | Pris |
  |---|---|
  | Cozi Gold | ungefär 39 USD/år |
  | FamilyWall Premium | 4,99–7,99 USD/månad eller 44,99 USD/år |
  | 2Houses (två hem) | ungefär 12,50 USD/månad |
  | OurFamilyWizard | 99–179 USD per förälder och år |
- Priset sätts per land i Play Console och inkluderar moms.
- Inget engångsköp för livstid i början: det betalar inte Firebase-kostnaden, som växer med familjens användning.

Källor:
- [Cozi i App Store](https://apps.apple.com/gb/app/cozi-family-organiser/id407108860)
- [FamilyWall i App Store](https://apps.apple.com/app/id496889629)
- [2Houses jämfört med OurFamilyWizard](https://www.2houses.com/en/blog/2houses-vs-ourfamilywizard)
- [Appar för separerade föräldrar som delar kostnader](https://savvyladies.org/education/expense-sharing-apps-for-divorced-parents)

### Kostnader att följa
- Cloud Functions: kalenderflödet (en körning per hämtning, kalendrarna hämtar ungefär varje timme), push och raderingar.
- Firestore: läsningar och skrivningar vid synk.
- Cloud Storage, med molnbilderna: lagring och nedladdning per familj.

Sätt en budgetvarning (SETUP.md 2.3) och titta på kostnaden per aktiv familj efter det stängda testet, innan priset
bestäms.

## Kod i dag
- `Entitlement` och `Feature` finns i `shared/core` (`premium`), och `FreeLimits` med gratisgränserna. `Feature` listar
  allt premium i tabellerna ovan:
  - **Byggt och spärrat:** `UnlimitedSubscriptions` (`SubscriptionsViewModel`) och `OtherHousehold` (`OtherHomeScreen`,
    att starta delning). Båda har test för gratisversionen.
  - **Byggt men inte spärrat än:** `SharedCosts`, `BreakPlanning`, `KidRewards`, `NutritionOverview` och `ExtraWidgets`.
  - **Inte byggt:** övriga, som spärras när de byggs.
- `Entitlements` finns i `shared/data` (`premium`): `AllUnlocked` är bunden i Koin för alla flavors.
- `BillingRepository` (gränssnitt), `PremiumProducts` (produkt-id:n) och `BillingEntitlements` (köp → `Entitlement`) är
  förberedda men används inte. Inget faktureringsbibliotek finns i bygget, så appen begär ingen faktureringsbehörighet
  och Play visar inte "Köp i appen".

## Att slå på betalning (när det är dags)
1. **Play Console:** betalningsprofil (Google-handlarkonto) och skatteuppgifter. Skapa abonnemangen
   `pusslet_premium_monthly` (39 kr) och `pusslet_premium_yearly` (349 kr), var och en med basplan och 14 dagars
   provperiod.
2. **Bibliotek:** lägg till Play Billing Library (senaste versionen, verifiera i ADR) i `composeApp` och skriv
   `PlayBillingRepository : BillingRepository`. Den använder `BillingClient` med väntande köp påslagna, bekräftar köp
   inom tre dagar och anropar `queryPurchasesAsync` vid start och när appen kommer tillbaka.
3. **Server:** en callable `verifyPurchase` kontrollerar köptoken mot Google Play Developer API. Den skriver
   `families/{familyId}/entitlement` och samma sak på de delade utrymmen familjen listar, så att ett utrymme har premium
   när något av hemmen har det. Bara servern får skriva dokumentet, och det behövs ett nytt regeltest; annars kan vem som
   helst ge sig själv premium via Firestore-API:t (docs/SECURITY.md). Prenumerera på Real-time developer notifications
   (Pub/Sub) för förnyelse, återbetalning och utgång, och uppdatera dokumenten därifrån. Familjer som fanns före
   lanseringen får premium i ett år från lanseringsdagen: skript eller funktion som skriver ett slutdatum, utan köp.
4. **Appen:** en `FamilyEntitlements : Entitlements` läser familjens dokument (så att alla i familjen får premium), med
   `BillingEntitlements` som snabb väg på telefonen som köpte. Bind den bara i `prod`; demo och dev behåller `AllUnlocked`.
5. **Spärrarna:** koppla `SharedCosts`, `BreakPlanning`, `KidRewards`, `NutritionOverview` och `ExtraWidgets` på samma
   sätt som prenumerationerna, med test för gratisversionen.
   - Det som kostar på servern kontrolleras också där: molnbilder i Storage-reglerna, personliga länkar i
     kalenderflödet och rapporter som byggs i funktioner.
   - Lokala spärrar räcker i appen: widgets, teman och barnläge.
6. **Skärmar:** en premiumsida (vad som ingår, pris från `products()`, "Återställ köp", länk till Plays
   abonnemangshantering) och en mjuk hänvisning där en spärr slår till.
7. **Data safety och butik:** lägg till köphistorik i Data safety (PLAY_DATA_SAFETY.md) och nämn premium i
   butikstexten (STORE_LISTING.md). Play märker appen "Köp i appen".
8. **Test:** licenstestare i Play Console, testkort, återbetalning och utgång. Enhetstester för `BillingEntitlements`
   finns redan.
