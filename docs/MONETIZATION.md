# Intäktsmodell – förslag

> Ingen betalning är aktiv. `Entitlements` låser upp allt, och ingen funktion får bero på att betalning fungerar
> (BRIEF §7). Det här är underlag för ägaren inför en Play-lansering.

## Princip
Gratisversionen ska vara **hela vardagen för en familj** – annars rekommenderar ingen appen vidare. Premium tar betalt för
sådant som kostar oss pengar att driva (servertid, push, lagring) eller som främst behövs av familjer med mer komplexa
liv (två hushåll, många externa kalendrar). Barnens säkerhet och integritet är aldrig premium: export, radering,
allergivarningar och tysta timmar är alltid gratis.

## Gratis
- Kalender (alla vyer, återkommande händelser, helgdagar, veckonummer), lämna/hämta med förfrågningar och notiser
- Idag-vyn med väder, klädråd och ska-med-idag
- Snabbinmatning, uppgifter och sysslor med rotation, fördelningsvyn
- Matsedel, receptbibliotek och receptimport, skollunch
- Inköpslistor med butiksordning, "jag handlar nu", streckkodsskanning
- **1 familj**, obegränsat antal vuxna och barn
- **2 ICS-prenumerationer** (t.ex. skolan och fotbollen), egen telefonkalender åt båda hållen
- Idag-widgeten
- Export (JSON) och radering, alltid

## Premium (förslag: 29 kr/månad eller 249 kr/år per familj – en betalar, alla i familjen får det)
| Funktion | Varför premium |
|---|---|
| Obegränsade ICS-prenumerationer | Varje flöde hämtas och tolkas regelbundet; familjer med många aktiviteter har störst nytta |
| Familjekalendern i jobbets kalender (eget ICS-flöde) | Kör på Cloud Functions för varje hämtning (Outlook/Google hämtar ofta) |
| Vårdnadsdelning med andra hushållet | Kräver flerhushållsmodellen (fas 5) och mer synk |
| Näringsöversikt per vecka | Livsmedelsdatabasen, tydligt "extra" värde |
| Inköpslista-widgeten och fler widgetstorlekar | Bekvämlighet, inte kärnfunktion |
| Bilder delade med hela familjen (Storage) | Lagring kostar; i dag ligger bilder bara på telefonen (ADR-025) |
| Teman/fler medlemsfärger | Ren bonus |

**Varför inte "1 vuxen gratis"?** Appens värde är samarbetet; att låsa den andra föräldern bakom betalvägg gör att
familjen aldrig upptäcker poängen.

**Provperiod:** 30 dagar premium för nya familjer, utan kortuppgifter (Play stödjer gratisperiod på prenumerationer).
När den tar slut stängs inget av som familjen redan skapat: prenumerationer utöver två pausas (visas som "pausad"),
flödeslänkar slutar uppdateras men raderas inte.

## Teknisk förberedelse i appen
- `se.pusslet.core.entitlements.Feature` – listan ovan som enum.
- `Entitlements` (`isPremium`, `features`, `has(feature)`) – i dag `AllUnlockedEntitlements` som alltid svarar ja.
- `BillingRepository` – gränssnitt för Google Play Billing (produkter, köp, återställ, kvitton) med implementationen
  `NoBilling`. TODO-markeringar visar var Play Billing Library och serververifiering (Cloud Function som validerar köp
  mot Play Developer API och sätter `families/{id}.premiumUntil`) ska in.
- Gränser (t.ex. 2 prenumerationer) kontrolleras via `Entitlements` i ViewModels – inte i UI-koden – så att de kan slås
  på centralt och testas.

## Inför aktivering (checklista)
1. Skapa prenumerationsprodukten i Play Console (månad + år, gratisperiod, länder).
2. Lägg till Play Billing Library i `composeApp`, implementera `BillingRepository` och ersätt `AllUnlockedEntitlements`
   med en implementation som läser `premiumUntil` från familjen (satt av servern, aldrig av klienten).
3. Cloud Function för kvittoverifiering och Real-time Developer Notifications (Pub/Sub) för förnyelse/avslut.
4. Uppdatera reglerna så att klienter inte kan skriva `premiumUntil`; regeltest.
5. Uppdatera butikstexten (STORE_LISTING) och integritetspolicyn (köphistorik hanteras av Google Play).
