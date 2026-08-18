package it.sublima.zeroandroid;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Le chiamate di ritorno verso Sublima.
 *
 * Sono due, e servono a cose diverse:
 *
 *  - dopo uno scontrino fiscale, il numero e la chiusura che il registratore ha
 *    assegnato. Senza, la carta esce ma il documento resta senza numero;
 *  - dopo una comanda ricevuta in ascolto, l'esito della stampa. E' quello che
 *    il browser aspetta di leggere, ed e' anche cio' che fa cancellare lo ZIP
 *    dal server.
 *
 * L'indirizzo si ricava da Origin, con ripiego su Referer: la richiesta di
 * stampa arriva proprio da Sublima, quindi ce l'ha dentro. Cosi' l'agente
 * funziona appena installato, senza che nessuno digiti un URL, e segue il
 * profilo se cambia indirizzo.
 *
 * ⚠️ Quando la stampa non arriva dal browser — `zero_direct`, oppure l'ascolto
 * comande — quegli header non esistono. Per anni il numero dello scontrino in
 * quel caso non era restituibile. Ora c'e' il terzo ripiego: l'indirizzo che
 * l'agente ha configurato per mettersi in ascolto. L'ordine e'
 * **Origin -> Referer -> configurato**, cosi' chi chiama esplicitamente
 * continua a comandare su chi si e' configurato una volta.
 */
public final class Sublima {

    public static final String PERCORSO_CHIUSURA =
            "/pg/VD/ac_StoricoScontrino/set_chiusura_e_numero_";

    /** Dove si dice a Sublima com'e' andata una comanda ricevuta in ascolto. */
    public static final String PERCORSO_ACK = "/pg/api/zero/ack_print";

    public static final int ATTESA = 5000;

    private Sublima() {
    }

    /**
     * L'indirizzo di Sublima ricavato dalla richiesta, o null se non si puo'
     * sapere.
     */
    public static String indirizzoDa(Richiesta richiesta) {
        String origine = richiesta.intestazione("origin");
        if (valido(origine)) {
            return ripulisci(origine);
        }
        String provenienza = richiesta.intestazione("referer");
        if (valido(provenienza)) {
            try {
                URL u = new URL(provenienza);
                String porta = (u.getPort() > 0) ? ":" + u.getPort() : "";
                return u.getProtocol() + "://" + u.getHost() + porta;
            } catch (Exception e) {
                // un Referer malformato non toglie il ripiego configurato
            }
        }
        String configurato = Conf.urlProfilo();
        return configurato.isEmpty() ? null : configurato;
    }

    /**
     * Dice a Sublima com'e' andata una comanda ricevuta in ascolto.
     *
     * Va mandata **sempre**, anche quando la stampa e' stata saltata perche' il
     * lavoro era gia' stato fatto: senza, lo ZIP resta sul server e il browser
     * aspetta un esito che non arriva mai.
     *
     * L'esito viaggia come stringa dentro un campo di form — non e' eleganza,
     * e' il contratto: il server fa `json.loads` su quel campo.
     *
     * Un fallimento qui non si ripete e non ferma niente: la stampa e' gia'
     * uscita, e ritentare rischierebbe solo di far ristampare.
     */
    public static void inviaAck(String indirizzo, String filescontrino,
                                String esitoJson, String licenza) {
        if (!valido(indirizzo) || !valido(filescontrino)) {
            return;
        }
        String risultato = "OK";
        try {
            risultato = JsonLettore.testo(JsonLettore.oggetto(esitoJson), "Result", "OK");
        } catch (Exception e) {
            risultato = "KO";
        }
        HttpURLConnection c = null;
        try {
            StringBuilder corpo = new StringBuilder();
            aggiungi(corpo, "filescontrino", filescontrino);
            aggiungi(corpo, "status", risultato);
            aggiungi(corpo, "zero_licenza", licenza == null ? "" : licenza);
            aggiungi(corpo, "print_result", esitoJson == null ? "" : esitoJson);
            byte[] dati = corpo.toString().getBytes("UTF-8");

            c = (HttpURLConnection) new URL(ripulisci(indirizzo) + PERCORSO_ACK)
                    .openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(ATTESA);
            c.setReadTimeout(ATTESA);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("User-Agent", Versione.NOME + "/" + Versione.NUMERO);
            OutputStream out = c.getOutputStream();
            out.write(dati);
            out.flush();
            out.close();

            int codice = c.getResponseCode();
            if (codice < 200 || codice >= 300) {
                ServerHttp.registra("Sublima ha risposto " + codice
                        + " all'esito di " + filescontrino);
            }
        } catch (Exception e) {
            ServerHttp.registra("esito di " + filescontrino
                    + " non consegnato: " + e.getMessage());
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /**
     * Manda numero e chiusura. Restituisce null se e' andata, altrimenti il
     * motivo del fallimento, che va riportato nell'esito della stampa.
     */
    public static String inviaNumeroEChiusura(String indirizzo, String numero,
                                              String chiusura, String numeroReso,
                                              String idTs, String codiceUnivoco) {
        if (!valido(indirizzo)) {
            return "indirizzo di Sublima non ricavabile dalla richiesta";
        }
        if (!valido(numero) || !valido(chiusura) || !valido(idTs)) {
            return "numero, chiusura o riferimento allo scontrino mancanti";
        }
        HttpURLConnection c = null;
        try {
            StringBuilder corpo = new StringBuilder();
            aggiungi(corpo, "numero", numero);
            aggiungi(corpo, "chiusura", chiusura);
            aggiungi(corpo, "numero_reso", numeroReso == null ? "0" : numeroReso);
            aggiungi(corpo, "id_ts", idTs);
            aggiungi(corpo, "codice_univoco", codiceUnivoco == null ? "" : codiceUnivoco);
            byte[] dati = corpo.toString().getBytes("UTF-8");

            c = (HttpURLConnection) new URL(indirizzo + PERCORSO_CHIUSURA).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(ATTESA);
            c.setReadTimeout(ATTESA);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            // Sublima riconosce da qui che a scrivere e' un tramite di stampa
            c.setRequestProperty("User-Agent", "joint");
            OutputStream out = c.getOutputStream();
            out.write(dati);
            out.flush();
            out.close();

            int codice = c.getResponseCode();
            if (codice >= 200 && codice < 300) {
                ServerHttp.registra("numero " + numero + " e chiusura " + chiusura
                        + " registrati su Sublima");
                return null;
            }
            return "Sublima ha risposto " + codice;
        } catch (Exception e) {
            return "Sublima non raggiungibile: " + e.getMessage();
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static void aggiungi(StringBuilder sb, String chiave, String valore)
            throws Exception {
        if (sb.length() > 0) {
            sb.append('&');
        }
        sb.append(URLEncoder.encode(chiave, "UTF-8")).append('=')
          .append(URLEncoder.encode(valore == null ? "" : valore, "UTF-8"));
    }

    private static boolean valido(String s) {
        return s != null && !s.trim().isEmpty() && !"null".equalsIgnoreCase(s.trim());
    }

    private static String ripulisci(String indirizzo) {
        String s = indirizzo.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
