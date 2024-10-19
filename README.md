# DNSpreload
*Being smarter, being faster, being prepared. Preload your unbound!*

## Was das?
DNSpreload dient dem Pre-Caching von Top-Domains in Deutschland und bietet die Möglichkeit dies auch an persönlichen Browser-Histories orientiert zu gestalten.
Dazu werden die 1mio meistgenutzten Domains gezogen, um im Anschluss beliebige Anteile davon im unbound-cache vorzuladen.

Das Script kann von jedem Client im Netz mit funktionierender Bash ausgeführt werden, es empfiehlt sich jedoch deutlich das Script auf dem unbound-Server selbst auszuführen, um die Flut an DNS-Abfragen lokal zu initiieren und nicht unnötigerweise durch das lokale Netz zwischen Client & Server zu senden.

## HowTo
### 1. Variablen anpassen
Das Skript "preload_topdomains.sh" muss um folgende Variablen angepasst werden:
```
SCRIPT_PATH=<PFAD-IN-WELCHEM-SKRIPT-LIEGT>
LOG_FILE=<ABSOLUTER-PFAD-ZUM-LOGFILE>
ANZAHL_DE_DOMAINS=<WIE-VIELE-DE_DOMAINS-CASHEN>
ANZAHL_REST_DOMAINS=<WIE-VIELE-NICHT_DE_DOMAINS-CASHEN>
```

### 2. Funktion aufrufen
Das Skript verfügt über die folgenden 3 Funktionen:

1. Updaten der Top-Domains
2. Sequentieller Preload vorbereiteter Top-Domains
3. Paralleler Preload vorbereiteter Top-Domains

#### Updaten der Top-Domains
`./preload_topdomains.sh 1` oder in der interaktiven Abfrage `1` wählen.
Es werden die aktuellesten Top-Domains gezogen, abgelegt und folgende Dateien abgelegt:
```
top-1m.csv
top-1m.txt
DEall.txt
DEtop<ANZAHL>.txt
RELEVANTall.txt
RELEVANTtop<ANZAHL>.txt
mergedDomainsToCache.txt
```

#### Sequentieller Preload
`./preload_topdomains.sh 2` oder in der interaktiven Abfrage `2` wählen.
Es wird eine sequentielle DNS-Auflösung aller vorbereiteter Domains durchgeführt.

#### Paralleler Preload
`./preload_topdomains.sh 3 <NO-OF-THREADS>` oder in der interaktiven Abfrage `3` wählen.
Es wird in der übergebenen Anzahl an Threads eine parallele DNS-Auflösung aller vorbereiteter Domains durchgeführt.

### 3. Coming soom
- Crons
- unbound cache dumb
- Browser history
