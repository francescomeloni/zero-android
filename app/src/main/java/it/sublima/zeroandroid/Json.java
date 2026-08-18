package it.sublima.zeroandroid;

import java.util.ArrayList;
import java.util.List;

/**
 * Costruzione delle risposte JSON, senza librerie esterne.
 *
 * Serve solo a scrivere: quello che Sublima manda si legge altrove. L'ordine di
 * inserimento viene mantenuto, cosi' le risposte restano leggibili nei log e
 * confrontabili fra una versione e l'altra.
 */
public final class Json {

    private final List<String> voci = new ArrayList<String>();

    public Json testo(String chiave, String valore) {
        voci.add(virgolette(chiave) + ":" + (valore == null ? "null" : virgolette(valore)));
        return this;
    }

    public Json numero(String chiave, long valore) {
        voci.add(virgolette(chiave) + ":" + valore);
        return this;
    }

    public Json vero(String chiave, boolean valore) {
        voci.add(virgolette(chiave) + ":" + (valore ? "true" : "false"));
        return this;
    }

    /** Innesta un altro oggetto gia' costruito. */
    public Json oggetto(String chiave, Json valore) {
        voci.add(virgolette(chiave) + ":" + (valore == null ? "null" : valore.toString()));
        return this;
    }

    /** Innesta una lista di oggetti. */
    public Json elenco(String chiave, List<Json> valori) {
        StringBuilder sb = new StringBuilder("[");
        if (valori != null) {
            for (int i = 0; i < valori.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(valori.get(i).toString());
            }
        }
        sb.append(']');
        voci.add(virgolette(chiave) + ":" + sb);
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < voci.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(voci.get(i));
        }
        return sb.append('}').toString();
    }

    /** Mette fra virgolette proteggendo i caratteri che romperebbero il JSON. */
    public static String virgolette(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
