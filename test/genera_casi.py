# -*- coding: utf-8 -*-
"""Genera i casi di prova per il lettore di manifest.

Per ogni caso scrive due file in test/casi/:
  <nome>.pickle   il manifest come lo produce Python
  <nome>.atteso   la stessa cosa in forma canonica, che il Java deve ricostruire

La forma canonica e' una riga per valore, ordinata:
    file|chiave|tipo|valore

Si lancia con:  python3 test/genera_casi.py
"""
import os
import pickle
import shutil

QUI = os.path.dirname(os.path.abspath(__file__))
CASI = os.path.join(QUI, "casi")


def canonica(manifest):
    righe = []
    for nomefile in sorted(manifest.keys()):
        dati = manifest[nomefile]
        for chiave in sorted(dati.keys()):
            v = dati[chiave]
            if v is None:
                tipo, valore = "none", ""
            elif isinstance(v, bool):
                tipo, valore = "bool", "true" if v else "false"
            elif isinstance(v, int):
                tipo, valore = "int", str(v)
            elif isinstance(v, float):
                tipo, valore = "float", repr(v)
            else:
                tipo, valore = "str", str(v)
            righe.append("%s|%s|%s|%s" % (nomefile, chiave, tipo, valore))
    return "\n".join(righe) + "\n"


def scrivi(nome, manifest, protocollo):
    with open(os.path.join(CASI, nome + ".pickle"), "wb") as f:
        pickle.dump(manifest, f, protocol=protocollo)
    with open(os.path.join(CASI, nome + ".atteso"), "w") as f:
        f.write(canonica(manifest))
    print("  %-28s protocollo %d, %d file" % (nome, protocollo, len(manifest)))


# Il manifest come lo costruisce build_service_file() di Sublima.
def manifest_sublima(formato_dati=None):
    dati = {
        "rt": False,
        "tipo": "scontrino",
        "ip": "192.0.2.58",
        "ip_port": "9100",
        "profilo": "s0000",
        "denominazione": "ESCPOS BAR",
        "path_out": "",
        "path_log": "",
        "porta": "9100",
        "joint": False,
        "id_pos": 1,
        "seriale_baud": None,
        "seriale_porta": None,
        "tipo_mf": "ESCPOS",
        "zero_sync_chiusura_e_numero": False,
        "zero_escpos_check_online": False,
        "zero_escpos_check_paper": False,
        "id_magazzino": 1,
        "estensione": "",
        "id": 2,
        "formato": "80mm",
        "matricola": "",
        "tipo_connessione": "ethernet",
        "codice_univoco": "vRSEhnm25L",
        "zero_cache_articoli_fast": "off",
        "zero_cache_articoli_barcode": "off",
        "zero_cache_vita": 3600,
        "zero_display_diretto": "on",
        "id_ts": 18085,
    }
    if formato_dati:
        dati["formato_dati"] = formato_dati
    return dati


if os.path.exists(CASI):
    shutil.rmtree(CASI)
os.makedirs(CASI)

print("Genero i casi di prova:")

# Una sola stampante, come la maggior parte delle stampe.
scrivi("una_stampante", {"scontrino.escpos": manifest_sublima()}, 5)

# Con la dichiarazione dei byte gia' tradotti.
scrivi("byte_nativi", {"comanda.escpos": manifest_sublima("bytes")}, 5)

# Piu' stampanti nello stesso lavoro: e' il caso delle comande GUSTO.
scrivi("tre_stampanti", {
    "bar.escpos": manifest_sublima(),
    "cucina.escpos": manifest_sublima("bytes"),
    "pizzeria.escpos": manifest_sublima(),
}, 5)

# Gli stessi dati con i protocolli piu' vecchi: uno Zero puo' girare su Python
# datati, e il manifest arriva come lo ha scritto quel Python.
scrivi("protocollo_2", {"scontrino.escpos": manifest_sublima()}, 2)
scrivi("protocollo_3", {"scontrino.escpos": manifest_sublima()}, 3)
scrivi("protocollo_4", {"scontrino.escpos": manifest_sublima()}, 4)

# Casi limite dei valori.
scrivi("valori_limite", {"strano.escpos": {
    "vuoto": "",
    "accenti": "PERCHE' e' cosi'? Citta', pero'... - ",
    "numero_grande": 2147483647,
    "numero_piccolo": 0,
    "negativo": -1,
    "vero": True,
    "falso": False,
    "niente": None,
}}, 5)

print()
print("Fatto: %d casi in %s" % (len(os.listdir(CASI)) // 2, CASI))
