# TV Home – TCL C7K

Ein werbefreier, schlichter Home-Screen im Apple-TV-Stil für den **TCL C7K (Google TV 14)**,
gebaut auf [CleanYourTV](https://github.com/Gathin23/cleanyourtv) (MIT-Lizenz).

Was du bekommst:

- Schwarzer Startbildschirm, Dock mit **Netflix, YouTube, Prime Video, Disney+** (5 Spalten; nicht
  installierte Apps werden übersprungen), alle anderen Apps darunter im Raster.
- Uhr, Datum, Begrüßung (Deutsch, folgt der TV-Sprache), Buttons für **Eingänge** (HDMI 1–4, Live TV),
  **WLAN** und **Einstellungen**.
- Keine Werbereihen, keine TCL-Promos, keine Telemetrie-Apps. Nichts wird deinstalliert – alles ist
  mit einem Befehl rückgängig zu machen.

| Datei | Inhalt |
|---|---|
| `tcl-c7k/debloat.txt` | Was abgeschaltet wird (kommentiert, mit optionalen Zeilen) |
| `launcher/` | Der Launcher; C7K-Einstellungen in `launcher/app/src/main/res/values/config.xml` |
| `tools/` | ADB-Client und Skripte (abschalten, wiederherstellen, Theme) |
| `.github/workflows/build-launcher.yml` | Baut die APK auf GitHub (kein JDK nötig) |

---

## Was du brauchst

- Einen Computer im **selben Netzwerk** wie der TV, mit **Python 3** und **git**.
- Für die APK **eins** davon:
  - **A)** nichts – die APK aus GitHub Actions herunterladen (siehe Schritt 5), oder
  - **B)** Docker (`tools/docker-build.sh`), oder
  - **C)** JDK 17–22 und Android SDK Platform 34 + Build-Tools 34.

Alle Befehle laufen im Repo-Ordner. Windows: Jedes `.sh`-Skript hat einen `.ps1`-Zwilling.

### Statt Computer: Android-Handy mit Termux

Geht mit **Termux** (aus F-Droid, nicht die veraltete Play-Store-Version), Handy im selben WLAN.
iPhone geht nicht (Termius ist nur ein SSH-Client, keine lokale Linux-Umgebung).

1. Im Handy-Browser bei GitHub angemeldet herunterladen:
   - Code: `https://github.com/Schubbii/Tv-home-moritz/archive/refs/heads/ccr-76c7c944-szajg6.zip`
   - APK: Repo → Actions → „Launcher-APK bauen“ → letzter grüner Lauf → **tvhome-apk**
2. In Termux:

```bash
pkg update && pkg install python python-cryptography unzip
termux-setup-storage                                   # Zugriff auf "Downloads" erlauben
cd ~ && unzip ~/storage/downloads/Tv-home-moritz-ccr-76c7c944-szajg6.zip
mv Tv-home-moritz-ccr-76c7c944-szajg6 Tv-home-moritz && cd Tv-home-moritz
chmod +x tools/*.sh launcher/*.sh
python -m venv --system-site-packages tools/venv
tools/venv/bin/pip install adb-shell==0.4.4 rsa==4.9.1
mkdir -p launcher/app/build/outputs/apk/release
unzip ~/storage/downloads/tvhome-apk.zip -d launcher/app/build/outputs/apk/release/
```

Danach ab Schritt 1 weiter, aber Schritt 2 **ohne** die Zeilen `git clone` und `python3 -m venv …`,
und in Schritt 5 immer mit `SKIP_BUILD=1`. Termux während der Befehle im Vordergrund lassen.

## 1. TV vorbereiten

1. **Einstellungen → System → Info →** auf **Android TV OS-Build** 7× OK drücken.
2. Zurück → **Entwickleroptionen → USB-Debugging** an.
3. IP-Adresse notieren: **Einstellungen → Netzwerk & Internet →** dein Netzwerk.

Auf dem C7K öffnet USB-Debugging allein schon den Netzwerk-Port 5555.

## 2. Verbinden und Sicherung

```bash
git clone https://github.com/Schubbii/Tv-home-moritz && cd Tv-home-moritz
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=192.168.1.50          # deine TV-IP
PY="tools/venv/bin/python -I"

$PY tools/tv.py --probe              # auf dem TV "USB-Debugging zulassen?" bestätigen
mkdir -p backup
$PY tools/tv.py 'pm list packages'      > backup/packages.txt
$PY tools/tv.py 'pm list packages -d'   > backup/disabled.txt
$PY tools/tv.py 'getprop'               > backup/getprop.txt
$PY tools/tv.py 'settings list global'  > backup/settings-global.txt
$PY tools/tv.py 'settings list secure'  > backup/settings-secure.txt
$PY tools/tv.py 'settings list system'  > backup/settings-system.txt
```

`--probe` sollte Android 14 / SDK 34 melden. `backup/` wird nicht committet.

## 3. WLAN neu verbinden (nur bei WLAN)

Das beim Ersteinrichten gespeicherte WLAN gehört oft dem Einrichtungsassistenten (setupwraith).
Wird der abgeschaltet, ist der TV offline. Prüfen:

```bash
$PY tools/tv.py "dumpsys wifi" | grep -o 'cname=[^ ]*' | sort -u
```

Steht dort `setupwraith`: am TV **Einstellungen → Netzwerk → WLAN entfernen**, dann neu verbinden.
Danach erneut prüfen (jetzt `com.android.tv.settings`). Bei LAN-Kabel ohne gespeichertes WLAN:
in Schritt 4 `FORCE_SETUPWRAITH=1` voranstellen.

## 4. Abschalten (Debloat)

Vorher `tcl-c7k/debloat.txt` durchsehen: Unter **„Optional“** stehen Apps, die standardmäßig
**bleiben** (Assistant, Chromecast, AirPlay, Netflix …). `#` entfernen = wird auch abgeschaltet.

```bash
tools/disable.sh tcl-c7k/debloat.txt
$PY tools/tv.py --screenshot shots/01-debloat.png
```

Danach kurz testen: **Input-Taste** der Fernbedienung (Quellenmenü), ein HDMI-Eingang.

## 5. Launcher installieren

**APK besorgen** – eine Variante:

- **A) GitHub:** Repo → **Actions → „Launcher-APK bauen“** → letzten grünen Lauf öffnen →
  Artifact **tvhome-apk** laden, entpacken und als
  `launcher/app/build/outputs/apk/release/app-release.apk` ablegen.
- **B) Docker:** `tools/docker-build.sh`
- **C) Lokal:** wird vom Installationsskript selbst gebaut (`SKIP_BUILD=1` weglassen).

**Fernbedienungstasten – kurz entscheiden:** Ohne Google-TV-Startseite tun die Tasten
Netflix, YouTube, Prime Video und das Zahnrad nichts mehr. Ein Hilfsdienst (`HOME_KEY_SERVICE=1`)
bringt sie zurück – **Nachteil:** Lautstärke-Halten läuft dann nur noch schrittweise (pro Druck
eine Stufe). Das ist auf dieser Firmware bekannt und nicht abschaltbar.

```bash
# Mit Tasten (empfohlen, wenn ihr die App-Tasten nutzt):
SKIP_BUILD=1 HOME_KEY_SERVICE=1 launcher/build-and-install.sh
$PY tools/tv.py "cmd appops set com.gathin.tvhome AUTO_START allow; cmd appops write-settings; settings put secure accessibility_enabled 1"

# Ohne Tasten (Lautstärke-Halten bleibt normal):
SKIP_BUILD=1 launcher/build-and-install.sh
```

Die zweite Zeile im ersten Block sorgt dafür, dass die Tasten auch **nach einem Neustart** gehen.
Das Skript schaltet die Google-TV-Startseite erst ab, wenn der neue Launcher sicher Home ist,
und rollt sonst zurück.

## 6. Bildschirmschoner (optional)

Google-Ambient zeigt Werbung. Ersetzen durch die Uhr des Launchers:

```bash
$PY tools/tv.py 'settings put secure screensaver_components com.gathin.tvhome/.ClockDream'
$PY tools/tv.py 'pm disable-user --user 0 com.google.android.apps.tv.dreamx'
```

## 7. Neustart und Prüfen

```bash
sleep 20 && $PY tools/tv.py --reboot     # 20 s warten: Android speichert den Zustand verzögert
# nach ~1 Minute:
$PY tools/tv.py 'pm list packages -d --user 0'
$PY tools/tv.py --screenshot shots/02-home.png
```

Prüfen: Home erscheint, Netzwerk da, Input-Taste, HDMI, Tasten auf der **echten** Fernbedienung.

## 8. ADB wieder schließen

Am TV **USB-Debugging aus** (sicherheitshalber auch die Entwickleroptionen), TV neu starten, dann:

```bash
$PY tools/adb-closed.py $TV_HOST        # muss "closed" melden
```

---

## Anpassen

**Dock / Spalten ohne Neubauen** – Ordner mit `config.json` anlegen, z. B. `mein-theme/config.json`:

```json
{
  "dock_apps": ["com.netflix.ninja", "com.google.android.youtube.tv", "com.disney.disneyplus"],
  "columns": 5,
  "greeting_name": "Moritz"
}
```

```bash
tools/theme.sh mein-theme       # übernehmen
tools/theme.sh --reset          # zurück zu den eingebauten Werten
```

Paketnamen findest du mit `$PY tools/tv.py 'pm list packages'`. Alle Schlüssel: `themes/README.md`.
Dauerhaft ändern: `launcher/app/src/main/res/values/config.xml`, dann neu bauen und installieren.

**Kachel verschieben:** OK auf der Kachel gedrückt halten → Verschieben.

**Eine App wieder einschalten:** `$PY tools/tv.py 'pm enable com.paket.name'`

## Alles rückgängig machen

```bash
tools/restore.sh tcl-c7k/debloat.txt com.google.android.apps.tv.launcherx
```

Stellt den Zustand von vor Schritt 4 wieder her, inklusive Google-TV-Startseite.
Bildschirmschoner zurück: `pm enable com.google.android.apps.tv.dreamx` und in den
TV-Einstellungen wieder auswählen.

## Nach einem Firmware-Update

Firmware-Updates bleiben möglich (`com.tcl.ttvs` ist bewusst **nicht** abgeschaltet). Ein Update
kann Apps wieder einschalten. Prüfen und reparieren:

```bash
$PY tools/check.py tcl-c7k/debloat.txt          # zeigt Abweichungen
$PY tools/check.py --fix tcl-c7k/debloat.txt    # schaltet sie wieder ab
```

Tracking ohne Update-Verlust: in den TV-Einstellungen unter **Datenschutz** die
Samba-TV-/„Viewing Information“-Dienste ausschalten.

## Updates des Launchers

Eine neue APK lässt sich nur über die alte installieren, wenn sie mit **demselben Schlüssel**
signiert ist. Docker (Variante B) und lokales Bauen (C) nutzen auf demselben Rechner immer
denselben. Bei GitHub (A) erzeugt jeder Lauf einen neuen – für stabile Updates einmalig das Secret
`DEBUG_KEYSTORE_BASE64` anlegen (Repo → Settings → Secrets → Actions), Inhalt:
`base64 -w0 ~/.android/debug.keystore`.

## Probleme

- **Schwarzer Bildschirm statt Home:** `$PY tools/tv.py 'pm enable com.google.android.apps.tv.launcherx'`
- **Port 5555 zu:** USB-Debugging am TV prüfen; IP korrekt? Siehe `docs/TROUBLESHOOTING.md`.
- **Tasten gehen nach Neustart nicht:** Befehl mit `AUTO_START` aus Schritt 5 erneut ausführen.
- **Bild wirkt auf 1080p skaliert:** `wm size` meldet ab Werk 1920x1080 – das ist normal, nicht ändern.

## Alternative: mit Claude Code am eigenen Rechner

Claude Code lokal im Repo-Ordner starten (gleiches WLAN wie der TV) und schreiben:

> Richte meinen TCL C7K (Google TV 14) nach der README in diesem Repo ein: Backup, WLAN-Prüfung,
> `tcl-c7k/debloat.txt`, Launcher, Bildschirmschoner. Prüfe jeden Schritt mit Screenshots vom TV,
> frag mich vor optionalen Apps und bei der Fernbedienungs-Entscheidung. TV-IP: <IP>.

Die vollständige Vorgehensweise steht im Original:
[PROMPT.md](https://github.com/Gathin23/cleanyourtv/blob/main/PROMPT.md).

## Herkunft

Launcher, Tools und Paketlisten stammen aus
[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) (Stand `ddc3b2c`, MIT, © 2026 Gathin).
Die C7K-Liste basiert auf den dort getesteten Listen `tcl-55c7k-uk` und `tcl-65c7k-google-tv`.
