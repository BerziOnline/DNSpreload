"""Offline panel localization tests with synthetic, reserved-domain data."""
import ast
import copy
import hashlib
import importlib.machinery
import importlib.util
import io
import json
import re
import string
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

HERE = next(p for p in (Path(__file__).resolve().parent, Path(__file__).resolve().parent.parent) if (p / "dnspreload").exists())   # repo root or tests/
PANEL = HERE / "panel"
NOW = 1_790_800_000
GERMAN = re.compile(r"[äöüÄÖÜß]|\b(und|nicht|oder|Geräte|keine|wird|werden|bei|für|nur|alle)\b")


def forbid(*args, **kwargs):
    raise AssertionError("External process/network access is forbidden in panel tests")


def load_panel(home, monkeypatch, lang="en", source=PANEL):
    monkeypatch.setenv("DNSPRELOAD_HOME", str(home))
    if lang is None:
        monkeypatch.delenv("UI_LANG", raising=False)
    else:
        monkeypatch.setenv("UI_LANG", lang)
    loader = importlib.machinery.SourceFileLoader("panel_i18n_" + str(lang), str(source))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    module.time = SimpleNamespace(**{name: getattr(time, name) for name in
                                    ("strftime", "strptime", "mktime", "localtime")}, time=lambda: NOW)
    module.subprocess = SimpleNamespace(run=forbid)
    module.sh = forbid
    module.ThreadingHTTPServer = forbid
    module.sqlite3 = SimpleNamespace(connect=forbid)
    module.TOKENS = home / "tokens.json"
    return module


def synthetic_state(home):
    state = home / "state"
    state.mkdir(exist_ok=True)
    (home / "lists").mkdir(exist_ok=True)
    last = {"mode": "slice", "time": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(NOW - 600)),
            "slot": 2, "slices": 22, "domains": 10, "duration_s": 120,
            "completed": "10 (100%)", "cache_before": 20, "cache_after": 30}
    d = {"time": NOW, "unbound_up": True, "dig_ok": True, "dig_ms": 2, "ftl_active": True,
         "cache": 30000, "rrset": 60000, "hit_rate": 99, "queries": 100, "hits": 99,
         "prefetch": 10, "expired": 2, "servfail": 0, "recursion_avg_ms": 100,
         "uptime_h": 48, "last_run": last, "history": [[last["time"], "slice", "10", "", "120", "", "", "", ""]],
         "lists": {key: {"n": 10, "mtime": NOW - 600} for key in
                   ("general-de", "general-intl", "network-learned", "network-recent",
                    "network-frequent", "network-blocked-excluded", "static-manual", "exclude", "merged-final")},
         "paused": 0, "report": "report-example.md", "timers": [], "failed": [],
         "pihole": {"queries": 100, "blocked": 20, "clients": 1, "top_blocked": [["ads.example.com", 20]],
                    "gravity": 200000, "gravity_updated": NOW - 600, "cached": 79, "answered": 80,
                    "p50_ms": 0.3, "slow": 1}}
    sw = [{"name": "ads", "label": "Ad blocker", "scope": "Test devices", "kind": "full",
           "roles": ["admin", "family"], "on": True, "until": NOW + 600, "presets": [5, 30, 60, 120]}]
    candidates = []
    reviews = {}
    for kind, domain, recommendation in (("over", "app.example.com", "allow"),
                                         ("under", "track.example.com", "deny")):
        candidates.append({"kind": kind, "domain": domain, "clients": 1, "queries": 4,
                           "lists": 2, "score": 5, "why": ["Repeated queries from phone-1 (192.0.2.10)"]})
        reviews[domain] = {"kind": kind, "verdict": "keep", "purpose": "Example service", "category": "service",
                           "if_blocked": "The service stops working", "recommendation": recommendation,
                           "reason": "Synthetic observation", "confidence": 0.9}
    files = {"snapshot.json": d, "last-run.json": last, "switches-list.json": sw,
             "blockmon.json": {"time": NOW, "over": [candidates[0]], "under": [candidates[1]]},
             "blockmon-review.json": {"reviews": reviews},
             "blockmon-decisions.json": {"app.example.com": {"ts": NOW - 50, "decision": "allow"}},
             "daystats.json": {"time": NOW, "per_client": {"192.0.2.10": {"name": "phone-1", "queries": 4}}},
             "token-seen.json": {"1": NOW}}
    for name, value in files.items():
        (state / name).write_text(json.dumps(value))
    tokens = {"1": {"role": "admin", "label": "Test admin", "hash": hashlib.sha256(b"admin-example-token").hexdigest()},
              "2": {"role": "family", "label": "Test user", "hash": hashlib.sha256(b"family-example-token").hexdigest()}}
    (home / "tokens.json").write_text(json.dumps(tokens))
    return d, sw


def request(module, method, path, role="admin", body="", label="Test account"):
    """Execute a handler without a socket; retain real serialization/error pages."""
    h = object.__new__(module.H)
    h.path = path
    h.command = method
    h.request_version = "HTTP/1.1"
    h.close_connection = False
    h.client_address = ("192.0.2.10", 12345)
    h.headers = {"Content-Length": str(len(body.encode()))}
    h.rfile = io.BytesIO(body.encode())
    h.wfile = io.BytesIO()
    h.auth = lambda: (role, label) if role else None
    result = {"code": None, "headers": []}
    h.send_response = lambda code, *args: result.update(code=code)
    h.send_header = lambda key, value: result["headers"].append((key, value))
    h.end_headers = lambda: None
    getattr(h, "do_" + method)()
    result["body"] = h.wfile.getvalue()
    return result


@pytest.mark.parametrize("lang", ["en", "de"])
def test_main_pages_render(tmp_path, monkeypatch, lang):
    d, sw = synthetic_state(tmp_path)
    m = load_panel(tmp_path, monkeypatch, lang)
    m.switches = lambda: copy.deepcopy(sw)
    m.snapshot = lambda: copy.deepcopy(d)
    pages = [m.page(d), m.blocking_page(), m.family_page(sw, "Test user"), m.family_page([], "Test user")]
    for page in pages:
        assert page.startswith("<!doctype html>")
        assert "lang=" + lang in page
        if lang == "en":
            assert not GERMAN.search(page)
    for role in ("admin", "family"):
        for path in ("/", "/u", "/blocking", "/api/v1/status", "/api/v1/stats", "/api/v1/switches",
                     "/api/v1/tokens", "/api/v1/daystats", "/api/v1/blocking", "/api/v1/blocking/raw", "/json", "/missing"):
            result = request(m, "GET", path, role)
            assert result["code"] in (200, 403, 404)
            if lang == "en":
                assert not GERMAN.search(result["body"].decode())


@pytest.mark.parametrize("lang", ["en", "de"])
def test_page_branches_and_formatters(tmp_path, monkeypatch, lang):
    d, sw = synthetic_state(tmp_path)
    m = load_panel(tmp_path, monkeypatch, lang)
    m.switches = lambda: []
    states = [d]
    for update in ({"pihole": None, "last_run": {}, "history": [], "lists": {}, "timers": [], "failed": None},
                   {"unbound_up": False, "dig_ok": False, "ftl_active": False, "failed": ["unbound.service"], "cache": 0},
                   {"pihole": {**d["pihole"], "answered": 0, "gravity": 5, "gravity_updated": None}},
                   {"pihole": {**d["pihole"], "cached": 0, "p50_ms": 30, "gravity_updated": NOW - 10 * 86400}},
                   {"last_run": {**d["last_run"], "time": "invalid", "completed": "invalid", "mode": "full"}}):
        states.append({**copy.deepcopy(d), **update})
    outputs = [m.page(x) for x in states]
    outputs += [m.n(None), m.pct(None), m.dur("invalid"), m.MODE_LABEL({"mode": "slice"}),
                m.MODE_LABEL({"mode": "full"}), m.MODE_LABEL({"mode": "network"})]
    outputs += [m.ago(ts) for ts in (None, NOW, NOW - 600, NOW - 8000, NOW - 200000)]
    outputs += [m.until(NOW + delta) for delta in (-1, 600, 8000, 200000)]
    if lang == "en":
        assert not any(GERMAN.search(text) for text in outputs)


@pytest.mark.parametrize("lang", ["en", "de"])
def test_error_responses(tmp_path, monkeypatch, lang):
    d, sw = synthetic_state(tmp_path)
    m = load_panel(tmp_path, monkeypatch, lang)
    m.switches = lambda: sw
    m.snapshot = lambda: d
    m.token_add = lambda *args: None
    cases = [("GET", "/", None, ""), ("GET", "/api/v1/status", None, ""),
             ("GET", "/u/invalidexampletoken", "admin", ""),
             ("POST", "/api/v1/tokens", None, ""), ("POST", "/api/v1/tokens", "family", ""),
             ("POST", "/api/v1/tokens", "admin", "{}"),
             ("POST", "/api/v1/tokens/1/revoke", "admin", ""),
             ("POST", "/api/v1/blocking/review", "admin", "{}"),
             ("POST", "/blocking", "admin", "domain=invalid&d=allow"),
             ("POST", "/api/v1/switch/missing", "admin", "{}"),
             ("POST", "/api/v1/switch/ads", "admin", "{")]
    for method, path, role, body in cases:
        result = request(m, method, path, role, body)
        assert result["code"] in (400, 401, 403)
        if lang == "en":
            assert not GERMAN.search(result["body"].decode())


def test_catalog_is_complete_and_has_no_dead_entries():
    source = PANEL.read_text()
    tree = ast.parse(source)
    catalog_node = next(n.value for n in tree.body if isinstance(n, ast.Assign)
                        and any(isinstance(t, ast.Name) and t.id == "DE" for t in n.targets))
    catalog = ast.literal_eval(catalog_node)
    keys = [ast.literal_eval(k) for k in catalog_node.keys]
    assert len(keys) == len(set(keys)), "Duplicate catalog keys"
    calls = [n for n in ast.walk(tree) if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "_"]
    assert all(len(n.args) == 1 and isinstance(n.args[0], ast.Constant) and isinstance(n.args[0].value, str) for n in calls)
    used = {n.args[0].value for n in calls}
    assert used == set(catalog), {"missing": used - set(catalog), "dead": set(catalog) - used}
    formatter = string.Formatter()
    for en, de in catalog.items():
        assert not GERMAN.search(en)
        assert {f for _, f, _, _ in formatter.parse(en) if f is not None} == {
            f for _, f, _, _ in formatter.parse(de) if f is not None}
    for lineno, line in enumerate(source.splitlines(), 1):
        if not catalog_node.lineno <= lineno <= catalog_node.end_lineno:
            assert not GERMAN.search(line), (lineno, line)


@pytest.mark.parametrize("setting,config,expected", [(None, "", "en"), (None, "UI_LANG=de\n", "de"),
                                                    ("en", "UI_LANG=de\n", "en"), ("de", "", "de"),
                                                    ("unsupported", "", "en")])
def test_language_setting_precedence(tmp_path, monkeypatch, setting, config, expected):
    (tmp_path / "config.env").write_text(config)
    m = load_panel(tmp_path, monkeypatch, setting)
    assert m.UI_LANG == expected
    assert m._("An unknown message") == "An unknown message"


@pytest.mark.parametrize("lang", ["en", "de"])
def test_family_page_renders_with_blocker_active(tmp_path, monkeypatch, lang):
    """Normal state (switch off = blocker active) has no footer hint; the page must still render (was HTTP 500)."""
    d, sw = synthetic_state(tmp_path)
    sw[0]["on"] = False
    m = load_panel(tmp_path, monkeypatch, lang)
    m.switches = lambda: sw
    page = m.family_page(sw, "Test user")
    assert "<button name=a value=on30>" in page
    assert request(m, "GET", "/u", "family")["code"] == 200


def test_blocking_without_reviewer_shows_raw_candidates(tmp_path, monkeypatch):
    """Public installs have no nightly reviewer: candidates must still be listed, not stay 'pending' forever."""
    synthetic_state(tmp_path)
    (tmp_path / "state" / "blockmon-review.json").unlink()
    m = load_panel(tmp_path, monkeypatch, "en")
    d = m.reviewed_blocking()
    assert len(d["over"]) == 1 and len(d["under"]) == 1 and d["pending"] == 0
    assert d["over"][0]["review"] is None and d["reviewed_at"] is None


def test_blocking_with_reviewer_keeps_gating(tmp_path, monkeypatch):
    synthetic_state(tmp_path)
    m = load_panel(tmp_path, monkeypatch, "en")
    d = m.reviewed_blocking()
    assert all(x["review"] is not None for x in d["over"] + d["under"])
