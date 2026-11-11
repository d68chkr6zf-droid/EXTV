#!/usr/bin/env python3
"""Top 10 Netflix France de la semaine -> top10.json (lu par l'appli EXTV).

Source : fichier public hebdomadaire de Netflix (all-weeks-countries.tsv). Netflix ne donne que des
titres anglais : chaque titre est cherché sur Wikidata pour obtenir ses titres français.
Aucune dépendance (bibliothèque standard uniquement).
"""
import csv, io, json, re, sys, time, unicodedata, urllib.error, urllib.parse, urllib.request

COUNTRY = "FR"
SOURCES = [
    "https://www.netflix.com/tudum/top10/data/all-weeks-countries.tsv",
    "https://top10.netflix.com/data/all-weeks-countries.tsv",
]
UA_BROWSER = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
UA_BOT = "EXTV-top10/1.0 (https://github.com/d68chkr6zf-droid/EXTV)"
FILM_WORDS = re.compile(r"film|movie|documentary|animated|special", re.I)
TV_WORDS = re.compile(r"series|show|sitcom|anime|docuseries|telenovela|miniseries", re.I)
# Un film : la description donne une année ou un auteur (« 2021 film by … »), sinon c'est un genre ou une notion.
FILM_OK = re.compile(r"\b(19|20)\d{2}\b|\bby\b|directed", re.I)
YEAR = re.compile(r"\b(19|20)\d{2}\b")
# Descriptions à écarter : genres, notions, pages d'homonymie (ex. « Heist » -> « heist film »).
NOT_A_WORK = re.compile(r"genre|type of|disambiguation|wikimedia|concept|term\b|category|topic|list of|style of", re.I)


def get(url, ua, timeout=120):
    last = None
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": ua, "Accept-Encoding": "identity"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except urllib.error.HTTPError:
            raise
        except Exception as e:  # noqa: BLE001  (coupure en cours de téléchargement : on réessaie)
            last = e
            time.sleep(3 * (attempt + 1))
    raise last


GLOBAL = [
    "https://www.netflix.com/tudum/top10/data/all-weeks-global.tsv",
    "https://top10.netflix.com/data/all-weeks-global.tsv",
]
FR_WEEKS = 4      # semaines du classement France utilisées (la plus récente d'abord)
GLOBAL_WEEKS = 2  # puis semaines du classement mondial
PER_KIND = 60     # titres candidats par type (l'appli garde les 10 premiers présents dans l'abonnement)


def tsv(urls):
    last = None
    for url in urls:
        try:
            data = get(url, UA_BROWSER).decode("utf-8", "replace")
        except Exception as e:  # noqa: BLE001
            last = e
            print(f"échec {url} : {e}", file=sys.stderr)
            continue
        print(f"{url} : {len(data) // 1024} Ko")
        return list(csv.DictReader(io.StringIO(data), delimiter="\t"))
    print(f"indisponible : {last}", file=sys.stderr)
    return []


def kind_of(category):
    return "FILMS" if category.lower().startswith("film") else "TV"


def netflix_rows():
    """Candidats par ordre de priorité : Top 10 France des dernières semaines, puis Top 10 mondial."""
    fr = [r for r in tsv(SOURCES) if r.get("country_iso2") == COUNTRY]
    glob = tsv(GLOBAL)
    if not fr and not glob:
        raise SystemExit("Top 10 indisponible")
    ordered = []
    fr_weeks = sorted({r["week"] for r in fr}, reverse=True)[:FR_WEEKS]
    for w in fr_weeks:
        ordered += sorted((r for r in fr if r["week"] == w), key=lambda r: int(r["weekly_rank"]))
    for w in sorted({r["week"] for r in glob}, reverse=True)[:GLOBAL_WEEKS]:
        # Anglais et non anglais entrelacés par rang.
        ordered += sorted((r for r in glob if r["week"] == w), key=lambda r: (int(r["weekly_rank"]), r["category"]))
    out, seen = [], set()
    count = {"FILMS": 0, "TV": 0}
    for r in ordered:
        kind = kind_of(r["category"])
        title = r["show_title"].strip()
        if (kind, title.lower()) in seen or count[kind] >= PER_KIND:
            continue
        seen.add((kind, title.lower()))
        count[kind] += 1
        out.append({"kind": kind, "rank": count[kind], "title": title})
    out.sort(key=lambda e: (e["kind"], e["rank"]))
    return (fr_weeks[0] if fr_weeks else ""), out


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


def is_work(desc, kind):
    if not desc or NOT_A_WORK.search(desc):
        return False
    if kind == "FILMS":
        return bool(FILM_WORDS.search(desc) and FILM_OK.search(desc))
    return bool(TV_WORDS.search(desc))


def wikipedia_item(title, kind):
    """Recherche plein texte sur Wikipédia anglais (trouve aussi les titres de redirection)."""
    hint = "film" if kind == "FILMS" else "TV series"
    url = "https://en.wikipedia.org/w/api.php?" + urllib.parse.urlencode({
        "action": "query", "generator": "search", "gsrsearch": f"{title} {hint}", "gsrlimit": 3,
        "prop": "pageprops|description", "ppprop": "wikibase_item", "format": "json"})
    try:
        pages = json.loads(get(url, UA_BOT, 30)).get("query", {}).get("pages", {})
    except Exception as e:  # noqa: BLE001
        print(f"wikipedia : {e}", file=sys.stderr)
        return None
    words = FILM_WORDS if kind == "FILMS" else TV_WORDS
    for p in sorted(pages.values(), key=lambda p: p.get("index", 99)):
        desc = p.get("description", "")
        qid = p.get("pageprops", {}).get("wikibase_item")
        if simple(re.sub(r"\s*\(.*\)$", "", p.get("title", ""))) != simple(title):
            continue  # résultat d'une recherche plein texte sur un autre titre
        if qid and is_work(desc, kind):
            y = YEAR.search(desc)
            return qid, int(y.group(0)) if y else None
    return None


def simple(s):
    s = unicodedata.normalize("NFD", s.lower())
    return re.sub(r"[^a-z0-9]+", " ", "".join(c for c in s if not unicodedata.combining(c))).strip()


def wikipedia_exact(title, kind):
    """Article Wikipédia anglais au titre exact (« X (2026 TV series) », « X (film) »…), le plus fiable."""
    year = time.gmtime().tm_year
    if kind == "FILMS":
        tries = [f"{title} ({y} film)" for y in (year, year - 1, year - 2)] + [f"{title} (film)", title]
    else:
        tries = [f"{title} ({y} TV series)" for y in (year, year - 1, year - 2)] + \
                [f"{title} (TV series)", f"{title} (miniseries)", f"{title} (docuseries)", title]
    url = "https://en.wikipedia.org/w/api.php?" + urllib.parse.urlencode({
        "action": "query", "titles": "|".join(tries), "redirects": 1, "prop": "pageprops|description",
        "ppprop": "wikibase_item", "format": "json"})
    try:
        q = json.loads(get(url, UA_BOT, 30)).get("query", {})
    except Exception as e:  # noqa: BLE001
        print(f"wikipedia : {e}", file=sys.stderr)
        return None
    redirect = {r["from"]: r["to"] for r in q.get("normalized", []) + q.get("redirects", [])}
    pages = {p.get("title"): p for p in q.get("pages", {}).values() if "missing" not in p}
    for name in tries:
        target = redirect.get(name, name)
        target = redirect.get(target, target)
        p = pages.get(target)
        if not p:
            continue
        desc = p.get("description", "")
        qid = p.get("pageprops", {}).get("wikibase_item")
        if simple(re.sub(r"\s*\(.*\)$", "", target)) != simple(title):
            continue  # redirigé vers une autre œuvre (« Not a Stranger » -> « Stranger »)
        # Titre avec précision « (2026 TV series) » : c'est forcément l'œuvre ; titre nu : on vérifie la description.
        ok = not NOT_A_WORK.search(desc) if name != title else is_work(desc, kind)
        if qid and ok:
            y = YEAR.search(desc) or YEAR.search(target)
            return qid, int(y.group(0)) if y else None
    return None


def translate(entries):
    found = {}
    for e in entries:
        if e["title"] in found:
            continue
        item = wikipedia_exact(e["title"], e["kind"])
        if item:
            found[e["title"]] = item
            continue
        hit = None
        for lang in ("en", "fr"):
            res = wikidata({"action": "wbsearchentities", "search": e["title"], "language": lang, "type": "item", "limit": 8}).get("search", [])
            # Le libellé trouvé doit être exactement le titre (évite « A Couple of Lies » -> « The Perfect Couple »).
            hit = next((r for r in res if is_work(r.get("description", ""), e["kind"])
                        and simple(r.get("match", {}).get("text", "")) == simple(e["title"])), None)
            if hit:
                break
        if hit:
            y = YEAR.search(hit.get("description", ""))
            found[e["title"]] = (hit["id"], int(y.group(0)) if y else None)
        else:
            item = wikipedia_item(e["title"], e["kind"])
            if item:
                found[e["title"]] = item
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
