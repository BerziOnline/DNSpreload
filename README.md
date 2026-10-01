<p align="center"><img src="brand/dnspreload-logo.svg" width="120" alt="DNSpreload logo"></p>

# DNSpreload

*Being smarter, being faster, being prepared. Preload your unbound!*

DNSpreload keeps the cache of your own recursive **unbound** resolver warm, so the first visit to a website is answered from cache in about a millisecond instead of after a full walk through the DNS hierarchy.

## Why preload?

If you run your own DNS server – for an ad blocker like Pi-hole or for privacy – you have two choices:

| | Forward to Google / Cloudflare / Quad9 | Resolve yourself (recursive unbound) |
|---|---|---|
| Speed | fast: their caches already hold almost everything | slow on the first lookup: root → TLD → domain, often 50–300 ms |
| Privacy | one company sees every name you look up | no middleman; each server only sees the part it is responsible for |

DNSpreload gives you both: it resolves the names you are likely to need **before** you need them. Your devices then get cached answers locally, and the upstream servers see a steady stream of tens of thousands of lookups every day – which ones you actually visit is lost in the noise.

You cannot cache the whole internet. But you can cache the part you use 95 % of the time.

## What's new in v2

v1 was a set of Bash scripts that fired the top domains at unbound once a night. v2 is a rewrite with the same idea, done properly:

- **Learns from your own network.** Every name your devices resolved in the last 90 days (from Pi-hole's database) is preloaded – plus the most popular domains in Germany and worldwide. Names your blocklists refuse are left out.
- **Flowing refresh instead of a nightly burst.** The list is split into 22 fixed slices; one slice is refreshed every hour at 5 queries per second. Every name is renewed every 22 hours, safely inside unbound's 24-hour `serve-expired` window – and your router never sees a spike. (A single burst of 70,000 lookups at 100 qps once overloaded a consumer router's NAT table.)
- **Smarter top lists.** Tranco (Umbrella as fallback), optional Cloudflare Radar confirmation, and a **TLD filter**: keep only the TLDs that matter to you (or drop the ones that don't) – filtered first, then cut to your target count.
- **Survives restarts.** The cache is dumped every 6 hours and on stop, and loaded on start; right after boot your network's own names are refreshed first.
- **Web panel.** Cache size, hit rate, response times, last runs, blocking statistics – mobile-friendly, English or German.
- **Switches.** Turn the ad blocker off for 30 min or 2 h – for the whole network or for a group of devices – with automatic switch-back.
- **Blocking monitor.** Deterministically finds domains that are probably blocked by mistake (broken apps retry, ad beacons don't) or trackers that slipped through. Suggestions only; you decide.
- **Android app.** Status, switches with countdown, home-screen widget, quick-settings tile, statistics and the blocking suggestions – with role-based tokens (admin / family).
- **Everything is a setting.** One commented `config.env`, no code changes needed.

## How it works

```
             top lists (Tranco/Umbrella/Radar)   Pi-hole query history
                              \                     /
   03:50 daily  dnspreload update  →  lists/merged-final.txt  (~60,000 names)
                                             |
   hourly :20   dnspreload preload slice     |  1/22 of the list, 5 q/s, via dnsperf
                                             v
                   unbound 127.0.0.1:5335  (directly – not through Pi-hole, so your query log stays clean)
                                             ^
   your devices → Pi-hole → unbound          |  answered from cache
```

The preload asks unbound for each name. If the entry is still valid, unbound answers from cache and nothing leaves your network. If it expired, unbound fetches it fresh – exactly what would otherwise happen the next time someone opens the site.

## Requirements

- Debian/Ubuntu (or similar with systemd), Python ≥ 3.9 (standard library only)
- **Pi-hole v6** and **unbound** on the same machine, unbound listening on `127.0.0.1:5335` with `remote-control` enabled
- `dnsperf`, `sqlite3`, `curl`

## Install

```
git clone https://github.com/BerziOnline/DNSpreload.git && cd DNSpreload
sudo ./install.sh --check      # prerequisites only
sudo ./install.sh              # install or update; keeps your config.env, lists and state
```

Recommended unbound settings are in [`unbound/unbound-dnspreload.conf.example`](unbound/unbound-dnspreload.conf.example); the two lines that matter most are `serve-expired: yes` and `serve-expired-ttl: 86400`.

Then set `PANEL_BIND` (and `ADMIN_NETS`) in `/opt/dnspreload-v2/config.env` and open `http://<dns-server>:8053/`.

## Settings

All in `/opt/dnspreload-v2/config.env` – changes apply on the next run.

| Setting | Default | Meaning |
|---|---|---|
| `LEARN_DAYS` | 90 | learn every name your network resolved in this window |
| `TOP_DE` / `TOP_INTL` | 0 (all) / 20000 | how many `.de` / international names from the top list |
| `INTL_TLD_WHITELIST` / `INTL_TLDS` | true / com,net,org,… | keep (or with `false`: drop) these TLDs; applied **before** `TOP_INTL` |
| `SOURCES` | tranco,umbrella | top-list sources, first reachable wins |
| `RADAR_TOKEN` | – | optional Cloudflare Radar token (read-only) |
| `SLICE_HOURS` / `SLICE_QPS` | 22 / 5 | hourly slice: number of slices and queries per second |
| `QPS` | 25 | speed of manual full runs and network runs |
| `UI_LANG` | en | panel and monitor language: `en` or `de` |
| `PANEL_BIND` / `PANEL_PORT` | 127.0.0.1 / 8053 | where the panel listens |
| `ADMIN_NETS` | 127.0.0. | IP prefixes with admin view without token |
| `SYSTEM_CLIENTS` | 127.0.0.1 | IPs left out of statistics (preload, servers, router) |

Your own additions: `lists/static-manual.txt`. Never preload: `lists/exclude.txt`.

## Schedule

| When | Timer | What |
|---|---|---|
| daily 03:50 | `dnspreload-update` | rebuild all lists (~1 min) |
| hourly :20 | `dnspreload-slice` | refresh one slice (~3,300 names, ~11 min) |
| daily 16:00 | `dnspreload-network` | refresh the names your network used in the last 24 h |
| after boot | `dnspreload-after-boot` | refresh your network's names first |
| every 6 h | `unbound-cache-dump` | save the cache for a warm restart |
| every 6 h | `dnspreload-blockmon` | blocking monitor |
| – | `dnspreload-full` | everything at once, manual only: `systemctl start dnspreload-full` |

`systemctl list-timers 'dnspreload*'` shows the last and next runs.

## Commands

```
dnspreload status             cache size, hit rate, list sizes, last run, how much of each list is cached
dnspreload update             rebuild the lists now
dnspreload preload slice      refresh the current slice now   (also: full | network)
dnspreload-token add admin "my phone"      create a token for the app (shown once)
dnspreload-switch list                     all switches and their state
dnspreload-blockmon test                   self-test of the blocking monitor
```

## Web panel, API and app

- **Panel** `http://<dns-server>:8053/` – status for admins; `/u/<token>` a simple page with just the switches for family members; `/blocking` the suggestions.
- **Grafana** `GET /metrics` (InfluxDB line protocol) – e.g. Telegraf `inputs.http` → InfluxDB.
- **API** `/api/v1/status`, `/stats`, `/daystats`, `/switches`, `/blocking`, `/tokens` – token via `Authorization: Bearer …`.
- **Switches** are defined in `/etc/dnspreload/switches.json` (see `etc/switches.example.json`): either the whole network (`"mode": "blocking"`) or a Pi-hole group with its devices.
- **Android app** in [`app/`](app/): `cd app && ./gradlew assembleRelease` (Android SDK, JDK 21). Enter the server address and a token on first start. A ready-made APK can be attached to each release.

## Upgrading from v1

v1 (the Bash scripts) is kept as release v1.0 (see [Releases](../../releases)). v2 does not use any v1 files: remove the old cron jobs, then run `install.sh`.
