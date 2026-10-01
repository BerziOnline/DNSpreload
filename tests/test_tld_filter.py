"""International top-list TLD filter tests. Run in v2: python3 -m pytest -q test_tld_filter.py."""
import importlib.machinery, importlib.util, os
from pathlib import Path

HERE = next(p for p in (Path(__file__).resolve().parent, Path(__file__).resolve().parent.parent) if (p / "dnspreload").exists())   # repo root or tests/


def load(tmp_path, cfg):
    (tmp_path / "config.env").write_text(cfg)
    os.environ["DNSPRELOAD_HOME"] = str(tmp_path)
    loader = importlib.machinery.SourceFileLoader("dp_tld_test", str(HERE / "dnspreload"))
    spec = importlib.util.spec_from_loader("dp_tld_test", loader)
    m = importlib.util.module_from_spec(spec); loader.exec_module(m)
    return m


# Ranked list: alternating .ru (excluded) and .com, plus .de and co.uk
RANKED = []
for i in range(50):
    RANKED += [f"ru{i}.ru", f"com{i}.com"]
RANKED += ["spiegel.de", "bbc.co.uk", "extra.xyz"]


def run(m):
    m.src_tranco = lambda: list(RANKED)
    m.src_radar = lambda: ([], set())
    m.build_general()
    intl = [l for l in (m.LISTS / "general-intl.txt").read_text().splitlines() if l and not l.startswith("#")]
    de = [l for l in (m.LISTS / "general-de.txt").read_text().splitlines() if l and not l.startswith("#")]
    return intl, de


def test_whitelist_filtert_zuerst_und_fuellt_zielzahl_voll(tmp_path):
    m = load(tmp_path, "SOURCES=tranco\nTOP_INTL=30\nINTL_TLD_WHITELIST=true\nINTL_TLDS=com,uk\n")
    intl, de = run(m)
    assert len(intl) == 30                                   # target reached even though half the names were .ru
    assert all(d.endswith((".com", ".uk")) for d in intl)
    assert not any(d.endswith(".ru") for d in intl)
    assert de == ["spiegel.de"]                              # filter leaves the .de list unchanged


def test_whitelist_zu_wenige_treffer_nimmt_alle(tmp_path):
    m = load(tmp_path, "SOURCES=tranco\nTOP_INTL=1000\nINTL_TLD_WHITELIST=true\nINTL_TLDS=com,uk\n")
    intl, _ = run(m)
    assert len(intl) == 51 and "bbc.co.uk" in intl           # 50 .com + co.uk (final label counts)


def test_blacklist_nimmt_nur_die_genannten_raus(tmp_path):
    m = load(tmp_path, "SOURCES=tranco\nTOP_INTL=1000\nINTL_TLD_WHITELIST=false\nINTL_TLDS=ru\n")
    intl, _ = run(m)
    assert not any(d.endswith(".ru") for d in intl)
    assert "extra.xyz" in intl and len(intl) == 52


def test_leere_liste_filtert_nichts(tmp_path):
    m = load(tmp_path, "SOURCES=tranco\nTOP_INTL=1000\nINTL_TLDS=\n")
    intl, _ = run(m)
    assert len(intl) == 102


def test_reihenfolge_nach_rang_bleibt(tmp_path):
    m = load(tmp_path, "SOURCES=tranco\nTOP_INTL=3\nINTL_TLDS=com\n")
    m.write_list = lambda name, doms, header: (m.LISTS / name).write_text("\n".join(doms)) or len(doms)
    intl, _ = run(m)
    assert intl == ["com0.com", "com1.com", "com2.com"]      # highest-ranked names, not random choices


def test_mitgelieferte_config_hat_whitelist_aktiv():
    txt = (HERE / "config.env").read_text()
    assert "INTL_TLD_WHITELIST=true" in txt
    line = next(l for l in txt.splitlines() if l.startswith("INTL_TLDS="))
    tlds = line.split("#")[0].split("=", 1)[1].split(",")
    for t in ("com", "net", "org", "uk", "at", "ch", "eu", "io"):
        assert t in tlds
    assert "de" not in tlds and "ru" not in tlds and "cn" not in tlds
