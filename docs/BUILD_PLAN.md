# Byggplan: idéer från HouseholdApp

> Status: **våg 1 är byggd (ADR-065), resten är förslag.** Skriven 2026-11-05 efter en genomläsning av
> [Iejenn/HouseholdApp](https://github.com/Iejenn/HouseholdApp) (commit ce2cbb6) och Pusslets kod (25fa63b). Jag har läst
> koden i HouseholdApp men inte kört appen.

**Licens:** HouseholdApp har ingen licens. Idéerna går att ta efter, men koden får inte kopieras utan lov. Den är dessutom
TypeScript och React, så allt här skrivs nytt i Pusslets arkitektur. Texter och listor skrivs också nya, på svenska.

## Skillnaderna

HouseholdApp är en webbapp (React och Firebase) för två vuxna: sysslor, inköp, veckomeny, lappar och en digital katt som
matas med poäng från sysslorna. Pusslet är en Android-app för hela barnfamiljen, med roller, hjälpare och två hem. Pusslet
är mycket större, men HouseholdApp har några enkla saker som saknas här.

| Område | HouseholdApp | Pusslet | Slutsats |
|---|---|---|---|
| Plattform | Webb: iPhone, Android och dator | Bara Android, iOS är en stomme | Största skillnaden, ägarens beslut (se nedan) |
| Familjen | Högst två personer | Föräldrar, hjälpare, andra hemmet | Pusslet |
| Sysslor | Färdig lista per rum, flera på en gång | Skrivs in en och en | **Ta med** (steg 1) |
| Upprepning | Räknas från när det senast gjordes | Fast schema (RRULE), turordning, Byt vecka | **Ta med som tillval** (steg 5) |
| "Senast gjord" | Visas på varje syssla | Bara "Klart" senaste veckan | **Ta med** (steg 5) |
| Fördelning | Poäng per person i veckopush | "Så har vi delat", utan poäng | Pusslet, poäng strider mot ADR-060 |
| Tacka för en syssla | Hjärta | Saknas | **Kanske** (steg 8) |
| Be någon ta en syssla | "Por", med spärr på 10 minuter | Bara för lämna och hämta | **Ta med** (steg 8) |
| Korta meddelanden | Lappar på startsidan, med reaktioner och svar | Saknas | **Ta med** (steg 7) |
| Bortrest | Semesterläge, tystar påminnelser | Saknas, packregler ropar även under semestern | **Ta med** (steg 4) |
| Veckoöversikt | Push på måndag | Bara morgon- och kvällskoll | **Ta med i annan form** (steg 3) |
| Försenat i morgonnotisen | Ja | Nej | **Ta med** (steg 3) |
| Prova notiser | Knapp "Stuur testmelding" | Saknas, bara en vanlig fråga | **Ta med** (steg 2) |
| Nytt i appen | Visas en gång efter uppdatering | Saknas | **Ta med** (steg 6) |
| Fast rätt per veckodag | Stöd i koden, avstängt | Favoritveckor | Kanske senare |
| Väder | 7 dagar | Idag och imorgon, med klädråd per barn (SMHI) | Kanske senare |
| Inköp | Förslag, "för Risotto", hela veckan till listan | Allt det, plus butiksläge, gångar, skafferi, streckkod | Pusslet |
| Mat | Recept med ingredienslista | Import, näring, balans, förslag, skollunch | Pusslet |
| Kalender | Google Kalender (bara läsa, via OAuth) | ICS, telefonens kalender åt båda håll, eget flöde | Pusslet |
| Push | Serverns text, språk per mottagare | Data-push, telefonen skriver texten på läsarens språk | Pusslet |

## Plan

Insats anges som i MONETIZATION.md: liten, medel eller stor. Allt är gratis om inget annat står, eftersom det håller ihop
vardagen. Varje steg följer projektets regler: ADR (nästa nummer är 065), tester, texter på svenska och engelska, PROGRESS.md.

### Våg 1: före lanseringen (byggd 2026-11-05, ADR-065)
Små steg som gör första intrycket bättre. Ingen ny datatyp och ingen ändring på servern, så de fungerar på Spark. Inget av
det är provat på en riktig telefon än.

**Steg 1. Färdiga sysslor** (liten, klar)
- **Mål:** en ny familj får in sina sysslor på under en minut, utan att skriva.
- **Byggt:**
  - `core/tasks/ChoreCatalog.kt`: 28 sysslor i sju grupper, med förvald upprepning. Gruppen Barnen är ny jämfört med
    HouseholdApp.
  - `feature-tasks/catalog/`: en egen skärm i stället för ett ark, eftersom listan är lång. Man väljer flera och väljer vem
    (ingen, en förälder, turas om). Sysslorna blir vanliga `FamilyTask`.
  - Nås från tomma listan, från raden "Vanliga sysslor" i Uppgifter och från en orörd Ny uppgift.
  - Första datum är kommande lördag. Sysslor med samma upprepning delar på lördagarna i sin runda.
  - Bekräftelse med Ångra, som tar bort alla på en gång. Det familjen redan har kan inte läggas till igen. Guiden nämner listan.

**Steg 2. Testnotis** (liten, klar)
- **Mål:** den som undrar om notiserna fungerar får svar direkt. Behövs också för att prova riktiga telefoner före
  lanseringen (SETUP.md 3.3).
- **Byggt:** "Skicka testnotis" under Inställningar › Notiser, och i hjälparens inställningar. Stoppar telefonen notisen säger
  appen det och öppnar telefonens inställningar. Vanliga frågan "Jag får inga notiser" pekar hit.
- **Gräns:** testnotisen visar att notiser kan visas, inte att planerade kommer i tid, eftersom batteriinställningarna kan
  hålla tillbaka dem.

> Utanför den här planen, men gjort före lanseringen: Google-knappen säger nu till när inloggningen misslyckas (ADR-066).

### Våg 2: första uppdateringen
Bara lokal logik och nya fält, så det fungerar på Spark.

**Steg 3. Veckokoll och försenat på morgonen** (liten till medel)
- **Mål:** på söndagskvällen veta vad som behöver lösas innan veckan börjar.
- **Bygg:**
  - `ReminderPlanner`: notisen "Nästa vecka: 2 hämtningar utan ansvarig · 1 krock · 3 middagar oplanerade" söndag 18:00,
    för föräldrar och bara när något behöver lösas. Tysta timmar och Bundling gäller som vanligt.
  - Middagar räknas bara med `Feature.Meals`.
  - `NotificationKind.WeekAhead("pusslet.week")`, `Prefs.WeekAhead` och val under Inställningar › Notiser. Öppnar nästa
    vecka i kalendern.
  - Morgonkollen får raden "2 försenade sysslor".
- **Medvetet annorlunda:** HouseholdApp visar vem som gjort mest. Det gör inte Pusslet: "För samtal, inte skuld" (BRIEF §4.7).
- **Tester:** inget att lösa ger ingen notis, hjälpare får ingen, sommartid, tysta timmar, utan premium.

**Steg 4. Semesterläge** (medel)
- **Mål:** en knapp som tystar vardagens tjat när familjen är borta. I dag fortsätter "Gympapåse" varje onsdag även på
  semestern, eftersom `PackingPlanner.matchesWeekly` bara ser veckodagen.
- **Bygg:**
  - `FamilySettings`: från och till-datum, synkat till båda föräldrarna.
  - Under pausen tystnar Morgonkoll, Kvällskoll och Veckokoll. Veckovisa packregler visas inte, sysslor blir inte försenade
    och påminner inte.
  - **Pausas aldrig:** medicin, sjukdagar, påminnelser man själv satt på en händelse, lämna och hämta, förfrågningar.
  - Rad på Idag: "Semesterläge till 12 juli" med knappen "Vi är hemma".
- **Berör:** `TodayPlanner`, `ReminderPlanner`, `TaskScheduler`, reglerna för `settings`.
- **Tester:** medicin fortsätter, datum som passerats, två föräldrar samtidigt, hjälpare.

**Steg 5. Senast gjord och "när det är dags igen"** (medel)
- **Mål:** sysslor utan fast dag, som att rengöra ugnen. Görs den tre veckor sent ska nästa gång räknas därifrån.
- **Bygg:**
  - Raden "Senast tisdag · Sara" på sysslor, från `completions` (liten, kan göras för sig).
  - Upprepningsvalet "Efter senast gjord: var N:e dag, vecka eller månad". Nytt fält på `FamilyTask`.
    `TaskScheduler` räknar nästa datum från senaste avbockningen. Ångra fungerar som förut, eftersom `completions` har kvar
    historiken.
  - Katalogen i steg 1 använder valet där det passar.
- **Berör:** `TaskScheduler`, `TaskRepeats`, `TaskCompletion`, `TaskEditorScreen`, `validTask` i reglerna.
- **Tester:** `TaskSchedulerTest` (försenad, ångrad, månadsskifte), regeltest, att fördelningsvyn räknar som förut.

**Steg 6. Nytt i appen** (liten)
- **Mål:** familjen ser vad som är nytt. Byggs med första uppdateringen: först då finns något att visa.
- **Bygg:** lista per version i `core`, ruta en gång efter uppdatering (inte vid första start, då visas guiden) och raden
  "Nytt i appen" under Hjälp. `Prefs` för det som är sett, med i exporten. Väjer för en öppnad länk, som guiden gör.

### Våg 3: kräver Blaze för push
Här är lappar den enda nya datatypen. Utan Blaze fungerar stegen, men utan notis till de andra.

**Steg 7. Lappar** (stor)
- **Mål:** "Glöm inte paketet" hamnar i appen i stället för i ett sms. HouseholdApp ersatte sin chatt med lappar, så det här
  är inte en chatt.
- **Bygg:**
  - Text upp till 300 tecken, som gäller till: idag, imorgon, en vecka eller tills den tas bort. Gamla lappar försvinner
    själva, vilket HouseholdApp inte har.
  - En reaktion per person från en kort lista, och korta svar upp till 80 tecken.
  - "Visa för hjälpare" är avstängt från början. Delas inte med andra hemmet (ADR-059).
  - `TodayBlock.Notes` på Idag, som går att dölja i Idag-layouten. Lugn stil enligt DESIGN.md: ingen handstil, inga sneda lappar.
- **Data:**
  - `core/model/FamilyNote.kt`, `Collections.Notes` med `mapFields` för reaktioner och svar och `caregiverFilterField`.
  - `firestore.rules` med `validNote`, plus `functions/test/rules.test.ts`.
  - Export, radering av familj och rensning är generella och tar med den nya samlingen. **Kontrollera:** vad som händer med
    `createdBy` när ett konto raderas.
- **Push:** `pushOnNote` och `NotificationKind.Notes("pusslet.notes")`. Som övrig push skickas id:n, inte lappens text.
- **Dokument:** PRIVACY.md (fritext kan innehålla känsliga uppgifter), PLAY_DATA_SAFETY.md, guiden, vanliga frågor.

**Steg 8. "Kan du ta den?" och Tack** (liten till medel)
- **Kan du ta den?** på en öppen uppgift: en fråga till en annan vuxen, med samma mönster som "Kan du ta hämtningen?".
  "Jag tar den" sätter ansvarig. Spärr på servern så att samma uppgift inte kan skickas om och om igen. Använder kanalen
  `Requests`.
- **Tack** (ägarens val, se nedan): en knapp på raderna under "Klart", som visar "Johan tackade". Räknas aldrig i "Så har vi delat".
- **Berör:** `FamilyTask`, `validTask`, `push.ts` med tester.

### Kanske senare
- **Fast rätt per veckodag** (liten till medel, premium: `Feature.Meals`): "Tacos på fredagar" fylls i först av
  `WeekFiller`. Favoritveckor täcker en del av behovet i dag.
- **Väder för veckan** (liten): en rad per dag med regnrisk, till exempel inför ett kalas. Idag och imorgon finns redan.

## Det vi inte tar med

| Idé | Varför |
|---|---|
| Digital katt, poäng och butik | Principen "vuxen, inte barnslig" (BRIEF), och stjärnorna togs bort av ägaren (ADR-060). Stort: katten är ensam omkring 60 kB kod, plus serversummor |
| Poäng per person i veckopushen | "nothing is scored or compared" (`TaskDistribution`). "Så har vi delat" täcker behovet |
| Chatt | HouseholdApp tog själv bort sin. Lappar räcker |
| Google Kalender med OAuth | Telefonens kalender når redan Google och Outlook, utan token på servern och Googles granskning |
| Handstil och sneda lappar | "Lugn i kaoset": passar två personer, inte en familjeapp |
| Svep för att ta bort | Knappar med Ångra finns och går att upptäcka. Kan prövas senare för inköpslistan |
| Sysslor grupperade per rum | Pusslets "Mina, Ingen ansvarig, Familjen" svarar bättre på vad jag ska göra |
| Summeringar på servern | Behövs inte när telefonen har all data lokalt (Room) |
| Webbens uppdateringslogik | Play sköter uppdateringar av en Android-app |

## Ägarens beslut

1. **iPhone.** Det här är den största skillnaden. HouseholdApp fungerar på alla telefoner eftersom den är en webbsida. I
   Pusslet kan en förälder med iPhone inte vara med: inbjudningssidan säger att appen bara finns för Android, och det enda
   som fungerar är familjens kalenderlänk (kräver Blaze). Alternativen är att lansera för Android och se vad testarna säger,
   att bygga iOS-appen (kräver Mac, Apple-konto och en egen Firestore-adapter, ADR-006) eller att göra en enklare webbvy. Jag föreslår det första, och att fråga testarna hur många som har en iPhone i familjen.
2. **Tack.** Omtänksamt för vissa, men en liten påminnelse om vem som gjorde vad för andra. Bygg det bara om du själv skulle
   vilja ha det.
3. **Semesterläge och lov.** Steg 4 går att använda även för sportlov. Att packregler tystnar av sig själva under lov är ett
   eget beslut, eftersom barn på fritids ofta behöver sakerna ändå.
