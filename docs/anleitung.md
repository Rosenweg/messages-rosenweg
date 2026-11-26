# Rosenweg Messenger – Anleitung

## Geteilte und private Telefone

- **Geteilte Rosenweg-Telefone** (mit Rosenweg Gerät): SMS-App und Rosenweg-Messenger in einem. Die App ist Standard-SMS-App; den Messenger-Zugang der angemeldeten Person liefert Rosenweg Gerät.
- **Private Telefone** (ohne Rosenweg Gerät): nur Rosenweg-Messenger über MQTT. Die App bindet sich nicht in SMS/MMS von Android ein: sie fragt weder die Rolle „Standard-SMS-App“ noch SMS-Berechtigungen an, ihre SMS/MMS-Bausteine sind ausgeschaltet (sie erscheint nicht in der Auswahl der SMS-Apps), sie zeigt keine SMS und meldet keine SMS. SMS bleiben in der gewohnten SMS-App.
- **Übergang:** War die App auf einem privaten Telefon (frühere Version) Standard-SMS-App, bleibt SMS an, bis man unter *Einstellungen › Apps › Standard-Apps › SMS-App* die gewohnte App wählt (eine App kann diese Rolle nicht selbst abgeben); die App weist beim Start darauf hin. Es wird keine SMS gelöscht.
- **Anmeldung auf dem privaten Telefon:** Beim Öffnen erscheint der gemeinsame Rosenweg-Anmeldeschirm: Rosenweg-Karte hinten ans Telefon halten oder „Mit Rosenweg-Konto anmelden“ (auch im Menü). Keine PIN; ohne Anmeldung ist die App nicht nutzbar. „Vom Rosenweg Messenger abmelden“ im Menü oder Abmelden im Portal unter *Smartphones* (auch durch die Technik) löscht den Zugang; die App löscht dann ihre Rosenweg-Chats und Schlüssel. Wie die Anmeldung technisch läuft, steht in [entwickeln.md](entwickeln.md#anmeldung-auf-dem-privaten-telefon).

## Was die App kann

- Gruppen, Kanäle (mit Schreibrecht) und Direktnachrichten; Direktnachrichten sind Ende-zu-Ende-verschlüsselt.
- Antworten, Reagieren, Bearbeiten, Löschen, Bilder und Dateien, "schreibt …" und online, Gelesen-Stand über alle Geräte.
- **Notfall-Alarm:** Nachrichten im Notfall-Kanal (oder mit Priorität Notfall) erscheinen als rotes Vollbild mit Sirene, auch bei "Nicht stören", und werden wiederholt, bis sie gelesen sind.
- Eine dauerhafte, stille Verbindung (Vordergrund-Dienst) hält den Empfang offen; sie startet bei der Anmeldung am geteilten Telefon. Beim Abmelden löscht die App ihre Daten.
- **Rosenweg Updater:** Auf Handys ohne Rosenweg Gerät prüft die App den App-Katalog der Telefonanlage und bietet Aktualisierungen für alle installierten Rosenweg-Apps an. Jede APK wird vorher geprüft (Prüfsumme, Paketname, Version, Rosenweg-Signatur); Android lässt jede Installation bestätigen. Auf geteilten Telefonen übernimmt das Rosenweg Gerät.
- **Zurückhalten bei Fehlern (eigenes Handy):** Nach einem Fehler wartet die App 30 s, dann immer länger bis 30 min (mit Zufall), bevor sie es wieder versucht. Lehnt der Server den Zugang ab (401/403), hört sie ganz auf und bittet, sich neu anzumelden; die Chats bleiben.

## Installation

- **Geteilte Rosenweg-Telefone:** Rosenweg Gerät installiert die App automatisch aus dem App-Katalog der Telefonanlage und hält sie aktuell.
- **Eigenes Handy:** APK von [www.rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps) herunterladen und installieren. Danach hält der Rosenweg Updater die Rosenweg-Apps aktuell.

Die App gibt es nicht im Play Store und nicht auf F-Droid. Sie ist mit dem Rosenweg-Schlüssel signiert; Fassungen anderer Herkunft lassen sich nicht darüber installieren.
