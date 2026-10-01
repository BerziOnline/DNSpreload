"""Slice preload tests. Run in v2: python3 -m pytest -q test_slice.py."""
import importlib.machinery, importlib.util, os, subprocess, sys, time
from pathlib import Path

import pytest

HERE = next(p for p in (Path(__file__).resolve().parent, Path(__file__).resolve().parent.parent) if (p / "dnspreload").exists())   # repo root or tests/


def load(tmp_path, cfg=""):
    (tmp_path / "config.env").write_text(cfg)
    os.environ["DNSPRELOAD_HOME"] = str(tmp_path)
    loader = importlib.machinery.SourceFileLoader("dp_slice_test", str(HERE / "dnspreload"))
    spec = importlib.util.spec_from_loader("dp_slice_test", loader)
    m = importlib.util.module_from_spec(spec); loader.exec_module(m)
    return m


def write_merged(m, names, aaaa=()):
    lines = [f"{d} A\n" for d in names] + [f"{d} AAAA\n" for d in aaaa]
    (m.LISTS / "merged-final.dnsperf").write_text("".join(lines))


NAMES = [f"name{i}.example.de" for i in range(5000)]


def test_jeder_name_genau_ein_fach_und_alle_abgedeckt(tmp_path):
    m = load(tmp_path)
    write_merged(m, NAMES, aaaa=NAMES[:500])
    seen = {}
    for h in range(22):
        f, slot, slices = m.slice_file(now=h * 3600)
        assert slices == 22
        for l in open(f):
            d, t = l.split()
            seen.setdefault(d, set()).add(slot)
    assert set(seen) == set(NAMES)                            # no missing domains
    assert all(len(s) == 1 for s in seen.values())            # A and AAAA share one slice, never duplicated


def test_faecher_gleichmaessig(tmp_path):
    m = load(tmp_path)
    write_merged(m, NAMES)
    sizes = [sum(1 for _ in open(m.slice_file(now=h * 3600)[0])) for h in range(22)]
    avg = len(NAMES) / 22
    assert min(sizes) > avg * 0.8 and max(sizes) < avg * 1.2, sizes   # no overloaded slices


def test_fach_stabil_ueber_neustarts(tmp_path):
    a = load(tmp_path)
    write_merged(a, NAMES)
    fa = a.slice_file(now=7 * 3600)[0].read_text()
    b = load(tmp_path)   # fresh process state, same list
    assert b.slice_file(now=7 * 3600)[0].read_text() == fa
    # checksum, not hash(): independent of PYTHONHASHSEED
    out = subprocess.run([sys.executable, "-c", "import zlib;print(zlib.crc32(b'name1.example.de')%22)"],
                         env={**os.environ, "PYTHONHASHSEED": "123"}, capture_output=True, text=True).stdout.strip()
    assert int(out) == a.slice_of("name1.example.de", 22)


def test_wiederkehr_alle_22_stunden_unter_24h(tmp_path):
    m = load(tmp_path)
    write_merged(m, NAMES)
    d = NAMES[42]; hits = []
    for h in range(0, 24 * 4):
        f = m.slice_file(now=1_790_000_000 + h * 3600)[0]
        if any(l.split()[0] == d for l in open(f)): hits.append(h)
    gaps = {b - a for a, b in zip(hits, hits[1:])}
    assert gaps == {22}, gaps          # exactly 22 h, always below the 24 h serve-expired-ttl


def test_konfig_und_qps(tmp_path, monkeypatch):
    m = load(tmp_path, "SLICE_HOURS=20\nSLICE_QPS=4\nQPS=25\n")
    write_merged(m, NAMES[:200])
    calls = []

    def fake_run(cmd, **kw):
        calls.append(cmd)
        if cmd[0] == "dnsperf":
            n = sum(1 for _ in open(cmd[cmd.index("-d") + 1]))
            return subprocess.CompletedProcess(cmd, 0, f"  Queries sent:         {n}\n  Queries completed:    {n} (100.00%)\n  Queries lost:         0 (0.00%)\n  Response codes:       NOERROR {n} (100.00%)\n", "")
        return subprocess.CompletedProcess(cmd, 0, "msg.cache.count=10\n", "")
    monkeypatch.setattr(m.subprocess, "run", fake_run)
    res = m.do_preload("slice")
    dn = next(c for c in calls if c[0] == "dnsperf")
    assert dn[dn.index("-Q") + 1] == "4"                  # SLICE_QPS, not QPS
    assert res["qps"] == 4 and 0 < res["domains"] < 200
    row = (m.STATE / "history.csv").read_text().splitlines()[-1].split(",")
    assert row[1] == "slice" and row[3] == "4"
    # full still uses QPS
    m.do_preload("full"); dn = [c for c in calls if c[0] == "dnsperf"][-1]
    assert dn[dn.index("-Q") + 1] == "25"


def test_leeres_fach_ruft_dnsperf_nicht(tmp_path, monkeypatch):
    m = load(tmp_path)
    write_merged(m, [])
    monkeypatch.setattr(m.subprocess, "run", lambda cmd, **kw: (_ for _ in ()).throw(AssertionError(cmd)) if cmd[0] == "dnsperf"
                        else subprocess.CompletedProcess(cmd, 0, "", ""))
    assert m.do_preload("slice")["domains"] == 0
