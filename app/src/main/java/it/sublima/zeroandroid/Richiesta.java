package it.sublima.zeroandroid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Una richiesta HTTP letta dal socket.
 *
 * Sublima chiama /stampa_all in due modi diversi a seconda di chi parla: il
 * browser manda multipart/form-data, il server manda x-www-form-urlencoded.
 * Qui si leggono entrambi e si espongono i campi allo stesso modo, cosi' il
 * resto del codice non deve sapere da dove arriva la stampa.
 *
 * Vedi il paragrafo 1 di PROTOCOLLO.md nel repo dello Zero.
 */
public final class Richiesta {

    /** Oltre questa soglia la richiesta viene rifiutata invece di riempire la memoria. */
    public static final int CORPO_MASSIMO = 48 * 1024 * 1024;

    private String metodo = "";
    private String percorso = "";
    private final Map<String, String> intestazioni = new LinkedHashMap<String, String>();
    private final Map<String, String> campi = new LinkedHashMap<String, String>();
    private byte[] corpo = new byte[0];

    public String metodo() {
        return metodo;
    }

    public String percorso() {
        return percorso;
    }

    public String intestazione(String nome) {
        return intestazioni.get(nome.toLowerCase());
    }

    /** Campo del form, comunque sia stato codificato. */
    public String campo(String nome) {
        return campi.get(nome);
    }

    public byte[] corpo() {
        return corpo;
    }

    public static class RichiestaNonValida extends IOException {
        public RichiestaNonValida(String messaggio) {
            super(messaggio);
        }
    }

    /**
     * Una richiesta costruita a mano, per chi porta un lavoro senza passare da
     * un socket.
     *
     * Serve all'ascolto delle comande: l'evento arriva da una connessione in
     * uscita, non da una richiesta HTTP, ma da li' in poi deve percorrere le
     * stesse identiche righe di codice di una stampa arrivata dal browser. Senza
     * questa scorciatoia servirebbe un secondo percorso di stampa, e due
     * percorsi divergono sempre.
     *
     * L'origine e' l'indirizzo di Sublima: e' quello che permette di restituire
     * il numero dello scontrino fiscale.
     */
    public static Richiesta sintetica(String data, String origine) {
        Richiesta r = new Richiesta();
        r.metodo = "POST";
        r.percorso = "/stampa_all";
        if (data != null) {
            r.campi.put("data", data);
        }
        if (origine != null && !origine.trim().isEmpty()) {
            r.intestazioni.put("origin", origine.trim());
        }
        return r;
    }

    public static Richiesta leggi(InputStream in) throws IOException {
        Richiesta r = new Richiesta();

        String prima = riga(in);
        if (prima == null || prima.isEmpty()) {
            throw new RichiestaNonValida("richiesta vuota");
        }
        String[] pezzi = prima.split(" ");
        if (pezzi.length < 2) {
            throw new RichiestaNonValida("prima riga malformata: " + prima);
        }
        r.metodo = pezzi[0].toUpperCase();
        String percorsoConQuery = pezzi[1];
        int domanda = percorsoConQuery.indexOf('?');
        if (domanda >= 0) {
            r.percorso = percorsoConQuery.substring(0, domanda);
            aggiungiCoppie(r.campi, percorsoConQuery.substring(domanda + 1));
        } else {
            r.percorso = percorsoConQuery;
        }

        String riga;
        while ((riga = riga(in)) != null && !riga.isEmpty()) {
            int duePunti = riga.indexOf(':');
            if (duePunti > 0) {
                r.intestazioni.put(riga.substring(0, duePunti).trim().toLowerCase(),
                        riga.substring(duePunti + 1).trim());
            }
        }

        int quanti = 0;
        String lunghezza = r.intestazione("content-length");
        if (lunghezza != null) {
            try {
                quanti = Integer.parseInt(lunghezza.trim());
            } catch (NumberFormatException e) {
                throw new RichiestaNonValida("content-length non numerico: " + lunghezza);
            }
        }
        if (quanti < 0 || quanti > CORPO_MASSIMO) {
            throw new RichiestaNonValida("corpo di " + quanti
                    + " byte: oltre il limite di " + CORPO_MASSIMO);
        }
        r.corpo = leggiEsatti(in, quanti);

        String tipo = r.intestazione("content-type");
        if (tipo == null) {
            tipo = "";
        }
        String tipoMinuscolo = tipo.toLowerCase();
        if (tipoMinuscolo.startsWith("application/x-www-form-urlencoded")) {
            aggiungiCoppie(r.campi, new String(r.corpo, "UTF-8"));
        } else if (tipoMinuscolo.startsWith("multipart/form-data")) {
            leggiMultipart(r, tipo);
        }
        return r;
    }

    // ---- corpo in parti (quello che manda il browser) -----------------------

    private static void leggiMultipart(Richiesta r, String tipo) throws IOException {
        String confine = null;
        for (String pezzo : tipo.split(";")) {
            String p = pezzo.trim();
            if (p.toLowerCase().startsWith("boundary=")) {
                confine = p.substring("boundary=".length()).trim();
                if (confine.startsWith("\"") && confine.endsWith("\"") && confine.length() > 1) {
                    confine = confine.substring(1, confine.length() - 1);
                }
            }
        }
        if (confine == null || confine.isEmpty()) {
            throw new RichiestaNonValida("multipart senza confine dichiarato");
        }

        byte[] separatore = ("--" + confine).getBytes("UTF-8");
        List<int[]> tratti = new ArrayList<int[]>();
        int da = 0;
        int trovato;
        int precedente = -1;
        while ((trovato = cerca(r.corpo, separatore, da)) >= 0) {
            if (precedente >= 0) {
                tratti.add(new int[]{precedente, trovato});
            }
            precedente = trovato + separatore.length;
            da = precedente;
        }

        for (int[] tratto : tratti) {
            leggiParte(r, tratto[0], tratto[1]);
        }
    }

    private static void leggiParte(Richiesta r, int inizio, int fine) throws IOException {
        // ogni parte e': CRLF intestazioni CRLF CRLF contenuto CRLF
        byte[] vuota = new byte[]{'\r', '\n', '\r', '\n'};
        int stacco = cerca(r.corpo, vuota, inizio);
        if (stacco < 0 || stacco >= fine) {
            return;
        }
        String testa = new String(r.corpo, inizio, stacco - inizio, "UTF-8");
        String nome = null;
        for (String riga : testa.split("\r\n")) {
            String minuscola = riga.toLowerCase();
            if (minuscola.contains("content-disposition") && minuscola.contains("name=")) {
                int apre = riga.indexOf("name=\"");
                if (apre >= 0) {
                    int chiude = riga.indexOf('"', apre + 6);
                    if (chiude > apre) {
                        nome = riga.substring(apre + 6, chiude);
                    }
                }
            }
        }
        if (nome == null) {
            return;
        }
        int daContenuto = stacco + vuota.length;
        int aContenuto = fine;
        // la parte finisce con il CRLF che precede il confine successivo
        while (aContenuto > daContenuto
                && (r.corpo[aContenuto - 1] == '\n' || r.corpo[aContenuto - 1] == '\r')) {
            aContenuto--;
        }
        if (aContenuto < daContenuto) {
            aContenuto = daContenuto;
        }
        r.campi.put(nome, new String(r.corpo, daContenuto, aContenuto - daContenuto, "UTF-8"));
    }

    // ---- utilita' -----------------------------------------------------------

    private static void aggiungiCoppie(Map<String, String> dove, String testo) {
        if (testo == null || testo.isEmpty()) {
            return;
        }
        for (String coppia : testo.split("&")) {
            if (coppia.isEmpty()) {
                continue;
            }
            int uguale = coppia.indexOf('=');
            String chiave = uguale >= 0 ? coppia.substring(0, uguale) : coppia;
            String valore = uguale >= 0 ? coppia.substring(uguale + 1) : "";
            try {
                dove.put(URLDecoder.decode(chiave, "UTF-8"), URLDecoder.decode(valore, "UTF-8"));
            } catch (Exception e) {
                dove.put(chiave, valore);
            }
        }
    }

    private static String riga(InputStream in) throws IOException {
        ByteArrayOutputStream sb = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.write(c);
            }
        }
        if (c == -1 && sb.size() == 0) {
            return null;
        }
        return new String(sb.toByteArray(), "UTF-8");
    }

    private static byte[] leggiEsatti(InputStream in, int quanti) throws IOException {
        byte[] dati = new byte[quanti];
        int letti = 0;
        while (letti < quanti) {
            int n = in.read(dati, letti, quanti - letti);
            if (n < 0) {
                throw new RichiestaNonValida("corpo interrotto dopo " + letti
                        + " byte su " + quanti + " dichiarati");
            }
            letti += n;
        }
        return dati;
    }

    /** Posizione della prima occorrenza di `ago` in `pagliaio` a partire da `da`. */
    static int cerca(byte[] pagliaio, byte[] ago, int da) {
        if (ago.length == 0 || pagliaio.length < ago.length) {
            return -1;
        }
        for (int i = Math.max(0, da); i <= pagliaio.length - ago.length; i++) {
            int k = 0;
            while (k < ago.length && pagliaio[i + k] == ago[k]) {
                k++;
            }
            if (k == ago.length) {
                return i;
            }
        }
        return -1;
    }
}
