# 🐹 Hamstr

Android-App, die dich benachrichtigt, sobald deine Produkte bei REWE, EDEKA, Lidl, Aldi (oder anderen Märkten)
im Prospekt sind. Läuft komplett auf dem Handy – kein Server, kein Konto.

## Installieren

1. Unter **Releases** (rechts auf der Repo-Seite) die neueste `hamstr-1.0.X.apk` herunterladen – am Handy, im Browser
   oder in der GitHub-App.
2. Antippen → Android fragt, ob der Browser Apps installieren darf → erlauben → installieren.
3. Hamstr öffnen, Benachrichtigungen erlauben, über das Zahnrad oben rechts (**Einstellungen**) die PLZ eintragen.
4. Unter **Produkte** deine Artikel anlegen, dann auf **Ergebnisse → Jetzt prüfen**.

Updates: in der App unter **Einstellungen → Updates → Nach Updates suchen**. Hamstr meldet neue Versionen
außerdem beim täglichen Check. Einstellungen und Produkte bleiben bei Updates erhalten.
Die Update-Funktion setzt voraus, dass dieses Repo **öffentlich** ist.

## Produkte eintragen

| Feld | Wirkung |
|---|---|
| Name | Muss im Angebot vorkommen. Mehrere Schreibweisen mit senkrechtem Strich trennen (`Beerenmix \| Waldbeeren`), eine reicht. **Leer lassen + Marke = alle Artikel der Marke** (z. B. Planted). |
| Marke | Optional, muss dann vorkommen. |
| Größe | `500 g`, `0,5 kg`, `1 l`, `6 x 0,5 l` – wird umgerechnet. Mit „exakt“ nur genau diese Größe, sonst ±15 % mit Hinweis. |
| Max. Preis | Nur melden, wenn der Angebotspreis darunter liegt. |
| Ohne diese Wörter | Sortiert Angebote aus, z. B. `Joghurt, Eis` bei Beeren. |
| Eigene Suchbegriffe | Danach wird bei Marktguru gesucht (alle werden abgefragt). Sonst automatisch Marke + Name. |
| Nur diese Märkte | Überschreibt die Märkte aus den Einstellungen für dieses Produkt. |

Was geprüft und warum etwas verworfen wurde, steht unter **Ergebnisse → Prüfprotokoll anzeigen** (vorher in den Einstellungen über das Zahnrad oben rechts einschalten).

## Wie es funktioniert

- Einmal täglich (Uhrzeit einstellbar) fragt die App bei **Marktguru** die Angebote für deine PLZ ab.
  Android startet den Check im Hintergrund, auch nach einem Neustart.
- Jedes Angebot wird nur einmal gemeldet (pro Markt, Artikel, Angebotswoche und Preis).
- Marktguru hat keine offizielle Schnittstelle; Hamstr nutzt dieselbe wie marktguru.de im Browser. Ändert sie sich,
  meldet die App „Abruf fehlgeschlagen“.

## Entwicklung

GitHub Actions baut bei jedem Push auf `main` die APK, führt die Unit-Tests aus und veröffentlicht ein Release.
Die APK wird mit einem festen Schlüssel signiert, damit Updates sich über ältere Versionen installieren lassen.
Der Schlüssel liegt **nicht** im Repo, sondern in zwei Repository-Secrets:

- `HAMSTR_KEYSTORE_BASE64`: der Schlüssel (Base64-kodierte `.jks`-Datei)
- `HAMSTR_KEYSTORE_PASSWORD`: das Passwort

Fehlen sie, baut der Workflow trotzdem, warnt aber: Dann lässt sich jede neue Version nur nach Deinstallation installieren.
Schlüssel und Passwort gut aufheben – ohne sie kann keine Version mehr über die installierte App aktualisiert werden.
