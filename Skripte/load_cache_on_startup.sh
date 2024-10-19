#!/bin/bash
#
# Dieses Skript dient dazu, wenn unbound gestartet wird den cache-dump hinein zu laden.
# Dazu waren 3 Schritte nötig
#  1.	Dieses Skript hier, welches letztendlich den Cache in Unbound lädt
#  2.	Eine Kopie von /lib/systemd/system/unbound.service nach /etc/systemd/system/unbound.service angelegt und um ExecStartPost=/pfad/nach/hier.sh ergänzt,
#       um dieses Skript bei jedem Start von Unbound im Anschluss auszuführen
#  3.	Einen Cronjob über crontab -e angelegt, der alle 3 Stunden das dump-file ablegt.

# Pfad zur Cache-Dump-Datei
LOG_FILE="/home/pi/Scripts/unbound-cache-dumping/dns-cache.log"
CACHE_DUMP_FILE="/home/pi/Scripts/unbound-cache-dumping/dns-cache.dump"
CACHE_ENTRIES=0

echo "$(date +'%d-%m-%Y %H:%M:%S') - Starte load_cache_on_startup.sh" >> "$LOG_FILE"


# Prüfe, ob die Cache-Dump-Datei vorhanden ist
if [ -f "$CACHE_DUMP_FILE" ]; then
    # Lade den DNS-Cache aus der Cache-Dump-Datei
	CACHE_ENTRIES=$(cat "$CACHE_DUMP_FILE" | wc -l)
	echo "$(date +'%d-%m-%Y %H:%M:%S') - Cache-Dump-Datei mit $CACHE_ENTRIES Einträgen gefunden." >> "$LOG_FILE"

	sudo unbound-control load_cache < "$CACHE_DUMP_FILE"

	CACHE_ENTRIES=$(sudo unbound-control dump_cache | wc -l)
	echo "$(date +'%d-%m-%Y %H:%M:%S') - DNS-Cache-Preloading abgeschlossen. Unbound besitzt nun $CACHE_ENTRIES Einträge im Cache." >> "$LOG_FILE"

else
	echo "$(date +'%d-%m-%Y %H:%M:%S') - Cache-Dump-Datei nicht gefunden: $CACHE_DUMP_FILE" >> "$LOG_FILE"
	echo "Cache-Dump-Datei nicht gefunden: $CACHE_DUMP_FILE"
fi
