# DNSpreload
*Being smarter, being faster, being prepared. Preload your unbound!*

## Was das?
DNSpreload dient dem Pre-Caching von Domains.

### Warum?
Wer im eigenen Netzwerk einen DNS-Server betreibt, um bspw. einen Ad-Blocker zu nutzen oder seine Privatsphäre zu erhöhen, der steht vor der Entscheidung für seinen eigenen DNS-Server einen weiteren Upstream-DNS zu nutzen oder diesen direkt per iterativen Anfragen bei den Root- bzw. TLD-Servern anfragen zu lassen. 
Letzteres birgt einen höheren Grad an Privatsphäre in sich, da es keinen Intermediär mehr zwischen den eigenen Anfragen und dem eigentlichen "Root-Walk" gibt. Ersteres ist jedoch schneller, da DNS-Server wie bspw. von Google und Cloudflare deutlich schnellere Responses liefern.

#### Geschwindigkeit
Wieso werden die Queries schneller beantwortet, wenn man bspw. Google oder Cloudflare als Upstream-Server verwendet?
Die Server haben eine breite Ansammlung an Caching-Einträgen und antworten blitzschnell und unmittelbar mit einem nicht-autoritativem Response. Warum sich diese Idee nicht selbst zu Gute machen und die Einträge im DNS-Server bereits cachen **bevor** man diese benötigt? 
Natürlich kann man nicht das ganze Internet cachen. Aber wie wäre es mit dem Anteil, den man davon in 95% der Zeit benutzt?

#### Privatsphäre
Durch diese Maßnahme erhöht mein seine Privatsphäre in puncto DNS-Abfragen auf ein absolutes Maximum. Eine gängige Praxis ist es seine Privatsphäre mit Datensparsamkeit zu schützen. Eine unterschätzte Praxis ist es aber auch seine Daten mit Daten-Flooding zu schützen. Hier kommen beide Strategien zum Tragen:
Aus dem Netzwerk heraus werden täglich tausende Domains nahezu willkürlich vor-gecached. Es ist für Außenstehende Provider und Anbieter nicht ersichtlich, welche davon wirklich benötigt werden und welche nicht. Anschließende DNS-Anfragen der Clients innerhalb des eigenen Netzwerkes für den wirklichen Besuch der Seiten finden nur noch lokal statt, da der DNS-Server jede Anfrage nicht-autoritativ beantworten kann.

## Wie funktioniert das?
Das Script zieht sich einen Dump von Top-Domains in Deutschland, sowie weiteren TLDs und bietet die Möglichkeit dies auch durch eine persönliche Browser-History zu ergänzen. Es werden die 1mio meistgenutzten Domains gezogen, um im Anschluss beliebige Anteile davon im unbound-cache vorzuladen.

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

### 3. Tägliches updaten der Domains & des Caches
Einmal am Tage werden die Top-Domains gezogen und abgelegt und daraufhin erneut per DNS-Query aufgelöst, um Cache-Einträge aktuell und im Cache zu halten.

Beispielsweise:
```
crontab -e
# Hole Top-Domains um 04:15 Uhr
15 4 * * * /home/pi/Scripts/unbound-cache-dumping/preload_topdomains.sh 1
# Starte Preload von Top-Domains sequentiell um 04:30 Uhr
30 4 * * * /home/pi/Scripts/unbound-cache-dumping/preload_topdomains.sh 2
```



### 3. Coming soom
- Crons
- unbound cache dumb
- Browser history
