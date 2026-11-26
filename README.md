# Rosenweg Messenger

Rosenweg-Messenger für die Smartphones der Siedlung Rosenweg in Kaiseraugst: Gruppen, Kanäle und Direktnachrichten des Rosenweg-Messengers (wie in der Web-App), auf den geteilten Telefonen zusätzlich SMS/MMS.

- **Geteilte Rosenweg-Telefone** (mit Rosenweg Gerät): SMS-App und Rosenweg-Messenger in einem. Die App ist Standard-SMS-App; den Messenger-Zugang der angemeldeten Person liefert Rosenweg Gerät.
- **Private Telefone** (ohne Rosenweg Gerät): nur Rosenweg-Messenger über MQTT. Die App bindet sich nicht in SMS/MMS von Android ein: sie fragt weder die Rolle „Standard-SMS-App“ noch SMS-Berechtigungen an, ihre SMS/MMS-Bausteine sind ausgeschaltet (sie erscheint nicht in der Auswahl der SMS-Apps), sie zeigt keine SMS und meldet keine SMS. SMS bleiben in der gewohnten SMS-App.
- **Übergang:** War die App auf einem privaten Telefon (frühere Version) Standard-SMS-App, bleibt SMS an, bis man unter *Einstellungen › Apps › Standard-Apps › SMS-App* die gewohnte App wählt (eine App kann diese Rolle nicht selbst abgeben); die App weist beim Start darauf hin. Es wird keine SMS gelöscht.
- **Anmeldung auf dem privaten Telefon:** „Mit Rosenweg-Konto anmelden“ (Knopf in der leeren Liste oder im Menü). Die App meldet sich über Authentik an (`rosenweg-apps`, PKCE, Rosenweg Cloud `RosenwegAnmeldung`) und holt einmal einen eigenen Zugang für dieses Handy (Website `POST /api/messenger/zugang`); danach holt sie damit laufend kurzlebige Messenger-Tokens. Gespeichert ist nur der Zugang (Geheimnis mit Android-Keystore verschlüsselt). „Vom Rosenweg Messenger abmelden“ im Menü oder Abmelden im Portal unter *Smartphones* (auch durch die Technik) löscht den Zugang; die App löscht dann ihre Rosenweg-Chats und Schlüssel.

## Im Rosenweg

- Gruppen, Kanäle (mit Schreibrecht) und Direktnachrichten; Direktnachrichten sind Ende-zu-Ende-verschlüsselt.
- Antworten, Reagieren, Bearbeiten, Löschen, Bilder und Dateien, "schreibt …" und online, Gelesen-Stand über alle Geräte.
- **Notfall-Alarm:** Nachrichten im Notfall-Kanal (oder mit Priorität Notfall) erscheinen als rotes Vollbild mit Sirene, auch bei "Nicht stören", und werden wiederholt, bis sie gelesen sind.
- Eine dauerhafte, stille Verbindung (Vordergrund-Dienst) hält den Empfang offen; sie startet bei der Anmeldung am geteilten Telefon. Beim Abmelden löscht die App ihre Daten.
- **Rosenweg Updater:** Auf Handys ohne Rosenweg Gerät prüft die App den App-Katalog der Telefonanlage und bietet Aktualisierungen für alle installierten Rosenweg-Apps an. Jede APK wird vorher geprüft (Prüfsumme, Paketname, Version, Rosenweg-Signatur); Android lässt jede Installation bestätigen. Auf geteilten Telefonen übernimmt das Rosenweg Gerät.
- **Zurückhalten bei Fehlern (eigenes Handy):** Nach einem Fehler wartet die App 30 s, dann immer länger bis 30 min (mit Zufall), bevor sie es wieder versucht. Lehnt der Server den Zugang ab (401/403), hört sie ganz auf und bittet, sich neu anzumelden; die Chats bleiben.
- **Logo:** Rosenweg-Wappen (Turm, Weg, Rose auf Rot) mit dem Zeichen der App rechts unten, erzeugt mit dem Logo-Baukasten aus rosenweg-basis; dazu ein monochromes Designsymbol (Android 13+). Die wählbaren Symbolfarben von Fossify gibt es nicht mehr: alle zeigen dasselbe Rosenweg-Symbol.

## Installation

- **Geteilte Rosenweg-Telefone:** Rosenweg Gerät installiert die App automatisch aus dem App-Katalog der Telefonanlage und hält sie aktuell.
- **Eigenes Handy:** APK von [www.rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps) herunterladen und installieren. Danach hält der Rosenweg Updater die Rosenweg-Apps aktuell.

Die App gibt es nicht im Play Store und nicht auf F-Droid. Sie ist mit dem Rosenweg-Schlüssel signiert; Fassungen anderer Herkunft lassen sich nicht darüber installieren.

## Bauen

Die App baut gegen den Rosenweg-Fork der Fossify Commons und die Rosenweg-Module `cloud`/`updater` (`ch.rosenweg4303:*`). Diese kommen aus dem lokalen Maven-Repository (`mavenLocal()`), also zuerst [commons-rosenweg](https://github.com/Rosenweg/commons-rosenweg) veröffentlichen:

```bash
# in commons-rosenweg, Zweig rosenweg
./gradlew -PVERSION=7.1.3-rosenweg.1 publishToMavenLocal

# in diesem Repo, Zweig rosenweg
./gradlew assembleFossRelease
```

Die APK liegt danach unter `app/build/outputs/apk/foss/release/`. Signiert wird mit `keystore.properties` (Vorlage: `keystore.properties_sample`); die Datei und der Schlüssel liegen nicht im Repo. Paketname: `ch.rosenweg4303.messages`.

Logo neu erzeugen (aus dem Repo-Wurzelverzeichnis, rosenweg-basis daneben): `sh assets/logo/erzeugen.sh`. Welches Zeichen die App trägt, steht in rosenweg-basis `werkzeug/logo/android-apps.txt`.

## Neue Version

APK wie oben bauen und signieren, dann `gh release create v<versionName> <datei.apk> --notes "Was ist neu"` (Tag = versionName der APK, eine APK je Release). Der Workflow `.github/workflows/apps.yml` (wiederverwendbarer Workflow `apps-release.yml` aus Rosenweg/Website) schickt sie an [www.rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps) und in den App-Katalog der Telefonanlage; damit bekommen sie die geteilten Telefone und der Rosenweg Updater. Anmeldung per GitHub-OIDC, kein gespeichertes Geheimnis; die Anlage prüft Tag, Prüfsumme, Rosenweg-Signatur und Paketnamen. Erneut senden: `gh workflow run apps.yml --ref <tag>`.

## Herkunft und Lizenz

Fork von [Fossify Messages](https://github.com/FossifyOrg/Messages). Der Zweig `main` folgt dem Original, die Rosenweg-Anpassungen liegen im Zweig `rosenweg`. Herzlichen Dank an das Fossify-Team und alle Mitwirkenden für die solide Grundlage.

Lizenz: GNU General Public License v3.0, siehe [LICENSE](LICENSE).
