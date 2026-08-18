package it.sublima.zeroandroid;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lettore del manifest `service_file_*.pickle` che Sublima mette nello ZIP.
 *
 * Non e' un'implementazione del pickle di Python: e' il sottoinsieme che serve a
 * leggere quel manifest, cioe' un dizionario piatto di dizionari con dentro solo
 * stringhe, interi, booleani e None. Gli opcode che costruiscono oggetti
 * (REDUCE, GLOBAL, INST) non compaiono nei payload di Sublima e qui vengono
 * rifiutati invece che interpretati a caso: meglio un errore chiaro che una
 * stampa sbagliata.
 *
 * Riferimento del formato: paragrafo 2 di PROTOCOLLO.md nel repo dello Zero.
 */
public final class Pickle {

    private Pickle() {
    }

    // Opcode usati dai manifest di Sublima (protocolli 2..5).
    private static final byte PROTO = (byte) 0x80;
    private static final byte FRAME = (byte) 0x95;
    private static final byte EMPTY_DICT = '}';
    private static final byte DICT = 'd';
    private static final byte MARK = '(';
    private static final byte SETITEM = 's';
    private static final byte SETITEMS = 'u';
    private static final byte SHORT_BINUNICODE = (byte) 0x8c;
    private static final byte BINUNICODE = 'X';
    private static final byte BINUNICODE8 = (byte) 0x8d;
    private static final byte SHORT_BINBYTES = (byte) 0x43;
    private static final byte BININT = 'J';
    private static final byte BININT1 = 'K';
    private static final byte BININT2 = 'M';
    private static final byte LONG1 = (byte) 0x8a;
    private static final byte BINFLOAT = 'G';
    private static final byte NEWTRUE = (byte) 0x88;
    private static final byte NEWFALSE = (byte) 0x89;
    private static final byte NONE = 'N';
    private static final byte MEMOIZE = (byte) 0x94;
    private static final byte BINPUT = 'q';
    private static final byte LONG_BINPUT = 'r';
    private static final byte BINGET = 'h';
    private static final byte LONG_BINGET = 'j';
    private static final byte EMPTY_LIST = ']';
    private static final byte APPEND = 'a';
    private static final byte APPENDS = 'e';
    private static final byte EMPTY_TUPLE = ')';
    private static final byte TUPLE = 't';
    private static final byte TUPLE1 = (byte) 0x85;
    private static final byte TUPLE2 = (byte) 0x86;
    private static final byte TUPLE3 = (byte) 0x87;
    private static final byte STOP = '.';

    /** Segnaposto dello stack per l'opcode MARK. */
    private static final Object SEGNO = new Object();

    public static class PickleNonLeggibile extends Exception {
        public PickleNonLeggibile(String messaggio) {
            super(messaggio);
        }
    }

    /**
     * Legge il manifest e restituisce il dizionario esterno:
     * nome del file di stampa -> dati della stampante.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> leggiManifest(byte[] dati) throws PickleNonLeggibile {
        Object radice = leggi(dati);
        if (!(radice instanceof Map)) {
            throw new PickleNonLeggibile(
                    "il manifest non contiene un dizionario ma " + descrivi(radice));
        }
        return (Map<String, Object>) radice;
    }

    /** Interpreta il flusso e restituisce l'oggetto in cima allo stack. */
    public static Object leggi(byte[] dati) throws PickleNonLeggibile {
        if (dati == null || dati.length == 0) {
            throw new PickleNonLeggibile("manifest vuoto");
        }
        List<Object> pila = new ArrayList<Object>();
        List<Object> memo = new ArrayList<Object>();
        int i = 0;

        while (i < dati.length) {
            byte op = dati[i++];
            switch (op) {
                case PROTO:
                    i += 1;
                    break;
                case FRAME:
                    i += 8;
                    break;
                case EMPTY_DICT:
                    pila.add(new LinkedHashMap<String, Object>());
                    break;
                case EMPTY_LIST:
                    pila.add(new ArrayList<Object>());
                    break;
                case EMPTY_TUPLE:
                    pila.add(new ArrayList<Object>());
                    break;
                case MARK:
                    pila.add(SEGNO);
                    break;
                case NONE:
                    pila.add(null);
                    break;
                case NEWTRUE:
                    pila.add(Boolean.TRUE);
                    break;
                case NEWFALSE:
                    pila.add(Boolean.FALSE);
                    break;
                case BININT1:
                    pila.add(Long.valueOf(dati[i] & 0xFF));
                    i += 1;
                    break;
                case BININT2:
                    pila.add(Long.valueOf((dati[i] & 0xFF) | ((dati[i + 1] & 0xFF) << 8)));
                    i += 2;
                    break;
                case BININT:
                    // con segno, a differenza di BININT1 e BININT2
                    pila.add(Long.valueOf(interoLEconSegno(dati, i, 4)));
                    i += 4;
                    break;
                case LONG1: {
                    int quanti = dati[i] & 0xFF;
                    i += 1;
                    pila.add(Long.valueOf(quanti == 0 ? 0 : interoLEconSegno(dati, i, quanti)));
                    i += quanti;
                    break;
                }
                case BINFLOAT: {
                    long grezzo = 0;
                    for (int k = 0; k < 8; k++) {
                        grezzo = (grezzo << 8) | (dati[i + k] & 0xFFL);
                    }
                    pila.add(Double.valueOf(Double.longBitsToDouble(grezzo)));
                    i += 8;
                    break;
                }
                case SHORT_BINUNICODE:
                case SHORT_BINBYTES: {
                    int quanti = dati[i] & 0xFF;
                    i += 1;
                    pila.add(testo(dati, i, quanti));
                    i += quanti;
                    break;
                }
                case BINUNICODE: {
                    int quanti = (int) interoLE(dati, i, 4);
                    i += 4;
                    pila.add(testo(dati, i, quanti));
                    i += quanti;
                    break;
                }
                case BINUNICODE8: {
                    int quanti = (int) interoLE(dati, i, 8);
                    i += 8;
                    pila.add(testo(dati, i, quanti));
                    i += quanti;
                    break;
                }
                case MEMOIZE:
                    memo.add(cima(pila));
                    break;
                case BINPUT:
                    metti(memo, dati[i] & 0xFF, cima(pila));
                    i += 1;
                    break;
                case LONG_BINPUT:
                    metti(memo, (int) interoLE(dati, i, 4), cima(pila));
                    i += 4;
                    break;
                case BINGET:
                    pila.add(prendi(memo, dati[i] & 0xFF));
                    i += 1;
                    break;
                case LONG_BINGET:
                    pila.add(prendi(memo, (int) interoLE(dati, i, 4)));
                    i += 4;
                    break;
                case SETITEM: {
                    Object valore = togli(pila);
                    Object chiave = togli(pila);
                    inserisci(cima(pila), chiave, valore);
                    break;
                }
                case SETITEMS: {
                    List<Object> coppie = finoAlSegno(pila);
                    Object mappa = cima(pila);
                    for (int k = 0; k + 1 < coppie.size(); k += 2) {
                        inserisci(mappa, coppie.get(k), coppie.get(k + 1));
                    }
                    break;
                }
                case DICT: {
                    List<Object> coppie = finoAlSegno(pila);
                    Map<String, Object> mappa = new LinkedHashMap<String, Object>();
                    for (int k = 0; k + 1 < coppie.size(); k += 2) {
                        inserisci(mappa, coppie.get(k), coppie.get(k + 1));
                    }
                    pila.add(mappa);
                    break;
                }
                case APPEND: {
                    Object valore = togli(pila);
                    aggiungi(cima(pila), valore);
                    break;
                }
                case APPENDS: {
                    List<Object> valori = finoAlSegno(pila);
                    Object lista = cima(pila);
                    for (int k = 0; k < valori.size(); k++) {
                        aggiungi(lista, valori.get(k));
                    }
                    break;
                }
                case TUPLE: {
                    List<Object> valori = finoAlSegno(pila);
                    pila.add(valori);
                    break;
                }
                case TUPLE1:
                    pila.add(raccogli(pila, 1));
                    break;
                case TUPLE2:
                    pila.add(raccogli(pila, 2));
                    break;
                case TUPLE3:
                    pila.add(raccogli(pila, 3));
                    break;
                case STOP:
                    return cima(pila);
                default:
                    throw new PickleNonLeggibile(String.format(
                            "opcode non gestito 0x%02X ('%s') alla posizione %d: "
                                    + "il manifest non ha la forma attesa",
                            op, (op >= 32 && op < 127) ? String.valueOf((char) op) : "?", i - 1));
            }
        }
        throw new PickleNonLeggibile("manifest troncato: manca l'opcode di fine");
    }

    // ---- utilita' interne ---------------------------------------------------

    /** Intero little-endian senza segno: lunghezze e riferimenti al memo. */
    private static long interoLE(byte[] dati, int da, int quanti) {
        long valore = 0;
        for (int k = quanti - 1; k >= 0; k--) {
            valore = (valore << 8) | (dati[da + k] & 0xFFL);
        }
        return valore;
    }

    /** Intero little-endian in complemento a due: i valori veri del manifest. */
    private static long interoLEconSegno(byte[] dati, int da, int quanti) {
        long valore = interoLE(dati, da, quanti);
        long segno = 1L << (quanti * 8 - 1);
        if ((valore & segno) != 0) {
            valore -= (segno << 1);
        }
        return valore;
    }

    private static String testo(byte[] dati, int da, int quanti) throws PickleNonLeggibile {
        try {
            return new String(dati, da, quanti, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new PickleNonLeggibile("UTF-8 non disponibile");
        }
    }

    private static Object cima(List<Object> pila) throws PickleNonLeggibile {
        if (pila.isEmpty()) {
            throw new PickleNonLeggibile("manifest malformato: pila vuota");
        }
        return pila.get(pila.size() - 1);
    }

    private static Object togli(List<Object> pila) throws PickleNonLeggibile {
        if (pila.isEmpty()) {
            throw new PickleNonLeggibile("manifest malformato: pila vuota");
        }
        return pila.remove(pila.size() - 1);
    }

    private static List<Object> finoAlSegno(List<Object> pila) throws PickleNonLeggibile {
        int segno = -1;
        for (int k = pila.size() - 1; k >= 0; k--) {
            if (pila.get(k) == SEGNO) {
                segno = k;
                break;
            }
        }
        if (segno < 0) {
            throw new PickleNonLeggibile("manifest malformato: manca il segnaposto");
        }
        List<Object> presi = new ArrayList<Object>(pila.subList(segno + 1, pila.size()));
        while (pila.size() > segno) {
            pila.remove(pila.size() - 1);
        }
        return presi;
    }

    private static List<Object> raccogli(List<Object> pila, int quanti)
            throws PickleNonLeggibile {
        List<Object> presi = new ArrayList<Object>();
        for (int k = 0; k < quanti; k++) {
            presi.add(0, togli(pila));
        }
        return presi;
    }

    private static void metti(List<Object> memo, int posizione, Object valore) {
        while (memo.size() <= posizione) {
            memo.add(null);
        }
        memo.set(posizione, valore);
    }

    private static Object prendi(List<Object> memo, int posizione) throws PickleNonLeggibile {
        if (posizione < 0 || posizione >= memo.size()) {
            throw new PickleNonLeggibile("manifest malformato: riferimento " + posizione
                    + " non memorizzato");
        }
        return memo.get(posizione);
    }

    @SuppressWarnings("unchecked")
    private static void inserisci(Object mappa, Object chiave, Object valore)
            throws PickleNonLeggibile {
        if (!(mappa instanceof Map)) {
            throw new PickleNonLeggibile("manifest malformato: atteso un dizionario, trovato "
                    + descrivi(mappa));
        }
        ((Map<String, Object>) mappa).put(String.valueOf(chiave), valore);
    }

    @SuppressWarnings("unchecked")
    private static void aggiungi(Object lista, Object valore) throws PickleNonLeggibile {
        if (!(lista instanceof List)) {
            throw new PickleNonLeggibile("manifest malformato: attesa una lista, trovato "
                    + descrivi(lista));
        }
        ((List<Object>) lista).add(valore);
    }

    private static String descrivi(Object o) {
        return o == null ? "niente" : o.getClass().getSimpleName();
    }
}
