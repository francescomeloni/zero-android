package it.sublima.zeroandroid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lettore JSON, quel tanto che basta per il campo `data` che manda Sublima.
 *
 * Di quel messaggio servono tre valori — tipo, filescontrino, corpo_scontrino —
 * ma il resto va comunque attraversato senza inciampare. Niente librerie: su
 * Android org.json ci sarebbe, ma tenendo la classe pura tutto resta
 * verificabile su JVM.
 *
 * I numeri diventano Long o Double, i booleani Boolean, null diventa null.
 */
public final class JsonLettore {

    private final String testo;
    private int i;

    private JsonLettore(String testo) {
        this.testo = testo;
    }

    public static class JsonNonValido extends Exception {
        public JsonNonValido(String messaggio) {
            super(messaggio);
        }
    }

    /** Legge un oggetto JSON e restituisce le sue voci. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> oggetto(String testo) throws JsonNonValido {
        if (testo == null || testo.trim().isEmpty()) {
            throw new JsonNonValido("messaggio vuoto");
        }
        JsonLettore l = new JsonLettore(testo);
        l.spazi();
        Object valore = l.valore();
        l.spazi();
        if (l.i < l.testo.length()) {
            throw new JsonNonValido("caratteri di troppo dopo la fine, alla posizione " + l.i);
        }
        if (!(valore instanceof Map)) {
            throw new JsonNonValido("atteso un oggetto, trovato " + descrivi(valore));
        }
        return (Map<String, Object>) valore;
    }

    /** Comodita': legge una voce come testo, con un valore di riserva. */
    public static String testo(Map<String, Object> mappa, String chiave, String riserva) {
        Object v = mappa == null ? null : mappa.get(chiave);
        return v == null ? riserva : String.valueOf(v);
    }

    private Object valore() throws JsonNonValido {
        spazi();
        if (i >= testo.length()) {
            throw new JsonNonValido("messaggio troncato");
        }
        char c = testo.charAt(i);
        switch (c) {
            case '{':
                return mappa();
            case '[':
                return lista();
            case '"':
                return stringa();
            case 't':
                atteso("true");
                return Boolean.TRUE;
            case 'f':
                atteso("false");
                return Boolean.FALSE;
            case 'n':
                atteso("null");
                return null;
            default:
                return numero();
        }
    }

    private Map<String, Object> mappa() throws JsonNonValido {
        Map<String, Object> risultato = new LinkedHashMap<String, Object>();
        i++; // {
        spazi();
        if (i < testo.length() && testo.charAt(i) == '}') {
            i++;
            return risultato;
        }
        while (true) {
            spazi();
            if (i >= testo.length() || testo.charAt(i) != '"') {
                throw new JsonNonValido("attesa una chiave alla posizione " + i);
            }
            String chiave = stringa();
            spazi();
            if (i >= testo.length() || testo.charAt(i) != ':') {
                throw new JsonNonValido("atteso ':' dopo la chiave " + chiave);
            }
            i++;
            risultato.put(chiave, valore());
            spazi();
            if (i >= testo.length()) {
                throw new JsonNonValido("oggetto non chiuso");
            }
            char c = testo.charAt(i);
            if (c == ',') {
                i++;
                continue;
            }
            if (c == '}') {
                i++;
                return risultato;
            }
            throw new JsonNonValido("atteso ',' o '}' alla posizione " + i);
        }
    }

    private List<Object> lista() throws JsonNonValido {
        List<Object> risultato = new ArrayList<Object>();
        i++; // [
        spazi();
        if (i < testo.length() && testo.charAt(i) == ']') {
            i++;
            return risultato;
        }
        while (true) {
            risultato.add(valore());
            spazi();
            if (i >= testo.length()) {
                throw new JsonNonValido("elenco non chiuso");
            }
            char c = testo.charAt(i);
            if (c == ',') {
                i++;
                continue;
            }
            if (c == ']') {
                i++;
                return risultato;
            }
            throw new JsonNonValido("atteso ',' o ']' alla posizione " + i);
        }
    }

    private String stringa() throws JsonNonValido {
        StringBuilder sb = new StringBuilder();
        i++; // "
        while (i < testo.length()) {
            char c = testo.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= testo.length()) {
                break;
            }
            char e = testo.charAt(i++);
            switch (e) {
                case '"':  sb.append('"');  break;
                case '\\': sb.append('\\'); break;
                case '/':  sb.append('/');  break;
                case 'b':  sb.append('\b'); break;
                case 'f':  sb.append('\f'); break;
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'u':
                    if (i + 4 > testo.length()) {
                        throw new JsonNonValido("sequenza \\u incompleta");
                    }
                    sb.append((char) Integer.parseInt(testo.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default:
                    throw new JsonNonValido("sequenza di escape sconosciuta: \\" + e);
            }
        }
        throw new JsonNonValido("stringa non chiusa");
    }

    private Object numero() throws JsonNonValido {
        int da = i;
        while (i < testo.length() && "+-0123456789.eE".indexOf(testo.charAt(i)) >= 0) {
            i++;
        }
        String pezzo = testo.substring(da, i);
        if (pezzo.isEmpty()) {
            throw new JsonNonValido("valore non riconosciuto alla posizione " + da);
        }
        try {
            if (pezzo.indexOf('.') >= 0 || pezzo.indexOf('e') >= 0 || pezzo.indexOf('E') >= 0) {
                return Double.valueOf(pezzo);
            }
            return Long.valueOf(pezzo);
        } catch (NumberFormatException e) {
            throw new JsonNonValido("numero non valido: " + pezzo);
        }
    }

    private void atteso(String parola) throws JsonNonValido {
        if (!testo.startsWith(parola, i)) {
            throw new JsonNonValido("atteso '" + parola + "' alla posizione " + i);
        }
        i += parola.length();
    }

    private void spazi() {
        while (i < testo.length()) {
            char c = testo.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
    }

    private static String descrivi(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }
}
