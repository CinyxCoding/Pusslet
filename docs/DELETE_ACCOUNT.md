# Radera ditt konto i Pusslet (utkast)

> **Utkast att granska innan publicering.** Fyll i det som står inom [hakparentes]. `firebase deploy --only hosting`
> publicerar avsnitten Svenska och English som `https://<linkHost>/radera/`, men först när inget står inom hakparentes
> (SETUP.md 2.4). Adressen läggs in i Play Console under Data safety (Google kräver sidan även om radering finns i appen).
> Hur en begäran via mejl hanteras står i SETUP.md 2.7. English version below.

---

## Svenska

Här kan du radera ditt konto i appen Pusslet och uppgifterna som hör till det. Det går både i appen och utan den.

### I appen
Öppna **Integritet**, som finns under Mer eller under Inställningar och hjälp:
- **Radera mitt konto** tar bort ditt konto och din koppling till familjen. De andra behåller familjen.
- **Radera familjen för alla** tar bort familjen och allt i den, för alla. Bara föräldrar kan göra det.

### Utan appen
Du behöver inte installera appen igen. Mejla [e-postadress] från adressen du loggar in med och skriv "Radera mitt konto".
Vi raderar kontot inom 30 dagar och bekräftar när det är gjort.

Är du den enda föräldern i en familj där andra har konton frågar vi först vad som ska hända med familjen. Vi kan radera
den för alla, eller så gör du någon annan till förälder i appen först.

Gick du med via en inbjudan utan att skapa ett konto finns ingen e-postadress kopplad till dig. Radera då i appen, eller
be en förälder i familjen att ta bort dig.

### Det här raderas
- Ditt konto: e-postadressen, inloggningen och, om du loggar in med Google, namnet från Google-kontot.
- Dina telefoners notis-token och din koppling till familjen.
- Kalenderlänkar du har skapat.
- Är du ensam i familjen raderas också familjen och allt i den.
- Väljer du "Radera familjen för alla" raderas familjen, allt i den och dess inbjudningar, kalenderlänkar och delade
  överlämningar, för alla.

### Det här finns kvar
- Familjen fortsätter för de andra. Ditt namn finns kvar i familjen som en person utan konto, tillsammans med det du har
  lagt in, tills en förälder tar bort det.
- Delar ni barnen med ett annat hem behåller det hemmet barnens saker.
- När en familj har raderats finns en markering kvar på servern, med bara familjens id och tiden för raderingen, så att en
  telefon som varit avstängd inte kan skapa familjen på nytt.
- Säkerhetskopior hos Google rensas enligt Googles rutiner. Det som finns på din telefon försvinner när du avinstallerar
  appen.

Mer om hur uppgifterna hanteras står i [integritetspolicyn](/privacy/).

---

## English

Here you can delete your account in the Pusslet app and the data that belongs to it, either in the app or without it.

### In the app
Open **Privacy**, under More or under Settings and help:
- **Delete my account** removes your account and your link to the family. The others keep the family.
- **Delete the family for everyone** removes the family and everything in it, for everyone. Only parents can do this.

### Without the app
You don't need to install the app again. Email [email address] from the address you sign in with and write "Delete my
account". We delete the account within 30 days and confirm when it is done.

If you are the only parent of a family where others have accounts, we first ask what should happen with the family. We can
delete it for everyone, or you first make someone else a parent in the app.

If you joined with an invite without creating an account, no email address is linked to you. Delete in the app, or ask a
parent in the family to remove you.

### What is deleted
- Your account: the email address, the sign-in and, if you sign in with Google, the name from the Google account.
- Your phones' notification tokens and your link to the family.
- Calendar links you have created.
- If you are alone in the family, the family and everything in it are deleted too.
- If you choose "Delete the family for everyone", the family, everything in it and its invites, calendar links and shared
  handovers are deleted, for everyone.

### What stays
- The family carries on for the others. Your name stays in the family as a person without an account, together with what
  you have added, until a parent removes it.
- If you share the children with another home, that home keeps the children's things.
- When a family has been deleted, a marker stays on the server with only the family's id and the time of the deletion, so
  that a phone that has been switched off cannot create the family again.
- Backups at Google are cleared according to Google's routines. What is on your phone goes away when you uninstall the app.

More about how the data is handled is in the [privacy policy](/privacy/).

---

## Att hålla sidan sann
Uppdatera sidan i samma ändring som `functions/src/privacy.ts` (vad som raderas) eller Integritet i appen ändras. Slås
betalning på: säg att en prenumeration avslutas i Google Play, eftersom den inte försvinner med kontot.
