#!/usr/bin/env python3
"""Bağımlılıksız minimal OSM .pbf okuyucu. Kullanım: pbf_oku.py dosya.pbf -> dict üreteçleri."""
import struct, zlib, sys

def varint(b, i):
    r = 0; s = 0
    while True:
        c = b[i]; i += 1
        r |= (c & 0x7F) << s
        if c < 0x80: return r, i
        s += 7

def fields(b):
    i = 0; n = len(b)
    while i < n:
        k, i = varint(b, i)
        f, t = k >> 3, k & 7
        if t == 0: v, i = varint(b, i)
        elif t == 2:
            l, i = varint(b, i); v = b[i:i+l]; i += l
        elif t == 1: v = b[i:i+8]; i += 8
        elif t == 5: v = b[i:i+4]; i += 4
        else: raise ValueError("wire type %d" % t)
        yield f, v

def packed(b, signed=False):
    out = []; i = 0
    while i < len(b):
        v, i = varint(b, i)
        if signed: v = (v >> 1) ^ -(v & 1)
        out.append(v)
    return out

def zz(v): return (v >> 1) ^ -(v & 1)

def blobs(path):
    with open(path, 'rb') as f:
        while True:
            h = f.read(4)
            if not h: return
            hl = struct.unpack('>I', h)[0]
            hdr = f.read(hl); typ = None; dsz = 0
            for fn, v in fields(hdr):
                if fn == 1: typ = v.decode()
                elif fn == 3: dsz = v
            data = f.read(dsz)
            raw = None
            for fn, v in fields(data):
                if fn == 1: raw = v
                elif fn == 3: raw = zlib.decompress(v)
            yield typ, raw

def read(path):
    """Üretir: ('node',id,lat,lon,tags) ('way',id,refs,tags) ('rel',id,members,tags)"""
    for typ, raw in blobs(path):
        if typ != 'OSMData': continue
        strs = []; groups = []; gran = 100; lo = 0; la = 0
        for fn, v in fields(raw):
            if fn == 1:
                strs = [s.decode('utf-8', 'replace') for fn2, s in fields(v) if fn2 == 1]
            elif fn == 2: groups.append(v)
            elif fn == 17: gran = v
            elif fn == 19: la = zz(v)
            elif fn == 20: lo = zz(v)
        def cv(x, off): return (off + gran * x) / 1e9
        for g in groups:
            for fn, v in fields(g):
                if fn == 1:  # node
                    nid = lat = lon = 0; ks = []; vs = []
                    for f2, x in fields(v):
                        if f2 == 1: nid = zz(x)
                        elif f2 == 2: ks = packed(x)
                        elif f2 == 3: vs = packed(x)
                        elif f2 == 8: lat = zz(x)
                        elif f2 == 9: lon = zz(x)
                    yield ('node', nid, cv(lat, la), cv(lon, lo), {strs[a]: strs[b] for a, b in zip(ks, vs)})
                elif fn == 2:  # dense
                    ids = kv = None; dl = dn = None
                    for f2, x in fields(v):
                        if f2 == 1: ids = packed(x, True)
                        elif f2 == 8: dl = packed(x, True)
                        elif f2 == 9: dn = packed(x, True)
                        elif f2 == 10: kv = packed(x)
                    i = 0; nid = 0; lat = 0; lon = 0
                    for j in range(len(ids)):
                        nid += ids[j]; lat += dl[j]; lon += dn[j]
                        tg = {}
                        while kv and i < len(kv) and kv[i] != 0:
                            tg[strs[kv[i]]] = strs[kv[i+1]]; i += 2
                        i += 1 if kv else 0
                        yield ('node', nid, cv(lat, la), cv(lon, lo), tg)
                elif fn == 3:  # way
                    wid = 0; ks = []; vs = []; refs = []
                    for f2, x in fields(v):
                        if f2 == 1: wid = x
                        elif f2 == 2: ks = packed(x)
                        elif f2 == 3: vs = packed(x)
                        elif f2 == 8:
                            r = 0; refs = []
                            for d in packed(x, True): r += d; refs.append(r)
                    yield ('way', wid, refs, {strs[a]: strs[b] for a, b in zip(ks, vs)})
                elif fn == 4:  # relation
                    rid = 0; ks = []; vs = []; roles = []; mem = []; types = []
                    for f2, x in fields(v):
                        if f2 == 1: rid = x
                        elif f2 == 2: ks = packed(x)
                        elif f2 == 3: vs = packed(x)
                        elif f2 == 8: roles = packed(x)
                        elif f2 == 9:
                            r = 0; mem = []
                            for d in packed(x, True): r += d; mem.append(r)
                        elif f2 == 10: types = packed(x)
                    yield ('rel', rid, [(("node", "way", "rel")[t], m, strs[ro]) for t, m, ro in zip(types, mem, roles)],
                           {strs[a]: strs[b] for a, b in zip(ks, vs)})

if __name__ == '__main__':
    import collections
    c = collections.Counter()
    for e in read(sys.argv[1]): c[e[0]] += 1
    print(dict(c))
