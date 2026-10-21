# Pusslet – gratis och premium

> Status: **ingen betalning är påslagen.** Varje bygge låser upp allt (`AllUnlocked`, BRIEF §7). Det här är underlaget
> för en senare Play-lansering med premium, och stegen för att slå på den.

## Principer
- **Gratisversionen ska räcka för en vanlig familj.** Det som håller ihop vardagen – kalendern, Idag, listorna,
  matsedeln och att dela med varandra – är gratis. Premium är för familjer med många källor, två hushåll eller ett
  intresse för näring.
- **En spärr stoppar bara något nytt.** Det familjen redan har fortsätter att fungera om premium går ut: prenumerationer
  över gränsen hämtas och visas som förut, men det går inte att lägga till fler. Ingen data raderas eller göms.
- **Ingen funktion får bero på att betalningen fungerar.** Utan Play Store, utan nät eller om Google Play Billing svarar fel
  gäller det familjen senast hade. Demo och dev låser alltid upp allt.
- **Ingen reklam och ingen försäljning av data**, varken i gratis- eller premiumversionen.
- **Premium gäller hela familjen.** En förälder köper och alla i familjen får det, även hjälpare. Det kräver att köpet
  verifieras på servern och skrivs på familjen (se "Att slå på betalning").

## Förslag: gratis och premium

| Område | Gratis | Premium | Varför |
|---|---|---|---|
| Kalender, Idag, lämna/hämta, förfrågningar | Allt | – | Kärnan; utan den är appen inte värd att prova |
| ICS-prenumerationer | 3 kalendrar | Obegränsat | Skola, en förening och förskola räcker för de flesta; den som har fler har störst nytta och kostar mest i hämtningar |
| Familjens eget kalenderflöde | En länk | – | Kostar en funktion per hämtning, men gör appen användbar ihop med jobbets kalender. Följ kostnaden (se nedan) |
| Telefonens kalender, widgets Idag och Inköpslistan | Allt | – | Ingen kostnad för oss, stor nytta i vardagen |
| Fler widgets (t.ex. veckan, matsedeln) | – | Ja | Bekvämlighet utöver det nödvändiga |
| Listor, butiksläge, streckkod, basvaror | Allt | – | Kärnan |
| Matsedel, recept, receptimport, skollunch | Allt | – | Kärnan |
| Näringsöversikt (Livsmedelsverket) | – | Ja | Fördjupning för de intresserade |
| Uppgifter, sysslor, fördelningsvy | Allt | – | Kärnan, och det som gör delningen rättvis |
| Vårdnadsdelning med andra hushållet (V2) | – | Ja | Stort värde för dem som behöver det; byggs i fas 5 |
| Familjer per konto | 1 | 1 | Flera familjer på samma konto är inte byggt; ingen premiumfråga nu |
| Notiser, push, export och radering av data | Allt | – | Integritet och säkerhet är aldrig premium |

Spärrarna finns i koden som `Feature` (`shared/core/.../premium/Entitlement.kt`) och gränsen som
`FreeLimits.SUBSCRIPTIONS`. I dag är bara prenumerationsgränsen kopplad till ett ställe i appen
(`SubscriptionsViewModel`), med test för gratisversionen; de andra kopplas när funktionerna finns.

### Pris (förslag att pröva)
- **Ett abonnemang för familjen**, månad och år: förslagsvis 29 kr/månad och 249 kr/år (cirka två månader gratis).
  Jämför med liknande familjeappar i Play innan beslut; priset sätts per land i Play Console och inkluderar moms.
- **En månads gratis provperiod** via Play (en gång per Google-konto).
- Inget engångsköp för livstid i början: det betalar inte Firebase-kostnaden som växer med familjens användning.

### Kostnader att följa
- Cloud Functions: kalenderflödet (en körning per hämtning, kalendrarna hämtar ungefär varje timme), push och raderingar.
- Firestore: läsningar och skrivningar vid synk. Sätt en budgetvarning (SETUP.md 2.3) och titta på kostnad per aktiv
  familj efter det stängda testet innan priset bestäms.

## Kod i dag
- `Entitlement` och `Feature` i `shared/core` (`premium`), `FreeLimits` med gratisgränserna.
- `Entitlements` i `shared/data` (`premium`): `AllUnlocked` är bunden i Koin för alla flavors.
- `BillingRepository` (gränssnitt), `PremiumProducts` (produkt-id:n) och `BillingEntitlements` (köp → `Entitlement`) är
  förberedda men används inte. Inget faktureringsbibliotek finns i bygget, så appen begär ingen faktureringsbehörighet
  och Play visar inte "Köp i appen".

## Att slå på betalning (när det är dags)
1. **Play Console:** betalningsprofil (Google-handlarkonto) och skatteuppgifter. Skapa abonnemangen
   `pusslet_premium_monthly` och `pusslet_premium_yearly` med basplaner och provperiod.
2. **Bibliotek:** lägg till Play Billing Library (senaste versionen, verifiera i ADR) i `composeApp` och skriv
   `PlayBillingRepository : BillingRepository` (`BillingClient`, väntande köp påslagna, bekräfta köp inom tre dagar,
   `queryPurchasesAsync` vid start och när appen kommer tillbaka).
3. **Server:** en callable `verifyPurchase` som kontrollerar köptoken mot Google Play Developer API och skriver
   `families/{familyId}/entitlement` (bara servern får skriva, nytt regeltest). Prenumerera på Real-time developer
   notifications (Pub/Sub) för förnyelse, återbetalning och utgång, och uppdatera dokumentet därifrån.
4. **Appen:** en `FamilyEntitlements : Entitlements` som läser familjens dokument (så alla i familjen får premium), med
   `BillingEntitlements` som snabb väg på telefonen som köpte. Bind den bara i `prod`; demo och dev behåller `AllUnlocked`.
5. **Skärmar:** en premiumsida (vad som ingår, pris från `products()`, "Återställ köp", länk till Plays
   abonnemangshantering) och en mjuk hänvisning där en spärr slår till, aldrig en vägg mitt i ett flöde.
6. **Data safety och butik:** lägg till köphistorik i Data safety (PLAY_DATA_SAFETY.md) och nämn premium i
   butikstexten (STORE_LISTING.md). Play märker appen "Köp i appen".
7. **Test:** licenstestare i Play Console, testkort, återbetalning och utgång; enhetstester för
   `BillingEntitlements` finns redan.
