package it.sublima.zeroandroid;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Il dialogo con i registratori telematici Micrelec.
 *
 * A differenza di una ESC/POS, qui non si scrive e basta: si apre con una
 * richiesta di stato, si manda una riga per volta e si legge la risposta di
 * ognuna, e alla fine si chiede al registratore che numero ha dato allo
 * scontrino. Il codice di errore e' il primo campo della risposta, letto in
 * ESADECIMALE: leggerlo in decimale porterebbe a scambiare un errore per un
 * altro.
 *
 * Replica quello che fa `socket_tcp_manager` nello Zero. Vedi il paragrafo 3 di
 * PROTOCOLLO.md e reference_protocollo_micrelec (errori a pag. 168-170 del PDF).
 */
public final class Micrelec {

    /** Quante volte richiedere lo stato prima di rinunciare. */
    public static final int TENTATIVI_STATO = 5;
    public static final int PAUSA_FRA_TENTATIVI = 1000;

    /**
     * Quanto attendere la risposta a una riga.
     *
     * Generosa apposta: la chiusura fiscale Z stampa il riepilogo della giornata
     * e trasmette i dati, e su quel comando il registratore puo' restare zitto
     * per un minuto abbondante. Lo Zero non ha alcun timeout e aspetta per
     * sempre; qui si aspetta molto ma non all'infinito, cosi' una cassa che non
     * risponde piu' libera comunque il thread.
     *
     * Il browser intanto stacca dopo dieci secondi e mostra un errore di rete:
     * la chiusura pero' va avanti e si completa, ed e' quello che conta.
     */
    public static final int ATTESA = 150000;

    private Micrelec() {
    }

    /** Come e' andata, con i dati fiscali se sono stati richiesti. */
    public static class Esito {
        public boolean riuscito;
        public String messaggio;
        public String numero;
        public String reso;
        public String chiusura;
        /** Qualcosa da segnalare pur essendo andata a buon fine, o null. */
        public String avvertimento;

        Esito(boolean riuscito, String messaggio) {
            this.riuscito = riuscito;
            this.messaggio = messaggio;
        }
    }

    /**
     * Manda il tracciato al registratore.
     *
     * @param chiediDatiFiscali se true, alla fine chiede numero e chiusura: si
     *                          fa solo per gli scontrini, e solo se la barriera
     *                          casse lo prevede.
     */
    public static Esito stampa(String ip, int porta, byte[] tracciato,
                               boolean chiediDatiFiscali) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip.trim(), porta),
                    Stampante.ATTESA_CONNESSIONE);
            socket.setSoTimeout(ATTESA);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String statoNonBuono = attendiCassaPronta(in, out);
            if (statoNonBuono != null) {
                return new Esito(false, statoNonBuono);
            }

            // ⚠ Le righe si mandano tutte fino in fondo, qualunque cosa risponda
            // il registratore: e' quello che fa lo Zero, e cambiarlo romperebbe i
            // flussi in cui un codice diverso da zero e' normale — il display
            // cliente risponde cosi' di continuo. L'ultimo codice visto viene
            // riportato come avviso, per non buttare via l'informazione.
            int ultimoErrore = 0;
            String rigaDellErrore = null;

            for (String riga : righe(tracciato)) {
                if (riga.isEmpty()) {
                    continue;
                }
                String risposta = scambia(in, out, riga);
                int errore = codice(risposta);

                if (errore == 54) {
                    // la cassa e' dentro un menu: la si riporta fuori a colpi di
                    // ESC, poi si ripete la riga
                    for (int k = 0; k < 4; k++) {
                        scambia(in, out, ")/118/0/");
                    }
                    risposta = scambia(in, out, riga);
                    errore = codice(risposta);
                } else if (errore == 44) {
                    // fine carta segnalata a meta': si ritenta la stessa riga
                    risposta = scambia(in, out, riga);
                    errore = codice(risposta);
                }

                if (errore > 0) {
                    ultimoErrore = errore;
                    rigaDellErrore = riga;
                }
            }

            Esito esito = new Esito(true, "STAMPATO CORRETTAMENTE .. IP: " + ip);
            if (ultimoErrore > 0) {
                esito.avvertimento = "il registratore ha risposto '"
                        + descrizione(ultimoErrore) + "' sulla riga: " + rigaDellErrore;
                ServerHttp.registra("Micrelec " + ip + ": " + esito.avvertimento);
            }
            if (chiediDatiFiscali) {
                leggiDatiFiscali(in, out, esito);
            }
            return esito;

        } catch (java.net.SocketTimeoutException e) {
            return new Esito(false, "Il registratore " + ip + " non ha risposto in tempo");
        } catch (java.net.ConnectException e) {
            return new Esito(false, "Il registratore " + ip + ":" + porta
                    + " rifiuta la connessione: spento o indirizzo sbagliato");
        } catch (Exception e) {
            return new Esito(false, "Errore parlando col registratore " + ip + ": "
                    + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                // niente da fare
            }
        }
    }

    /**
     * Chiede lo stato finche' la cassa non e' pronta.
     * Restituisce null se e' pronta, altrimenti il motivo per cui non lo e'.
     *
     * Lo Zero insiste per un minuto intero; qui si insiste molto meno, perche'
     * il browser stacca dopo dieci secondi e continuare a provare oltre serve
     * solo a far comparire un errore di rete al posto del motivo vero.
     */
    private static String attendiCassaPronta(InputStream in, OutputStream out)
            throws Exception {
        String ultimo = "nessuna risposta";
        for (int tentativo = 0; tentativo < TENTATIVI_STATO; tentativo++) {
            String risposta = scambia(in, out, "?");
            int errore = codice(risposta);
            if (errore == 0) {
                return null;
            }
            ultimo = descrizione(errore);
            Thread.sleep(PAUSA_FRA_TENTATIVI);
        }
        return "La cassa non e' pronta: " + ultimo;
    }

    /** Dopo lo scontrino: numero, reso e chiusura, che Sublima deve registrare. */
    private static void leggiDatiFiscali(InputStream in, OutputStream out, Esito esito) {
        try {
            String rispostaNumero = scambia(in, out, "0/");
            ServerHttp.registra("Micrelec, risposta a 0/ : " + rispostaNumero.trim());
            String[] campi = rispostaNumero.split("/");
            if (campi.length > 10) {
                esito.numero = campi[9];
                esito.reso = campi[10];
            }
            String rispostaChiusura = scambia(in, out, "i/");
            ServerHttp.registra("Micrelec, risposta a i/ : " + rispostaChiusura.trim());
            String[] campiChiusura = rispostaChiusura.split("/");
            if (campiChiusura.length > 5) {
                // il registratore riporta l'ultima chiusura fatta: quella in
                // corso e' la successiva
                esito.chiusura = String.valueOf(intero(campiChiusura[5]) + 1);
            }
        } catch (Exception e) {
            // la carta e' gia' uscita: non si annulla la stampa per questo, ma
            // chi ha chiamato deve sapere che il numero non e' stato letto
            esito.messaggio = esito.messaggio
                    + " - ATTENZIONE: numero e chiusura non leggibili (" + e.getMessage() + ")";
        }
    }

    private static String scambia(InputStream in, OutputStream out, String comando)
            throws Exception {
        out.write(comando.getBytes("ISO-8859-1"));
        out.flush();
        byte[] buf = new byte[1024];
        int n = in.read(buf);
        if (n <= 0) {
            throw new Exception("il registratore ha chiuso la conversazione");
        }
        return new String(buf, 0, n, "ISO-8859-1");
    }

    /** Il primo campo della risposta e' il codice di errore, in ESADECIMALE. */
    public static int codice(String risposta) {
        if (risposta == null || risposta.isEmpty()) {
            return -1;
        }
        String primo = risposta.split("/")[0].trim();
        try {
            return Integer.parseInt(primo, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Le righe del tracciato, senza i fine riga. */
    public static List<String> righe(byte[] tracciato) throws Exception {
        List<String> risultato = new ArrayList<String>();
        for (String riga : new String(tracciato, "ISO-8859-1").split("\n")) {
            risultato.add(riga.replace("\r", "").trim());
        }
        return risultato;
    }

    /** Gli errori che il registratore sa segnalare, in parole. */
    public static String descrizione(int errore) {
        switch (errore) {
            case 0:  return "pronta";
            case 5:  return "campi mancanti nel comando (impostazioni del registratore?)";
            case 26: return "cassa occupata: attendere qualche secondo e riprovare";
            case 44: return "fine carta";
            case 45: return "taglierina";
            case 46: return "cassa disconnessa";
            case 51:
            case 73: return "sportello aperto";
            case 54: return "la cassa e' sul menu'";
            case 94: return "scontrino aperto da tastiera";
            case -1: return "risposta non interpretabile";
            default: return "errore " + errore;
        }
    }

    private static long intero(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
