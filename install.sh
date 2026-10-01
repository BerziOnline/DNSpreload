#!/usr/bin/env bash
# DNSpreload v2 installer - run as root on the machine that runs Pi-hole (v6) and unbound.
#   sudo ./install.sh            install or update (keeps your config.env, lists and state)
#   sudo ./install.sh --check    only check prerequisites
# Idempotent: every changed system file gets a .bak-<timestamp> copy next to it.
set -euo pipefail
cd "$(dirname "$0")"
DEST=/opt/dnspreload-v2
TS=$(date +%Y%m%dT%H%M%S)
say() { printf '\033[1m%s\033[0m\n' "$*"; }
die() { echo "install.sh: $*" >&2; exit 1; }
put() { # put SRC DST MODE [OWNER:GROUP] - install with backup of a differing existing file
  local src=$1 dst=$2 mode=$3 own=${4:-root:root}
  if [ -e "$dst" ] && ! cmp -s "$src" "$dst"; then cp -a "$dst" "$dst.bak-$TS"; echo "  backup $dst.bak-$TS"; fi
  install -D -m "$mode" -o "${own%%:*}" -g "${own##*:}" "$src" "$dst"
}

[ "$(id -u)" = 0 ] || die "run as root"
say "Checking prerequisites"
miss=()
for b in python3 dnsperf unbound-control sqlite3 curl pihole-FTL systemctl; do command -v "$b" >/dev/null || miss+=("$b"); done
[ ${#miss[@]} -eq 0 ] || die "missing: ${miss[*]}  (Debian/Ubuntu: apt install python3 dnsperf unbound sqlite3 curl; Pi-hole v6 from pi-hole.net)"
python3 -c 'import sys; sys.exit(sys.version_info < (3, 9))' || die "python3 >= 3.9 required"
unbound-control status >/dev/null 2>&1 || die "unbound-control cannot reach unbound - enable remote-control (see unbound/unbound-dnspreload.conf.example)"
dig +time=2 +tries=1 @127.0.0.1 -p 5335 example.com >/dev/null 2>&1 || echo "  note: nothing answers on 127.0.0.1#5335 - set UNBOUND/UNBOUND_PORT in config.env to where unbound listens"
[ -r /etc/pihole/pihole-FTL.db ] || die "/etc/pihole/pihole-FTL.db not found (Pi-hole v6 required)"
[ -r /etc/pihole/cli_pw ] || echo "  note: /etc/pihole/cli_pw missing - switches need Pi-hole's CLI API password (enabled by default in v6)"
echo "  ok"
[ "${1:-}" = --check ] && exit 0

say "User and directories"
id dnspreload >/dev/null 2>&1 || useradd --system --no-create-home --home-dir "$DEST" --shell /usr/sbin/nologin dnspreload
getent group pihole >/dev/null && usermod -aG pihole dnspreload     # read access to pihole-FTL.db / gravity.db
install -d -m 750 -o root -g dnspreload "$DEST"
install -d -m 755 -o dnspreload -g dnspreload "$DEST/lists"
install -d -m 775 -o dnspreload -g dnspreload "$DEST/state" "$DEST/logs"
install -d -m 755 /etc/dnspreload /var/lib/unbound/cache
chown unbound:unbound /var/lib/unbound/cache 2>/dev/null || true

say "Program files"
for f in dnspreload panel; do put "$f" "$DEST/$f" 750 root:dnspreload; done
for f in dnspreload-blockmon dnspreload-daystats dnspreload-switch dnspreload-token; do put "$f" "/usr/local/bin/$f" 755; done
for f in unbound-cache-dump unbound-cache-load; do put "unbound/$f" "/usr/local/sbin/$f" 755; done
install -d -m 755 -o root -g root "$DEST/brand"; cp -a brand/. "$DEST/brand/"
if [ -e "$DEST/config.env" ]; then
  echo "  keeping your $DEST/config.env (new defaults: config.env in this folder)"
  for k in $(grep -oE '^[A-Z_]+=' config.env | tr -d =); do grep -q "^$k=" "$DEST/config.env" || echo "  new setting you may want to add: $k"; done
else
  put config.env "$DEST/config.env" 640 root:dnspreload
fi
for f in exclude.txt static-manual.txt; do [ -e "$DEST/lists/$f" ] || put "lists/$f" "$DEST/lists/$f" 644 dnspreload:dnspreload; done
[ -e /etc/dnspreload/switches.json ] || put etc/switches.example.json /etc/dnspreload/switches.json 644
[ -e /etc/dnspreload/tokens.json ] || { echo '{}' > /etc/dnspreload/tokens.json; chown root:dnspreload /etc/dnspreload/tokens.json; chmod 640 /etc/dnspreload/tokens.json; }

say "sudo rules (panel -> unbound-control, switches, blocking decisions)"
for f in sudoers-dnspreload sudoers-dnspreload-panel; do
  visudo -cq -f "etc/$f" || die "etc/$f failed visudo check"
  put "etc/$f" "/etc/sudoers.d/${f//./_}" 440
done
put etc/tmpfiles-dnspreload-switch.conf /etc/tmpfiles.d/dnspreload-switch.conf 644
systemd-tmpfiles --create /etc/tmpfiles.d/dnspreload-switch.conf

say "systemd units"
for f in systemd/*.service systemd/*.timer; do put "$f" "/etc/systemd/system/$(basename "$f")" 644; done
for f in systemd/dnspreload-panel.service.d/*.conf; do put "$f" "/etc/systemd/system/dnspreload-panel.service.d/$(basename "$f")" 644; done
put unbound/10-cache.conf /etc/systemd/system/unbound.service.d/10-cache.conf 644
systemctl daemon-reload
systemctl enable --now dnspreload-update.timer dnspreload-slice.timer dnspreload-network.timer \
  dnspreload-metrics.timer dnspreload-daystats.timer dnspreload-blockmon.timer unbound-cache-dump.timer >/dev/null
systemctl enable dnspreload-after-boot.service dnspreload-switch-reset.service >/dev/null
systemctl enable dnspreload-panel.service >/dev/null; systemctl restart dnspreload-panel.service

say "First list build (takes ~1 min: downloads the top list, reads Pi-hole's history)"
systemctl start dnspreload-update.service && sudo -u dnspreload "$DEST/dnspreload" status | head -20 || echo "  update failed - see: journalctl -u dnspreload-update"

cat <<EOF

Done. Next steps:
  - Panel: set PANEL_BIND (and ADMIN_NETS) in $DEST/config.env, then systemctl restart dnspreload-panel
           optional firewall: systemd/dnspreload-panel.nft
  - Tokens for the app / family view:   dnspreload-token add admin "my phone"
  - Switches (ad blocker on/off):       edit /etc/dnspreload/switches.json
  - Preload runs hourly at :20 (one slice). Full run now: systemctl start dnspreload-full
EOF
