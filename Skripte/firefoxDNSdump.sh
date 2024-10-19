#!/bin/bash

PLACES_FILE="/home/<USER>/.mozilla/firefox/<XXX>.default/places.sqlite"

echo "Hole Database aus Firefox-History..."
sqlite3 $PLACES_FILE "SELECT * FROM moz_places;" > database.txt
echo "Check!"

echo

echo "Lege enthaltene URLs als saubere Domains ab..."
grep -oE 'https?://[^[:space:]]+' database.txt | awk -F "|" '{print $1}' > urls.txt
awk -F "|" '{gsub(/^https?:\/\//, "", $1); print $1}' urls.txt | awk -F "/" '{print $1}' | sort -u | grep -E '[[:alpha:]]' > firefoxHistoryDomains.txt
echo "Check!"

echo

# Wichtig: Der Dateiname muss "firefoxHistoryDomains.txt" bleiben, da er so in den anderen Skripten aufgerufen wird!
echo "Kopiere den Dump zum Server, sodass dieser beim nächsten Load verwertet wird..."
scp firefoxHistoryDomains.txt <USER>@<IP>:/path/to/other/scripts/firefoxHistoryDomains.txt
echo "Check!"

read -p " 
ENTER drücken zum Schließen"
