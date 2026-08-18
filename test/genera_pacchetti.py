# -*- coding: utf-8 -*-
"""Genera i lavori di stampa completi per provare /stampa_all.

Costruisce gli stessi pacchetti che manda Sublima: uno ZIP con i file di stampa
e il service_file.pickle, il tutto in base64 dentro il campo `data`.

Ogni caso produce test/casi/<nome>.data, che contiene esattamente la stringa che
Sublima mette nel campo `data` della richiesta.

Si lancia con:  python3 test/genera_pacchetti.py
"""
import io
import json
import os
import base64
import pickle
import zipfile

QUI = os.path.dirname(os.path.abspath(__file__))
CASI = os.path.join(QUI, "casi")

if not os.path.isdir(CASI):
    os.makedirs(CASI)

# Un tracciato ESC/POS gia' tradotto, come lo produce ora Sublima: comincia con
# ESC t (scelta della tabella caratteri) e finisce col taglio.
BYTE_COMANDA = (
    b"\x1bt\x00"
    b"\x1d!\x01\x1bE\x01\x1ba\x01"
    b"CUCINA - 1\n"
    b"\x1d!\x00\x1bE\x00\x1ba\x00"
    b"1 X CARBONARA\n"
    b"1 X AMATRICIANA\n"
    b"\n\n"
    b"\x1dV\x00"
)

TRACCIATO_TAG = (
    "[LF=1]\n"
    "[SET][CENTER][BOLD][2H]\n"
    "CUCINA - 1\n"
    "[SET][LEFT][NORMAL][1H]\n"
    "1 X CARBONARA\n"
    "[CUT]\n"
).encode("utf-8")


def stampante(ip="127.0.0.1", formato_dati="bytes", tipo_mf="ESCPOS",
              connessione="ethernet", denominazione="ESCPOS CUCINA", id_bc=3):
    dati = {
        "rt": False,
        "tipo": "scontrino",
        "ip": ip,
        "ip_port": "9100",
        "profilo": "s0000",
        "denominazione": denominazione,
        "path_out": "",
        "path_log": "",
        "porta": "9100",
        "joint": False,
        "id_pos": 1,
        "seriale_baud": None,
        "seriale_porta": None,
        "tipo_mf": tipo_mf,
        "zero_sync_chiusura_e_numero": False,
        "zero_escpos_check_online": False,
        "zero_escpos_check_paper": False,
        "id_magazzino": 1,
        "estensione": "",
        "id": id_bc,
        "formato": "80mm",
        "matricola": "",
        "tipo_connessione": connessione,
        "codice_univoco": "vRSEhnm25L",
        "id_ts": None,
    }
    if formato_dati:
        dati["formato_dati"] = formato_dati
    return dati


def scrivi(nome, file_e_dati, manifest, nome_lavoro=None, con_manifest=True):
    """file_e_dati: {nomefile: contenuto}. manifest: {nomefile: dati stampante}."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for nomefile, contenuto in file_e_dati.items():
            zf.writestr(nomefile, contenuto)
        if con_manifest:
            zf.writestr("service_file_2026-08-18 09_00_00_000000.pickle",
                        pickle.dumps(manifest))

    data = {
        "Result": "JOINT",
        "filescontrino": nome_lavoro or ("tpl/media/s0000/" + nome + ".zip"),
        "corpo_scontrino": base64.b64encode(buf.getvalue()).decode("ascii"),
        "joint": True,
        "tipo": "scontrino",
    }
    percorso = os.path.join(CASI, nome + ".data")
    with open(percorso, "w") as f:
        f.write(json.dumps(data))
    print("  %-26s %d file, %d byte di zip" % (
        nome, len(file_e_dati), len(buf.getvalue())))


print("Genero i lavori di stampa:")

# Il caso normale: una comanda gia' tradotta in byte.
scrivi("lavoro_byte",
       {"comanda.escpos": BYTE_COMANDA},
       {"comanda.escpos": stampante()})

# Due stampanti nello stesso lavoro, come una comanda GUSTO divisa fra reparti.
scrivi("lavoro_due_stampanti",
       {"bar.escpos": BYTE_COMANDA, "cucina.escpos": BYTE_COMANDA},
       {"bar.escpos": stampante(denominazione="ESCPOS BAR", id_bc=2),
        "cucina.escpos": stampante(denominazione="ESCPOS CUCINA", id_bc=3)})

# Tracciato a tag: questo agente non lo interpreta e deve dirlo.
scrivi("lavoro_tag",
       {"comanda.escpos": TRACCIATO_TAG},
       {"comanda.escpos": stampante(formato_dati=None)})

# Registratore Micrelec: l'agente lo instrada sul suo trasporto, che parla e
# non si limita a scrivere. Il dialogo vero e' verificato in TestMicrelec.
scrivi("lavoro_micrelec",
       {"scontrino.dat": b"W/2\n3/S/CAFFE//1.000/1.20/1/////33\n"},
       {"scontrino.dat": stampante(tipo_mf="M", formato_dati=None,
                                   denominazione="CASSA")})

# Riga di display: il messaggio esterno NON porta il campo `tipo`, che sta solo
# nel manifest. Chi lo cercasse nel messaggio finirebbe per trattare il display
# come uno scontrino e interrogare il registratore sui dati fiscali.
riga_display = stampante(tipo_mf="M", formato_dati=None, denominazione="CASSA")
riga_display["tipo"] = "display"
riga_display["id_ts"] = None
scrivi("lavoro_display",
       {"display.dat": b"=/1/CAFFE            1,20\n"},
       {"display.dat": riga_display},
       nome_lavoro="tpl/media/s0000/display.zip")

# Driver che l'agente davvero non gestisce.
scrivi("lavoro_driver_ignoto",
       {"scontrino.wec": b"qualcosa\n"},
       {"scontrino.wec": stampante(tipo_mf="WINECR", formato_dati=None,
                                   denominazione="DITRON")})

# Collegamento non di rete.
scrivi("lavoro_seriale",
       {"comanda.escpos": BYTE_COMANDA},
       {"comanda.escpos": stampante(connessione="seriale")})

# Il manifest elenca un file che nell'archivio non c'e'.
scrivi("lavoro_file_mancante",
       {"altro.escpos": BYTE_COMANDA},
       {"comanda.escpos": stampante()})

# Archivio senza service_file: non si sa dove mandare niente.
scrivi("lavoro_senza_manifest",
       {"comanda.escpos": BYTE_COMANDA},
       {}, con_manifest=False)

# Stampante spenta: indirizzo che non risponde.
scrivi("lavoro_stampante_spenta",
       {"comanda.escpos": BYTE_COMANDA},
       {"comanda.escpos": stampante(ip="127.0.0.9")})

print()
print("Fatto.")
