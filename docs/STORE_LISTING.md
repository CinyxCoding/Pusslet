# Play-butiken: texter och material

> Utkast till butikssidan (Play Console → Grow → Store presence → Main store listing). Svenska är huvudspråk, engelska
> läggs till som översättning. Gränser: namn 30 tecken, kort beskrivning 80 tecken, lång beskrivning 4 000 tecken.
> Play tillåter inga omdömen om sig själv ("bästa", "nr 1") eller uppmaningar i namn och kort beskrivning.

## Svenska

**Appnamn (≤ 30):** Pusslet – familjens vardag

**Kort beskrivning (≤ 80):** Kalender, lämna och hämta, listor och matsedel för hela familjen på ett ställe.

**Lång beskrivning:**

Pusslet samlar familjens vardag på ett ställe: vem som lämnar och hämtar, vad som ska med, vad ni äter och vad som ska
handlas. Alla i familjen ser samma sak, direkt, även utan nät.

IDAG
• Dagens händelser för hela familjen, vem som lämnar och hämtar och vad som ska med
• Väder och klädråd för barnen, skollunchen och kvällens middag
• Morgonkoll och kvällskoll som notis, om du vill

KALENDER
• Dag, vecka, månad och lista, med veckonummer och svenska helgdagar
• Prenumerera på skolans, föreningens eller förskolans kalender (ICS)
• Visa telefonens egna kalendrar och se krockar direkt
• Skriv familjens händelser till telefonens kalender, eller dela en länk till jobbets kalender
• Växelvis boende: varannan vecka, 2-2-5-5 eller 2-2-3

LÄMNA OCH HÄMTA
• Fråga om någon kan hämta, och få svar som notis
• ”Hämtad” med ett tryck, så att de andra vet – och en varning om ingen har bockat av
• Hjälpare, som morfar eller barnvakten, ser bara det ni delar och kan svara ja eller nej
• Sjukt barn? Ett tryck ställer in lämning, hämtning och aktiviteter, säger till de andra och håller koll på VAB

MAT OCH HANDLA
• Veckans matsedel med recept, och importera recept från en länk eller en hel veckomeny
• Inköpslistan skapas från matsedeln och slås ihop smart
• Butiksläge sorterat efter avdelning, streckkodsläsare och basvaror
• Se när någon handlar och lägg till på listan medan de är i butiken

UPPGIFTER
• Uppgifter och sysslor som turas om, och en vy över hur ni har delat

PÅ HEMSKÄRMEN
• Widgets för Idag och inköpslistan

INTEGRITET
• Barn behöver bara ett förnamn
• Familjens uppgifter sparas inom EU
• Ingen reklam, ingen försäljning av data, ingen AI
• Exportera allt eller radera familjen direkt i appen

Väder från SMHI, skolor från Skolverket, livsmedelsdata från Livsmedelsverket (CC BY 4.0) och produktdata från Open Food
Facts (ODbL).

## English

**App name (≤ 30):** Pusslet – family planner

**Short description (≤ 80):** Calendar, drop-offs and pick-ups, lists and meal plans for the whole family.

**Full description:**

Pusslet brings the family's everyday life into one place: who drops off and picks up, what to bring, what you're eating
and what to buy. Everyone in the family sees the same thing, right away, even offline.

TODAY
• The whole family's day, who drops off and picks up, and what to bring
• Weather and clothing advice for the children, school lunch and tonight's dinner
• Morning and evening check-ins as notifications, if you like

CALENDAR
• Day, week, month and list views, with week numbers and Swedish public holidays
• Subscribe to the school's, club's or preschool's calendar (ICS)
• Show the phone's own calendars and spot clashes at once
• Write the family's events to the phone's calendar, or share a link with your work calendar
• Shared custody: alternating weeks, 2-2-5-5 or 2-2-3

DROP-OFF AND PICK-UP
• Ask if someone can pick up, and get the answer as a notification
• "Picked up" with one tap so the others know – and a warning if nobody has ticked it off
• Helpers, like grandpa or the babysitter, only see what you share and can answer yes or no
• Sick child? One tap cancels drop-offs, pick-ups and activities, tells the others and keeps track of VAB

FOOD AND SHOPPING
• The week's meal plan with recipes; import recipes from a link or a whole weekly menu
• The shopping list is made from the meal plan and merged smartly
• Store mode sorted by aisle, barcode scanner and staples
• See when someone is shopping and add to the list while they're in the store

TASKS
• Tasks and chores that take turns, and a view of how you've shared them

ON THE HOME SCREEN
• Widgets for Today and the shopping list

PRIVACY
• Children only need a first name
• The family's data is stored in the EU
• No ads, no selling of data, no AI
• Export everything or delete the family right in the app

Weather from SMHI, schools from Skolverket, food data from the Swedish Food Agency (CC BY 4.0) and product data from Open
Food Facts (ODbL).

## Material och uppgifter att fylla i

| Vad | Krav | Status |
|---|---|---|
| Appikon | 512 × 512 px, PNG, 32-bit | Exportera från `composeApp/src/main/res` (adaptiv ikon) |
| Funktionsgrafik | 1 024 × 500 px, JPG eller 24-bit PNG | Att göra |
| Skärmdumpar, telefon | 2–8 st, 16:9 eller 9:16, 320–3 840 px | Idag, Kalender (vecka), Lämna/hämta-fråga, Inköpslista i butiksläge, Matsedel, Widgets. Ta dem i demo-flavorn med exempelfamiljen, ljust tema, svenska; samma i engelska för den engelska sidan |
| Skärmdumpar, surfplatta | Valfritt | Kan vänta |
| Kategori | | Föräldraskap (Parenting) |
| Taggar | Upp till 5 | Familj, Kalender, Planering, Inköpslista, Recept |
| Kontakt-e-post | Krävs | [e-postadress] |
| Webbplats | Valfritt | [webbadress] |
| Integritetspolicy | Krävs, öppen URL | Publicera PRIVACY.md, t.ex. `https://[din-domän]/integritet` |
| Innehållsklassificering | Frågeformulär | Ingen våldsam eller sexuell text, inga köp, användare kan dela innehåll med sin familj (ej offentligt) → väntas bli PEGI 3 / Alla |
| Målgrupp | | 18+ (appen är för föräldrar, se PLAY_DATA_SAFETY.md) |
| Annonser | | Nej |
| Data safety | | PLAY_DATA_SAFETY.md |
