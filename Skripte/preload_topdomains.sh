#!/bin/bash
# Copyright® 2024 BerziOnline
# 
# DNSpreload v1.0


# VARIABLEN
SCRIPT_PATH="/home/pi/Scripts/unbound-cache-dumping/"
cd $SCRIPT_PATH
LOG_FILE="/home/pi/Scripts/unbound-cache-dumping/dns-cache.log"
ANZAHL_DE_DOMAINS=20000
ANZAHL_REST_DOMAINS=10000

#############################################
# START Funktion 1: Update alle Top-Domains #
#############################################
funktion1() {
echo "$(date +'%d-%m-%Y %H:%M:%S') - Funktion 1 wird gestartet. Hole aktuelle Top 1 Mio URLs und lege davon Top10000 DE und restliche relevante Domains ab." >> "$LOG_FILE"
echo "Funktion 1 wird gestartet..."
# Hole aktuelle Top 1 Mio von Cisco
# https://s3-us-west-1.amazonaws.com/umbrella-static/index.html
echo
echo "Hole aktuelle Top 1 Mio URLs von Cisco"
echo
rm top-1m*
wget http://s3-us-west-1.amazonaws.com/umbrella-static/top-1m.csv.zip
unzip top-1m.csv.zip
rm top-1m.csv.zip
echo
echo "Top 1 Mio als CSV abgelegt."
echo

# Exports der Domains

# Lösche alle vorherigen
rm DEtop*.txt
rm RELEVANTtop*.txt

# Exportiere nur die Domains
echo
echo "Exportiere nur die Domains aus der CSV"
echo
cat top-1m.csv | cut -f 2 -d , > top-1m.txt
dos2unix top-1m.txt

# Exportiere alle DE-Domains und lege die Top X davon ab
echo
echo "Exportiere alle DE-Domains und lege die Top $ANZAHL_DE_DOMAINS davon ab"
grep -E '\.(de)\b' top-1m.txt > DEall.txt
head -n $ANZAHL_DE_DOMAINS DEall.txt > DEtop"$ANZAHL_DE_DOMAINS".txt
ANZAHL_DE_DOMAINS=$(cat DEtop"$ANZAHL_DE_DOMAINS".txt | wc -l)
mv DEtop*.txt DEtop"$ANZAHL_DE_DOMAINS".txt 2>/dev/null
echo "$ANZAHL_DE_DOMAINS DE-Domains abgelegt"

# Exportiere alle DE-Domains und lege die Top 10.000 davon ab
echo
echo "Exportiere alle anderen möglichen relevanten Domains und lege die Top $ANZAHL_REST_DOMAINS davon ab"
grep -E '\.(com|net|org|eu)$' top-1m.txt > RELEVANTall.txt
head -n $ANZAHL_REST_DOMAINS RELEVANTall.txt > RELEVANTtop"$ANZAHL_REST_DOMAINS".txt
ANZAHL_REST_DOMAINS=$(cat RELEVANTtop"$ANZAHL_REST_DOMAINS".txt | wc -l)
mv RELEVANTtop*.txt RELEVANTtop"$ANZAHL_REST_DOMAINS".txt 2>/dev/null
echo "$ANZAHL_REST_DOMAINS RELEVANT-Domains abgelegt"

# Merge alle Domains zu einem File, um Doppeleinträge zu vermeiden
cat DEtop*.txt RELEVANTtop*.txt firefoxHistoryDomains.txt | sort -u > mergedDomainsToCache.txt

echo "$(date +'%d-%m-%Y %H:%M:%S') - Funktion 1 beendet." >> "$LOG_FILE"
}
###################
# ENDE Funktion 1 #
###################


#########################################
# START Funktion 2: Preload sequentiell #
#########################################
funktion2() {
echo "$(date +'%d-%m-%Y %H:%M:%S') - Funktion 2 wird gestartet. Digge auf alle Top DE und restliche relevante Domains im SEQUENTIELLEN Modus. Das sollte ca. 15m je 10tsd URLs in Anspruch nehmen." >> "$LOG_FILE"
    echo "Funktion 2 wird gestartet..."

    # Liste von Dateinamen mit Domain-Listen erstellen
#	patterns=("DEtop*.txt" "RELEVANTtop*.txt")
	patterns=("mergedDomainsToCache.txt")
	domain_files=()
	for pattern in "${patterns[@]}"; do
		for file in $pattern; do
			domain_files+=("$file")
		done
	done

    # Schleife über jede Datei in der Liste
    for domain_file in "${domain_files[@]}"; do
        # Überprüfen, ob die Domain-Liste existiert
        if [ ! -f "$domain_file" ]; then
            echo "Domain list file '$domain_file' not found."
            continue
        fi

        # Schleife über jede Domain in der Datei
        while IFS= read -r domain; do
            # Überprüfen, ob die Domain nicht leer ist
            if [ -n "$domain" ]; then
                # DNS-Abfrage für die Domain durchführen
                result=$(dig +short @127.0.0.1 -p 5335 "$domain")

                # Überprüfen, ob ein Ergebnis vorliegt
                if [ -n "$result" ]; then
                    echo "Resolved $domain: $result"
                else
                    echo "Unable to resolve $domain"
                fi
            fi
        done < "$domain_file"
    done
echo "$(date +'%d-%m-%Y %H:%M:%S') - Funktion 2 beendet." >> "$LOG_FILE"
}
###################
# ENDE Funktion 2 #
###################


######################################
# START Funktion 3: Preload parallel #
######################################
funktion3() {
echo "$(date +'%d-%m-%Y %H:%M:%S') - Funktion 3 wird gestartet. Digge auf alle Top DE und restliche relevante Domains im PARALLELEN Modus. Das sollte bei 5 Threads auf 20tsd URLs bspw. ca. 10m in Anspruch nehmen." >> "$LOG_FILE"
    echo "Funktion 3 wird gestartet..."


    # Liste von Dateinamen mit Domain-Listen erstellen
#	patterns=("DEtop*.txt" "RELEVANTtop*.txt")
	patterns=("mergedDomainsToCache.txt")
	domain_files=()
	for pattern in "${patterns[@]}"; do
		for file in $pattern; do
			domain_files+=("$file")
		done
	done


    # Anzahl der gleichzeitigen DNS-Abfragen
    #parallelism=4  # Anpassen Sie die gewünschte Anzahl gleichzeitiger Abfragen an Ihre Anforderungen an
    local threads=$1
    echo "Es werden $threads Threads parallel ausgeführt."

    # Funktion zur Auflösung einer einzelnen Domain
    resolve_domain() {
        domain="$1"
        result=$(dig +short @127.0.0.1 -p 5335 "$domain")
        if [ -n "$result" ]; then
            echo "Resolved $domain: $result"
        else
            echo "Unable to resolve $domain"
        fi
    }

    # Exportiere die Funktion, damit sie von parallel verwendet werden kann
    export -f resolve_domain

    # Parallelisierte Ausführung der DNS-Abfragen für jede Datei in der Liste
    for domain_file in "${domain_files[@]}"; do
        # Überprüfen, ob die Domain-Liste existiert
        if [ ! -f "$domain_file" ]; then
            echo "Domain list file '$domain_file' not found."
            continue
        fi

        echo "Parallelisierte DNS-Abfragen für Datei: $domain_file"

        # Parallele Ausführung der DNS-Abfragen für die Domains in der Datei
        cat "$domain_file" | parallel -j "$threads" resolve_domain
    done
echo "$(date +'%d-%m-%Y %H:%M:%S') - Funktion 3 beendet." >> "$LOG_FILE"
}
###################
# ENDE Funktion 3 #
###################






##############################################
##############################################
## Hauptfunktion zum Auswählen der Funktion ##
##############################################
##############################################
main_with_arg() {
    local threads=$2

    case $1 in
        1)  funktion1 ;;
        2)  funktion2 ;;
        3)  funktion3 "$threads" ;;
        q)  echo "Das Skript wird beendet" 
            exit ;;
        *)  echo "Ungültige Auswahl. Bitte gebe 1, 2, 3 oder 'q' ein." ;;
    esac
}

##############################################
## Hauptfunktion für interaktive Abfrage    ##
##############################################
main_without_arg() {
    while true; do
        echo "Was möchtest du tun?"
        echo
        echo "1 = Update alle Top-Domains"
        echo "2 = Führe Preload sequentiell durch"
        echo "3 = Führe Preload parallel durch"
        echo "q = Beende das Skript"
        echo
        echo "Deine Wahl:"
        read auswahl

        #main_with_arg "$auswahl" "$threads"

        case $auswahl in
            1|2|q)  main_with_arg "$auswahl" ;;
            3)      echo "Wie viele parallele Anfragen sollen laufen?"
                    echo "(10-20 könnte ein guter Versuch sein)"
                    read threads
                    main_with_arg "$auswahl" "$threads" ;;
            *)      echo "Ungültige Auswahl. Bitte gebe 1, 2, 3 oder 'q' ein." ;;
        esac


        echo
        echo
        echo
        echo "Funktion abgeschlossen."
        echo "Möchtest du eine weitere Funktion ausführen? (j/n)"
        read weiter
        if [[ $weiter != "j" ]]; then
            echo "Das Skript wird beendet."
            exit
        fi
    done
}





#################
# Hauptprogramm #
#################
# Überprüfen, ob Argumente übergeben wurden
if [ $# -gt 0 ]; then
	# Wenn Argumente übergeben wurden, rufe die Hauptfunktion mit den übergebenen Argumenten auf
	echo "$(date +'%d-%m-%Y %H:%M:%S') - Starte preload_topdomains.sh mit Übergabeparametern. Überspringe interaktive Abfrage." >> "$LOG_FILE"
	main_with_arg "$@"
else
	# Wenn keine Argumente übergeben wurden, führe die interaktive Abfrage durch
	echo "$(date +'%d-%m-%Y %H:%M:%S') - Starte preload_topdomains.sh ohne Übergabeparameter. Beginne interaktive Abfrage." >> "$LOG_FILE"
	main_without_arg
fi

