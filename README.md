<p align="center"><img src="assets/logo/rosenweg-messenger-512.png" width="96" alt="Logo"></p>
<h1 align="center">Rosenweg Messenger</h1>
<p align="center">Gruppen, Kanäle und Direktnachrichten des Rosenweg-Messengers auf dem Smartphone – auf den geteilten Telefonen zugleich die SMS-App.</p>
<p align="center"><a href="https://www.rosenweg4303.ch/apps">Herunterladen</a> · <a href="docs/anleitung.md">Anleitung</a></p>

## Inhalt

- [Überblick](#überblick)
- [Plattformen](#plattformen)
- [Rechte und Gruppen](#rechte-und-gruppen)
- [Bedienung](#bedienung)
- [Geteilte Telefone und eigenes Handy](#geteilte-telefone-und-eigenes-handy)
- [Notfall-Alarm](#notfall-alarm)
- [Aufbau](#aufbau)
- [Bauen](#bauen)
- [CI und Release](#ci-und-release)
- [Konfiguration](#konfiguration)
- [Herkunft und Lizenz](#herkunft-und-lizenz)

## Überblick

Der Rosenweg-Messenger wie in der Web-App, als Android-App. Auf den geteilten Telefonen ist die App zugleich die SMS-App.

Was die App kann:

- **Gruppen, Kanäle und Direktnachrichten**; Direktnachrichten sind Ende-zu-Ende-verschlüsselt.
- **Schreiben wie gewohnt:** antworten, reagieren, bearbeiten, löschen, Bilder und Dateien, „schreibt …“ und online.
- **Gelesen-Stand** über alle Geräte der Person.
- **Notfall-Alarm** als rotes Vollbild mit Sirene, auch bei „Nicht stören“.
- **SMS/MMS** – nur auf den geteilten Telefonen.
- **Rosenweg Updater:** hält auf dem eigenen Handy alle Rosenweg-Apps aktuell.

Eine dauerhafte, stille Verbindung (Vordergrund-Dienst, MQTT) hält den Empfang offen.

## Plattformen

| Plattform | Stand |
|---|---|
| Android 8 und neuer (API 26–36) | ja, APK auf [rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps) |
| Geteilte Rosenweg-Telefone | ja, Messenger und SMS-App; Rosenweg Gerät installiert sie (Katalog „immer“) |
| Eigenes Handy | ja, nur Messenger (MQTT), mit eigener Anmeldung |
| Web | die Web-App des Messengers auf rosenweg4303.ch |
| Play Store, F-Droid | nein – nur mit Rosenweg-Signatur |

## Rechte und Gruppen

Die Rechte vergibt der Server nach den Gruppen der Person; die App zeigt nur, was sie darf.

| Wer | darf |
|---|---|
| alle Angemeldeten | Gruppen und Direktnachrichten, in denen sie Mitglied sind; Kanäle lesen; Kanäle mit Schreibrecht beschreiben; eigene Handys abmelden |
| Ausschuss | zusätzlich im Kanal Ankündigungen schreiben |
| Technik, Präsident | zusätzlich im Kanal Notfall schreiben; im Portal unter *Smartphones* die Messenger-Zugänge aller Handys sehen und abmelden |

Quelle: Rosenweg/Website, `pbx/GETEILTE-TELEFONE.md` und `api/routes/messenger-zugang.js`.

## Bedienung

| Schritt | So geht’s |
|---|---|
| Geteiltes Telefon | mit der Karte anmelden – den Messenger-Zugang der Person liefert Rosenweg Gerät. Die Verbindung startet sofort, auch ohne die App zu öffnen. |
| Eigenes Handy anmelden | Beim Öffnen erscheint der gemeinsame Rosenweg-Anmeldeschirm: Rosenweg-Karte hinten ans Telefon halten (NFC) oder „Mit Rosenweg-Konto anmelden“. Keine PIN. Ohne Anmeldung ist die App nicht nutzbar. |
| Neuer Chat | Knopf in der Liste; Gruppen und Kanäle erscheinen von selbst. |
| Abmelden (eigenes Handy) | „Vom Rosenweg Messenger abmelden“ im Menü oder im Portal unter *Smartphones* (auch durch die Technik). Die App löscht dann ihre Rosenweg-Chats und Schlüssel. |
| Verbindungsfehler | Die App wartet 30 s, dann immer länger bis 30 min. Lehnt der Server den Zugang ab (401/403), bittet sie, sich neu anzumelden; die Chats bleiben. |

Ausführlich: [docs/anleitung.md](docs/anleitung.md).

## Geteilte Telefone und eigenes Handy

| | Geteiltes Telefon (mit Rosenweg Gerät) | Eigenes Handy |
|---|---|---|
| Rolle | Standard-SMS-App und Messenger in einem | nur Messenger über MQTT |
| SMS/MMS | ja | nein: keine Rolle „Standard-SMS-App“, keine SMS-Rechte, erscheint nicht in der Auswahl der SMS-Apps; SMS bleiben in der gewohnten App |
| Anmeldung | gilt die Anmeldung von Rosenweg Gerät (Karte am Sperrbildschirm, PIN) | eigene Anmeldung dieser App über den gemeinsamen Anmeldeschirm (Karte oder Rosenweg-Konto, keine PIN); bis zu 20 Sitzungen pro Person |
| Zugang | von Rosenweg Gerät nach dem Anmelden | eigener Zugang für dieses Handy |
| Abmelden | Rosenweg Gerät meldet ab, die App löscht alle Daten der Person selbst (Rechte bleiben) | im Menü oder im Portal |

**Übergang:** War die App auf einem eigenen Handy (frühere Version) Standard-SMS-App, bleibt SMS an, bis man unter *Einstellungen › Apps › Standard-Apps › SMS-App* die gewohnte App wählt; die App weist beim Start darauf hin. Es wird keine SMS gelöscht.

Nach dem Anmelden an einem geteilten Telefon holt der Messenger den Verlauf neu vom Server. Ältere Direktnachrichten zeigen „Vor der Anmeldung an diesem Gerät verschlüsselt“ (neuer Geräteschlüssel); andere Geräte der Person können sie nachliefern.

## Notfall-Alarm

Nachrichten im Kanal Notfall (oder mit Priorität Notfall) erscheinen als rotes Vollbild über allem, mit Sirene über den Wecker-Kanal – auch bei stumm und „Nicht stören“. Je Alarm 30 s, Wiederholung alle 2 min bis „Verstanden“ (gilt als gelesen), höchstens 60 min.

## Aufbau

| Teil | Inhalt |
|---|---|
| `app/src/main/kotlin/org/fossify/messages/` | SMS/MMS-App aus Fossify Messages (Kotlin-Namensraum wie im Original) |
| `…/rosenweg/RwMqttClient.kt`, `MqttCodec.kt`, `MessengerVerbindung.kt`, `RosenwegLive.kt` | MQTT-Verbindung zum Message-Store, Vordergrund-Dienst |
| `…/rosenweg/RosenwegApi.kt`, `RosenwegDatenbank.kt`, `RosenwegSpeicher.kt`, `RosenwegMedien.kt` | REST-Zugriff, lokale Ablage, Bilder und Dateien |
| `…/rosenweg/RwKrypto.kt`, `RwGeraeteSchluessel.kt` | Ende-zu-Ende-Verschlüsselung der Direktnachrichten, Geräteschlüssel |
| `…/rosenweg/RosenwegNotfall.kt`, `NotfallActivity.kt` | Notfall-Alarm |
| `…/rosenweg/RosenwegPrivatAnmeldung.kt`, `RosenwegKonto.kt`, `RosenwegAbmeldung.kt`, `RwZurueckhalten.kt` | Anmeldung, Abmeldung, Zurückhalten bei Fehlern |
| `…/rosenweg/RosenwegSmsModus.kt` | SMS an/aus je nach geteiltem Telefon oder eigenem Handy |
| [commons-rosenweg](https://github.com/Rosenweg/commons-rosenweg) | Fossify Commons in der Rosenweg-Fassung, `ch.rosenweg4303:cloud` (Anmeldung) und `ch.rosenweg4303:updater` |
| `assets/logo/` | Logo aus dem Baukasten von rosenweg-basis: Turm, Weg und Rose mit Sprechblasen-Plakette, dazu ein monochromes Designsymbol (Android 13+) |

**Anmeldung auf dem eigenen Handy:** gemeinsamer Anmeldeschirm `RosenwegAnmeldeSchirm` aus commons-rosenweg (Cloud 0.9.0): Karte (Karten-Sitzung) oder Rosenweg-Konto (Authentik-Client `rosenweg-apps` mit PKCE, `RosenwegAnmeldung`). Mit diesem Token holt die App einmal ein eigener Zugang für dieses Handy (Website `POST /api/messenger/zugang`); damit holt die App laufend kurzlebige Messenger-Tokens. Gespeichert ist nur der Zugang, das Geheimnis mit dem Android-Keystore verschlüsselt.

Paketname `ch.rosenweg4303.messages`.

## Bauen

Zuerst [commons-rosenweg](https://github.com/Rosenweg/commons-rosenweg) ins lokale Maven-Repository veröffentlichen (`mavenLocal()`), dann die App bauen:

```bash
# in commons-rosenweg (Zweig main)
./gradlew -PVERSION=7.1.3-rosenweg.1 publishToMavenLocal

# in diesem Repo (Zweig main)
./gradlew assembleFossRelease
```

Die APK liegt unter `app/build/outputs/apk/foss/release/`. Logo neu erzeugen (rosenweg-basis daneben): `sh assets/logo/erzeugen.sh`.

Mehr: [docs/entwickeln.md](docs/entwickeln.md).

## CI und Release

| Workflow | Auslöser | Ergebnis |
|---|---|---|
| `apps.yml` (Rosenweg) | GitHub-Release veröffentlicht, oder von Hand `gh workflow run apps.yml --ref <tag>` | APK geht an **[rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps)** und in den App-Katalog der Telefonanlage |
| `pr.yml`, `testing-build.yml`, `release.yml` u. a. | aus Fossify übernommen | Prüfungen und Play-Store-Release des Originals; für Rosenweg nicht nötig |

Ablauf einer neuen Version:

1. APK lokal bauen und mit dem Rosenweg-Schlüssel signieren.
2. `gh release create v<versionName> <datei.apk> --notes "Was ist neu"` – Tag = `versionName` der APK, eine APK je Release, Tag auf `main`.
3. `apps.yml` ruft `apps-release.yml` aus Rosenweg/Website auf. Anmeldung per GitHub-OIDC, kein gespeichertes Geheimnis; die Anlage prüft Tag, Prüfsumme, Rosenweg-Signatur und Paketnamen.

## Konfiguration

| Wo | Name | Wozu |
|---|---|---|
| Repo-Wurzel (nicht im Repo) | `keystore.properties` | Rosenweg-Signierschlüssel; Vorlage `keystore.properties_sample` |
| `gradle.properties` | `APP_ID`, `VERSION_NAME`, `VERSION_CODE` | Paketname und Version |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | `commons`, `ch.rosenweg4303:cloud`, `ch.rosenweg4303:updater` | Versionen der Rosenweg-Bibliotheken |

Für `apps.yml` braucht es kein GitHub-Secret (OIDC).

## Herkunft und Lizenz

Fork von [Fossify Messages](https://github.com/FossifyOrg/Messages). Hauptzweig ist `main` mit allen Rosenweg-Anpassungen (früher Zweig `rosenweg`). Herzlichen Dank an das Fossify-Team und alle Mitwirkenden für die solide Grundlage.

Lizenz: GNU General Public License v3.0, siehe [LICENSE](LICENSE).
