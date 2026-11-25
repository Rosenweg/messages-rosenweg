# Rosenweg Messenger

SMS-App und Rosenweg-Messenger in einem, für die Smartphones der Siedlung Rosenweg in Kaiseraugst. Neben SMS/MMS zeigt die App die Gruppen, Kanäle und Direktnachrichten des Rosenweg-Messengers (wie in der Web-App). Der Rosenweg-Teil ist auf den geteilten Telefonen aktiv: den Zugang der angemeldeten Person liefert Rosenweg Gerät; ohne Rosenweg Gerät bleibt die App eine normale SMS-App.

## Im Rosenweg

- Gruppen, Kanäle (mit Schreibrecht) und Direktnachrichten; Direktnachrichten sind Ende-zu-Ende-verschlüsselt.
- Antworten, Reagieren, Bearbeiten, Löschen, Bilder und Dateien, "schreibt …" und online, Gelesen-Stand über alle Geräte.
- **Notfall-Alarm:** Nachrichten im Notfall-Kanal (oder mit Priorität Notfall) erscheinen als rotes Vollbild mit Sirene, auch bei "Nicht stören", und werden wiederholt, bis sie gelesen sind.
- Eine dauerhafte, stille Verbindung (Vordergrund-Dienst) hält den Empfang offen; sie startet bei der Anmeldung am geteilten Telefon. Beim Abmelden löscht die App ihre Daten.
- **Rosenweg Updater:** Auf Handys ohne Rosenweg Gerät prüft die App den App-Katalog der Telefonanlage und bietet Aktualisierungen für alle installierten Rosenweg-Apps an. Jede APK wird vorher geprüft (Prüfsumme, Paketname, Version, Rosenweg-Signatur); Android lässt jede Installation bestätigen. Auf geteilten Telefonen übernimmt das Rosenweg Gerät.

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

## Herkunft und Lizenz

Fork von [Fossify Messages](https://github.com/FossifyOrg/Messages). Der Zweig `main` folgt dem Original, die Rosenweg-Anpassungen liegen im Zweig `rosenweg`. Herzlichen Dank an das Fossify-Team und alle Mitwirkenden für die solide Grundlage.

Lizenz: GNU General Public License v3.0, siehe [LICENSE](LICENSE).
