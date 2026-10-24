#!/usr/bin/env python3
"""OSM .pbf (BBBike/Geofabrik bölge çıkarımı) -> Android için küçük adres veritabanı (SQLite).

Kullanım:  python3 -I adres_db_uret.py girdi.pbf ../app/src/main/assets/bandirma_adres.db

- Sadece OSM'de GERÇEKTEN yazılı olan addr:* kayıtları alınır; eksik numara/koordinat uydurulmaz.
- Eski-yeni adres eşleştirmesi yoktur ve eklenmez.
- Anahtar (key) üretimi uygulamadaki NavHelper.fold()/key() ile birebir aynı olmalı.
Veri: © OpenStreetMap katkıda bulunanlar, ODbL 1.0 — https://www.openstreetmap.org/copyright
"""
import sys, os, re, sqlite3, datetime, struct
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pbf_oku as P

DB_VERSION = 1
TYPES = {"caddesi": "cadde", "cadde": "cadde", "sokak": "sokak", "sokagi": "sokak", "bulvari": "bulvar",
         "bulvar": "bulvar", "yolu": "yol", "cikmazi": "cikmaz", "cikmaz": "cikmaz", "meydani": "meydan"}
MAP = str.maketrans({'ç': 'c', 'ğ': 'g', 'ı': 'i', 'ö': 'o', 'ş': 's', 'ü': 'u', 'â': 'a', 'î': 'i', 'û': 'u'})

def fold(s):
    return s.replace('İ', 'i').replace('I', 'ı').lower().translate(MAP)

def key(s):
    return re.sub(r'[^a-z0-9]', '', fold(s))

def mahalle_key(s):
    toks = s.split()
    if toks and fold(toks[-1]) in ('mahallesi', 'mahalle'):
        toks = toks[:-1]
    return key(' '.join(toks))

def street_split(s):
    """'Mareşal Fevzi Çakmak Caddesi' -> (anahtar 'maresalfevzicakmak', tür 'cadde', ad)"""
    toks = s.replace('.', ' ').split()
    typ = ''
    if len(toks) > 1 and fold(toks[-1]) in TYPES:
        typ = TYPES[fold(toks[-1])]
        toks = toks[:-1]
    name = ' '.join(toks)
    return key(name), typ, name

def no_split(hn):
    """'45/7' -> tam 'k45/7', taban '45' ve harf ''; '47B' -> taban '47', harf 'b'."""
    f = fold(hn).replace(' ', '')
    m = re.match(r'^(\d+)(?:/?([a-z])(?![a-z0-9]))?', f)
    return f, (m.group(1) if m else ''), ((m.group(2) or '') if m else '')

def main(src, dst):
    nodes = {}; ways = []; addrn = []
    stamp = None
    for typ, raw in P.blobs(src):
        if typ == 'OSMHeader':
            for fn, v in P.fields(raw):
                if fn == 32: stamp = datetime.datetime.fromtimestamp(v, datetime.timezone.utc).strftime('%Y-%m-%d')
    for e in P.read(src):
        if e[0] == 'node':
            nodes[e[1]] = (e[2], e[3])
            if 'addr:housenumber' in e[4] and 'addr:street' in e[4]: addrn.append(e)
        elif e[0] == 'way':
            if 'addr:housenumber' in e[3] and 'addr:street' in e[3]: ways.append(e)
    rows = []; skipped = {'ilce': 0, 'mahalle_yok': 0, 'geometri_yok': 0}
    def add(kind, oid, lat, lon, t):
        if t.get('addr:district', 'Bandırma') != 'Bandırma': skipped['ilce'] += 1; return
        mh = t.get('addr:neighbourhood', '')
        if not mh: skipped['mahalle_yok'] += 1
        yk, typ, yname = street_split(t['addr:street'])
        full, base, harf = no_split(t['addr:housenumber'])
        if not yk or not base: return
        rows.append((kind, oid, mh, mahalle_key(mh) if mh else '', yname, yk, typ, t['addr:housenumber'], full, base, harf,
                     round(lat, 7), round(lon, 7), t.get('name', ''), key(t.get('name', '')), t.get('addr:unit', ''),
                     t.get('addr:postcode', '')))
    for e in addrn: add('n', e[1], e[2], e[3], e[4])
    for w in ways:
        pts = [nodes[r] for r in w[2] if r in nodes]
        if not pts: skipped['geometri_yok'] += 1; continue
        add('w', w[1], sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts), w[3])
    if os.path.exists(dst): os.remove(dst)
    db = sqlite3.connect(dst)
    db.executescript('''
    CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);
    CREATE TABLE adres(
      id INTEGER PRIMARY KEY, osm_tur TEXT, osm_id INTEGER,
      mahalle TEXT, mahalle_k TEXT, yol TEXT, yol_k TEXT, yol_tur TEXT,
      no TEXT, no_k TEXT, no_taban TEXT, no_harf TEXT,
      lat REAL, lon REAL, ad TEXT, ad_k TEXT, birim TEXT, posta TEXT);
    ''')
    db.executemany('INSERT INTO adres(osm_tur,osm_id,mahalle,mahalle_k,yol,yol_k,yol_tur,no,no_k,no_taban,no_harf,lat,lon,ad,ad_k,birim,posta) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)', rows)
    db.executescript('CREATE INDEX i_yol_no ON adres(yol_k, no_taban); CREATE INDEX i_ad ON adres(ad_k) WHERE ad_k<>"";')
    meta = {'surum': str(DB_VERSION), 'kaynak': 'OpenStreetMap', 'kaynak_tarihi': stamp or 'bilinmiyor',
            'uretim': datetime.date.today().isoformat(), 'kayit': str(len(rows)),
            'lisans': 'ODbL 1.0 - © OpenStreetMap katkıda bulunanlar - https://www.openstreetmap.org/copyright'}
    db.executemany('INSERT INTO meta VALUES (?,?)', meta.items())
    db.commit(); db.execute('VACUUM'); db.close()
    print('kayıt', len(rows), 'atlanan', skipped, 'veri tarihi', stamp, 'boyut', os.path.getsize(dst))

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
