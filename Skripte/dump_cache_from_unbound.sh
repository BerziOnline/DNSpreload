#!/bin/bash
# Dieses Skript legt den Cache von Unbound ab

# VARIABLEN
LOG_FILE="/home/pi/Scripts/unbound-cache-dumping/dns-cache.log"
CACHE_DUMP_FILE="/home/pi/Scripts/unbound-cache-dumping/dns-cache.dump"
CACHE_ENTRIES=0

echo "$(date +'%d-%m-%Y %H:%M:%S') - Starte dump_cache_from_unbound.sh" >> "$LOG_FILE"



# Prüfe, ob vorherige Cache-Dump-Datei vorhanden ist
if [ -f "$CACHE_DUMP_FILE" ]; then
    # Gebe Einträge der vorherigen Cache-Dump-Datei aus
        CACHE_ENTRIES=$(cat "$CACHE_DUMP_FILE" | wc -l)
        echo "$(date +'%d-%m-%Y %H:%M:%S') - Vorherige Cache-Dump-Datei mit $CACHE_ENTRIES Einträgen gefunden." >> "$LOG_FILE"
else
        echo "$(date +'%d-%m-%Y %H:%M:%S') - Keine vorherige Cache-Dump-Datei gefunden: $CACHE_DUMP_FILE" >> "$LOG_FILE"
        echo "Keine vorherige Cache-Dump-Datei gefunden: $CACHE_DUMP_FILE"
fi


# Lege neuen Dump ab
sudo unbound-control dump_cache > "$CACHE_DUMP_FILE"

CACHE_ENTRIES=$(cat "$CACHE_DUMP_FILE" | wc -l)
echo "$(date +'%d-%m-%Y %H:%M:%S') - Cache-Dump-Datei fertig geschrieben. Der neue Dump besitzt nun $CACHE_ENTRIES Einträge." >> "$LOG_FILE"
