package it.sublima.zeroandroid;

/**
 * Decodifica base64, che e' come viaggia lo ZIP dentro `corpo_scontrino`.
 *
 * Scritta qui invece di usare java.util.Base64 (che vuole Android 8) o
 * android.util.Base64 (che legherebbe la classe al dispositivo e la
 * renderebbe non verificabile su JVM). Sono poche righe e funzionano ovunque.
 *
 * Gli a capo vengono ignorati: alcuni produttori di base64 li inseriscono ogni
 * 76 caratteri.
 */
public final class Base64 {

    private Base64() {
    }

    private static final int[] VALORE = new int[128];

    static {
        for (int i = 0; i < VALORE.length; i++) {
            VALORE[i] = -1;
        }
        String alfabeto = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < alfabeto.length(); i++) {
            VALORE[alfabeto.charAt(i)] = i;
        }
        // accettato anche l'alfabeto per URL, non costa nulla
        VALORE['-'] = 62;
        VALORE['_'] = 63;
    }

    public static class Base64NonValido extends Exception {
        public Base64NonValido(String messaggio) {
            super(messaggio);
        }
    }

    private static final char[] ALFABETO =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Serve all'autenticazione delle stampanti Custom, che la vuole in questa forma. */
    public static String codifica(byte[] dati) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i + 2 < dati.length) {
            int gruppo = ((dati[i] & 0xFF) << 16) | ((dati[i + 1] & 0xFF) << 8)
                    | (dati[i + 2] & 0xFF);
            sb.append(ALFABETO[(gruppo >> 18) & 63]).append(ALFABETO[(gruppo >> 12) & 63])
              .append(ALFABETO[(gruppo >> 6) & 63]).append(ALFABETO[gruppo & 63]);
            i += 3;
        }
        int avanzo = dati.length - i;
        if (avanzo == 1) {
            int gruppo = (dati[i] & 0xFF) << 16;
            sb.append(ALFABETO[(gruppo >> 18) & 63]).append(ALFABETO[(gruppo >> 12) & 63])
              .append("==");
        } else if (avanzo == 2) {
            int gruppo = ((dati[i] & 0xFF) << 16) | ((dati[i + 1] & 0xFF) << 8);
            sb.append(ALFABETO[(gruppo >> 18) & 63]).append(ALFABETO[(gruppo >> 12) & 63])
              .append(ALFABETO[(gruppo >> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    public static byte[] decodifica(String testo) throws Base64NonValido {
        if (testo == null) {
            throw new Base64NonValido("nessun contenuto");
        }
        int lunghezza = testo.length();
        byte[] uscita = new byte[(lunghezza / 4 + 1) * 3];
        int quanti = 0;
        int accumulatore = 0;
        int pezzi = 0;

        for (int i = 0; i < lunghezza; i++) {
            char c = testo.charAt(i);
            if (c == '=' ) {
                break;
            }
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                continue;
            }
            int v = (c < 128) ? VALORE[c] : -1;
            if (v < 0) {
                throw new Base64NonValido("carattere non valido alla posizione " + i
                        + ": '" + c + "'");
            }
            accumulatore = (accumulatore << 6) | v;
            pezzi++;
            if (pezzi == 4) {
                uscita[quanti++] = (byte) (accumulatore >> 16);
                uscita[quanti++] = (byte) (accumulatore >> 8);
                uscita[quanti++] = (byte) accumulatore;
                accumulatore = 0;
                pezzi = 0;
            }
        }

        // gli ultimi caratteri, quando non sono un gruppo intero di quattro
        if (pezzi == 2) {
            uscita[quanti++] = (byte) (accumulatore >> 4);
        } else if (pezzi == 3) {
            uscita[quanti++] = (byte) (accumulatore >> 10);
            uscita[quanti++] = (byte) (accumulatore >> 2);
        } else if (pezzi == 1) {
            throw new Base64NonValido("contenuto troncato: avanza un carattere solo");
        }

        byte[] esatto = new byte[quanti];
        System.arraycopy(uscita, 0, esatto, 0, quanti);
        return esatto;
    }
}
