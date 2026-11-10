#!/usr/bin/env python3
"""Top 10 Netflix France de la semaine -> top10.json (lu par l'appli EXTV).

Source : fichier public hebdomadaire de Netflix (all-weeks-countries.tsv). Netflix ne donne que des
titres anglais : chaque titre est cherché sur Wikidata pour obtenir ses titres français.
Aucune dépendance (bibliothèque standard uniquement).
"""
import csv, io, json, re, sys, time, urllib.parse, urllib.request

COUNTRY = "FR"
SOURCES = [
    "https://www.netflix.com/tudum/top10/data/all-weeks-countries.tsv",
    "https://top10.netflix.com/data/all-weeks-countries.tsv",
]
UA_BROWSER = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
UA_BOT = "EXTV-top10/1.0 (https://github.com/d68chkr6zf-droid/EXTV)"
FILM_WORDS = re.compile(r"film|movie|documentary|animated|special", re.I)
TV_WORDS = re.compile(r"series|show|sitcom|anime|television|docuseries|reality|drama|telenovela|programme|miniseries", re.I)
YEAR = re.compile(r"\b(19|20)\d{2}\b")


def get(url, ua, timeout=120):
    req = urllib.request.Request(url, headers={"User-Agent": ua, "Accept-Encoding": "identity"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def netflix_rows():
    last = None
    for url in SOURCES:
        try:
            data = get(url, UA_BROWSER).decode("utf-8", "replace")
        except Exception as e:  # noqa: BLE001
            last = e
            print(f"échec {url} : {e}", file=sys.stderr)
            continue
        print(f"{url} : {len(data) // 1024} Ko")
        rows = [r for r in csv.DictReader(io.StringIO(data), delimiter="\t") if r.get("country_iso2") == COUNTRY]
        if not rows:
            print("aucune ligne France", file=sys.stderr)
            continue
        week = max(r["week"] for r in rows)
        rows = [r for r in rows if r["week"] == week]
        out = []
        for r in rows:
            kind = "FILMS" if r["category"].lower().startswith("film") else "TV"
            out.append({"kind": kind, "rank": int(r["weekly_rank"]), "title": r["show_title"].strip()})
        out.sort(key=lambda e: (e["kind"], e["rank"]))
        return week, out
    raise SystemExit(f"Top 10 indisponible : {last}")


def wikidata(params):
    params = dict(params, format="json")
    url = "https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode(params)
    for attempt in range(3):
        try:
            return json.loads(get(url, UA_BOT, 30))
        except Exception as e:  # noqa: BLE001
            print(f"wikidata : {e}", file=sys.stderr)
            time.sleep(2 * (attempt + 1))
    return {}


def translate(entries):
    found = {}
    for e in entries:
        words = FILM_WORDS if e["kind"] == "FILMS" else TV_WORDS
        res = wikidata({"action": "wbsearchentities", "search": e["title"], "language": "en", "type": "item", "limit": 8}).get("search", [])
        hit = next((r for r in res if words.search(r.get("description", ""))), None)
        if hit:
            y = YEAR.search(hit.get("description", ""))
            found[e["title"]] = (hit["id"], int(y.group(0)) if y else None)
    ids = sorted({v[0] for v in found.values()})
    names = {}
    for i in range(0, len(ids), 40):
        chunk = ids[i:i + 40]
        ents = wikidata({"action": "wbgetentities", "ids": "|".join(chunk), "props": "labels|aliases|sitelinks",
                         "languages": "fr", "sitefilter": "frwiki"}).get("entities", {})
        for qid in chunk:
            ent = ents.get(qid, {})
            alt = []
            lab = ent.get("labels", {}).get("fr", {}).get("value")
            if lab:
                alt.append(lab)
            wiki = ent.get("sitelinks", {}).get("frwiki", {}).get("title")
            if wiki:
                alt.append(re.sub(r"\s*\(.*\)$", "", wiki))
            alt += [a["value"] for a in ent.get("aliases", {}).get("fr", [])[:6]]
            names[qid] = list(dict.fromkeys(a for a in alt if a.strip()))
    for e in entries:
        qid, year = found.get(e["title"], (None, None))
        e["alt"] = names.get(qid, []) if qid else []
        e["year"] = year or 0
    return entries


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "top10.json"
    week, entries = netflix_rows()
    entries = translate(entries)
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"week": week, "country": COUNTRY, "generated": int(time.time()), "entries": entries}, f, ensure_ascii=False, indent=1)
    print(f"Semaine du {week} :")
    for e in entries:
        print(f"  {e['kind']:5} #{e['rank']:2} {e['title']}  ->  {' | '.join(e['alt']) or '(pas de titre français)'}")


if __name__ == "__main__":
    main()
