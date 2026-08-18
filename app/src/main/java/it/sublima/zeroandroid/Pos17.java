package it.sublima.zeroandroid;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Protocollo 17 dei POS Ingenico: costruzione dei comandi e lettura degli esiti.
 *
 * Qui dentro non c'e' rete: solo byte, campi e conversioni. La conversazione
 * col terminale sta in {@link Pos17Conversazione}. La separazione e' voluta —
 * questa parte si verifica contro i frame reali catturati dai terminali, senza
 * hardware e senza socket.
 *
 * Port fedele di `lib_zero/prot17.py` dello Zero. Ogni riga di quel file nasce
 * da un incidente, quindi qui non si migliora niente di propria iniziativa:
 * dove ci si discosta, lo si dichiara.
 *
 * ⚠️ I cinque punti che le implementazioni precedenti avevano sbagliato, e che
 * sono costati denaro vero:
 *
 *  1. ogni frame ricevuto va RISCONTRATO con un ACK (sta nella conversazione);
 *  2. TCP e' un flusso, non un messaggio: il riassemblaggio non e' opzionale;
 *  3. gli offset del parser si contano sul payload, non sul frame con lo STX;
 *  4. l'identificativo del terminale NON va confrontato: il POS risponde col
 *     PROPRIO, e confrontarlo faceva scartare esiti di transazioni GIA' PAGATE;
 *  5. gli importi si trattano con BigDecimal, mai con double: `4.99 * 100` in
 *     virgola mobile fa 498.99999999999994.
 */
public final class Pos17 {

    public static final byte STX = 0x02;
    public static final byte ETX = 0x03;
    public static final byte SOH = 0x01;
    public static final byte EOT = 0x04;
    public static final int BASE_LRC = 0x7F;

    /** Il terminale ha preso in carico il comando. */
    public static final byte[] ACK = {0x06, ETX, (byte) (BASE_LRC ^ 0x06 ^ ETX)};
    /** Il terminale rifiuta il nostro frame. */
    public static final byte[] NAK = {0x15, ETX, (byte) (BASE_LRC ^ 0x15 ^ ETX)};

    /** Messaggio di stato: SOH + 20 caratteri + EOT. */
    public static final int LUNGH_STATO = 22;

    /** Oltre questa soglia il buffer si tronca: un frame che non si chiude mai
     *  non deve far crescere la memoria all'infinito. */
    public static final int MAX_BUFFER = 65536;

    /** Il protocollo e' a byte: l'encoding e' latin-1, non UTF-8. */
    public static final String CODIFICA = "ISO-8859-1";

    private Pos17() {
    }

    // ---- esiti ---------------------------------------------------------------

    public static String descrizioneEsito(String codice) {
        if ("00".equals(codice)) {
            return "OK - transazione approvata";
        }
        if ("01".equals(codice)) {
            return "KO - transazione rifiutata";
        }
        if ("05".equals(codice)) {
            return "KO - carta non presente nel lettore";
        }
        if ("09".equals(codice)) {
            return "KO - TAG da GT non previsto";
        }
        return "esito sconosciuto";
    }

    /**
     * Codici messaggio che portano un esito di transazione.
     *
     * Il filtro e' voluto e va tenuto GENEROSO: serve a non scambiare per esito
     * i frame che il terminale manda per altro. Lo scontrino ('S') e lo stato
     * ('s') hanno due zeri nelle posizioni dell'esito, e un riconoscimento
     * basato sulla sola forma leggerebbe lo stato come "pagamento approvato".
     */
    private static final String CODICI_MESSAGGIO_ESITO = "EVeci";

    // ---- LRC e incapsulamento ------------------------------------------------

    /** XOR di tutti i byte con base 0x7F. Il manuale lo calcola su STX..ETX inclusi. */
    public static int lrc(byte[] dati, int da, int quanti) {
        int valore = BASE_LRC;
        for (int i = da; i < da + quanti; i++) {
            valore ^= (dati[i] & 0xFF);
        }
        return valore & 0xFF;
    }

    /** Messaggio applicativo -> frame completo STX + payload + ETX + LRC. */
    public static byte[] incapsula(String payload) {
        byte[] p = byteDi(payload);
        byte[] frame = new byte[p.length + 3];
        frame[0] = STX;
        System.arraycopy(p, 0, frame, 1, p.length);
        frame[p.length + 1] = ETX;
        frame[p.length + 2] = (byte) lrc(frame, 0, p.length + 2);
        return frame;
    }

    /**
     * Vero se il LRC in coda al frame ricevuto e' quello giusto.
     * Un frame corrotto va rifiutato con NAK: il terminale lo ripete.
     */
    public static boolean verificaLrc(byte[] frame) {
        if (frame == null || frame.length < 3 || frame[0] != STX) {
            return false;
        }
        return (frame[frame.length - 1] & 0xFF) == lrc(frame, 0, frame.length - 1);
    }

    // ---- normalizzazione dei campi -------------------------------------------

    /**
     * ID POS / ID cassa -> esattamente 8 cifre.
     *
     * ⚠️ Il frame ha campi FISSI: un ID di 7 cifre sposta di una posizione tutto
     * quello che segue, il frame esce di 169 byte invece di 170 e il terminale
     * lo scarta **senza dire niente**. Si tengono le prime 8 cifre e si riempie
     * a sinistra con zeri, come il driver di riferimento.
     */
    public static String normalizzaId(Object valore) {
        String testo = (valore == null) ? "" : String.valueOf(valore);
        StringBuilder cifre = new StringBuilder();
        for (int i = 0; i < testo.length(); i++) {
            if (Character.isDigit(testo.charAt(i))) {
                cifre.append(testo.charAt(i));
            }
        }
        String s = cifre.length() > 8 ? cifre.substring(0, 8) : cifre.toString();
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < 8; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }

    public static class ImportoNonValido extends Exception {
        public ImportoNonValido(String messaggio) {
            super(messaggio);
        }
    }

    /**
     * Importo in EURO -> centesimi interi.
     *
     * ⚠️ Sono soldi: si usa BigDecimal e non double. Accetta sia il punto sia la
     * virgola come separatore decimale, perche' la vendita manda un testo.
     */
    public static long centesimi(Object importoEuro) throws ImportoNonValido {
        String testo = (importoEuro == null) ? "" : String.valueOf(importoEuro).trim();
        testo = testo.replace(",", ".");
        if (testo.isEmpty()) {
            throw new ImportoNonValido("importo vuoto");
        }
        BigDecimal valore;
        try {
            valore = new BigDecimal(testo);
        } catch (NumberFormatException e) {
            throw new ImportoNonValido("importo non interpretabile come euro: " + testo);
        }
        valore = valore.setScale(2, RoundingMode.HALF_UP);
        if (valore.signum() <= 0) {
            throw new ImportoNonValido("importo non positivo: " + valore + " EUR");
        }
        long cent = valore.movePointRight(2).longValueExact();
        if (cent > 99999999L) {
            throw new ImportoNonValido(
                    "importo oltre il massimo del protocollo (8 cifre): " + valore + " EUR");
        }
        return cent;
    }

    /**
     * Cosa non va nei campi della barriera casse.
     *
     * Nessuno di questi casi impedisce di costruire il frame, ma tutti producono
     * un pacchetto che il terminale scarta in silenzio: senza un avviso si
     * finisce ad aspettare il timeout per nulla.
     */
    public static java.util.List<String> anomalieConfigurazione(String idPos, String idCassa,
                                                                String contratto) {
        java.util.List<String> problemi = new java.util.ArrayList<String>();
        String[][] campi = {
                {"ID POS (campo Modello)", idPos},
                {"ID cassa (campo Matricola)", idCassa},
        };
        for (String[] campo : campi) {
            String nome = campo[0];
            String testo = (campo[1] == null) ? "" : campo[1];
            if (testo.isEmpty()) {
                problemi.add(nome + " vuoto: verra' mandato 00000000");
                continue;
            }
            boolean soloCifre = true;
            for (int i = 0; i < testo.length(); i++) {
                if (!Character.isDigit(testo.charAt(i))) {
                    soloCifre = false;
                    break;
                }
            }
            if (!soloCifre) {
                problemi.add(nome + " contiene caratteri non numerici (" + testo
                        + "): il protocollo vuole 8 cifre");
            }
            if (testo.length() != 8) {
                problemi.add(nome + " e' di " + testo.length() + " caratteri invece di 8 ("
                        + testo + ")");
            }
        }
        if (contratto != null && contratto.length() > 128) {
            problemi.add("codice contratto di " + contratto.length()
                    + " caratteri: verra' troncato a 128");
        }
        return problemi;
    }

    // ---- costruzione dei comandi ---------------------------------------------

    /**
     * Comando di PAGAMENTO ('P'), o con risultato esteso ('X').
     *
     * Messaggio applicativo di 167 caratteri, frame completo di 170 byte:
     * <pre>
     *   pos   1   8  Terminal ID
     *   pos   9   1  riservato '0'
     *   pos  10   1  codice messaggio 'P' (o 'X')
     *   pos  11   8  Cash register ID
     *   pos  19   1  dati GT '0'
     *   pos  20   2  riservato '0'
     *   pos  22   1  partenza transazione '0' (carta non inserita)
     *   pos  23   1  tipo pagamento '0' (automatico)
     *   pos  24   8  importo in centesimi, zeri a sinistra
     *   pos  32 128  codice contratto, allineato a DESTRA con spazi
     *   pos 160   8  riservato '0'
     * </pre>
     */
    public static byte[] framePagamento(String idPos, String idCassa, Object importoEuro,
                                        String contratto, boolean esteso)
            throws ImportoNonValido {
        String c = (contratto == null) ? "" : contratto;
        if (c.length() > 128) {
            c = c.substring(0, 128);
        }
        String payload = normalizzaId(idPos)
                + "0" + (esteso ? "X" : "P")
                + normalizzaId(idCassa)
                + "00000"
                + String.format("%08d", centesimi(importoEuro))
                + String.format("%128s", c)
                + "00000000";
        return incapsula(payload);
    }

    /**
     * Comando 'G' — invia ultimo risultato salvato.
     *
     * Il terminale rispedisce copia esatta dell'ultimo esito che ha in memoria,
     * e lo conserva anche dopo altre transazioni. E' l'unico modo di sapere se
     * un pagamento e' stato incassato quando la risposta si e' persa.
     */
    public static byte[] frameUltimoEsito(String idPos, String idCassa) {
        return incapsula(normalizzaId(idPos) + "0G" + normalizzaId(idCassa) + "0000");
    }

    /**
     * Comando 's' — stato del terminale.
     *
     * Non muove denaro e non richiede la carta: e' la verifica di collegamento
     * piu' economica. La risposta porta l'ID VERO del terminale, che non e'
     * detto sia quello configurato.
     */
    public static byte[] frameStato(String idPos) {
        return incapsula(normalizzaId(idPos) + "0s");
    }

    // ---- lettura della risposta ----------------------------------------------

    /**
     * Vero se il messaggio e' una risposta di esito.
     *
     * ⚠️ Si controlla la FORMA, non QUALE identificativo sia: il terminale
     * risponde col PROPRIO. Un terminale visto sul campo si presentava come
     * un identificativo diverso da quello configurato, e confrontare gli ID
     * faceva scartare esiti di transazioni gia' pagate.
     */
    public static boolean eEsito(String payload) {
        return payload != null
                && payload.length() >= 12
                && soloCifre(payload.substring(0, 8))
                && CODICI_MESSAGGIO_ESITO.indexOf(payload.charAt(9)) >= 0
                && soloCifre(payload.substring(10, 12));
    }

    /** Vero se il messaggio e' una risposta di stato terminale. */
    public static boolean eStato(String payload) {
        return payload != null
                && payload.length() >= 31
                && soloCifre(payload.substring(0, 8))
                && payload.charAt(9) == 's';
    }

    /**
     * Messaggio di esito -> campi.
     *
     * Unica fonte di verita' per gli offset, contati sul payload SENZA STX:
     * <pre>
     *   [0:8]   identificativo terminale   [9]      codice messaggio
     *   [10:12] esito
     *   comuni a ogni esito (fra positivo e negativo cambiano solo le 13-47):
     *   [47]    tipo carta                 [48:59]  id acquirer
     *   [59:65] STAN                       [65:71]  progressivo
     *   esito "00": [12:31] PAN  [31:34] tipo transaz.  [34:40] cod. autorizzazione
     *               [40:47] data e ora dall'host
     *   esito "01": [12:36] descrizione del motivo
     *   solo 'X':   [71:74] action code    [74:82]  importo dall'host
     * </pre>
     */
    public static Map<String, String> parseEsito(String payload, String idPosAtteso) {
        Map<String, String> d = new LinkedHashMap<String, String>();
        d.put("id_pos", payload.substring(0, 8).trim());
        d.put("riservato1", payload.substring(8, 9));
        d.put("codice_messaggio", payload.substring(9, 10));
        String esito = payload.substring(10, 12);
        d.put("esito", esito);
        d.put("descrizione_codice_esito", descrizioneEsito(esito));

        if (idPosAtteso != null) {
            String configurato = normalizzaId(idPosAtteso);
            d.put("id_pos_configurato", configurato);
            d.put("id_pos_allineato", String.valueOf(configurato.equals(d.get("id_pos"))));
        }

        // ⚠️ Questi quattro si leggono SEMPRE, anche su una transazione rifiutata:
        // senza lo STAN di un KO, il recupero col comando 'G' non distinguerebbe
        // un rifiuto nuovo da un esito vecchio rimasto in memoria.
        d.put("tipo_carta", payload.substring(47, 48));
        d.put("id_acquirer", payload.substring(48, 59).trim());
        d.put("STAN", payload.substring(59, 65));
        d.put("num_progressivo", payload.substring(65, 71).trim());

        if ("00".equals(esito)) {
            d.put("esito_interno", "OK");
            d.put("PAN", payload.substring(12, 31).trim());
            d.put("tipo_transazione", payload.substring(31, 34));
            d.put("codice_autorizzazione", payload.substring(34, 40));
            d.put("dati_temporali", payload.substring(40, 47));
        } else if ("01".equals(esito)) {
            d.put("esito_interno", "KO");
            String motivo = payload.substring(12, 36).trim();
            d.put("descrizione_esito", motivo.isEmpty() ? "Errore sconosciuto" : motivo);
        } else {
            d.put("esito_interno", "KO");
            d.put("descrizione_esito", "00".equals(esito) || "01".equals(esito)
                    ? descrizioneEsito(esito) : "Esito sconosciuto: " + esito);
        }

        // Risultato esteso: c'e' solo se abbiamo chiesto 'X', e non si distingue
        // dal codice messaggio (resta 'E') ma dalla lunghezza.
        if (payload.length() >= 82) {
            d.put("action_code", payload.substring(71, 74));
            String importoHost = payload.substring(74, 82);
            d.put("importo_host_centesimi", importoHost);
            if (soloCifre(importoHost)) {
                d.put("importo_host_euro", new BigDecimal(importoHost)
                        .movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
        }

        d.put("riassunto", "esito " + esito + ": " + descrizioneEsito(esito));
        return d;
    }

    // ---- utilita' -------------------------------------------------------------

    public static byte[] byteDi(String s) {
        try {
            return s.getBytes(CODIFICA);
        } catch (UnsupportedEncodingException e) {
            // latin-1 c'e' su ogni JVM e su Android
            throw new IllegalStateException(CODIFICA + " non disponibile");
        }
    }

    public static String testoDi(byte[] dati, int da, int quanti) {
        try {
            return new String(dati, da, quanti, CODIFICA);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(CODIFICA + " non disponibile");
        }
    }

    private static boolean soloCifre(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
