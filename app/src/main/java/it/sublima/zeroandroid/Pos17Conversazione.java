package it.sublima.zeroandroid;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Il dialogo con il terminale POS: riassemblaggio del flusso, riscontri, attesa
 * dell'esito.
 *
 * ⚠️ Due cose qui dentro sono la ragione per cui questo file esiste, e sono
 * esattamente quelle che le implementazioni precedenti avevano sbagliato:
 *
 *  1. **Ogni frame ricevuto va riscontrato con un ACK.** Il manuale: "All the
 *     application-level packets have a physical confirmation message in response
 *     from the peer" e "the message transmission is repeated up to three".
 *     Senza riscontro il terminale ripete l'esito tre volte e poi chiude — che
 *     e' quello che si e' visto sul POS di un cliente.
 *
 *  2. **TCP e' un flusso, non un messaggio.** Un `read()` puo' contenere ACK ed
 *     esito insieme, oppure mezzo frame. Trattarlo come un pacchetto faceva
 *     buttare via l'esito: e' il difetto all'origine dell'incidente del
 *     30/07/2026, quando un POS ha autorizzato 4,99 EUR e la cassa ha
 *     registrato KO.
 *
 * Qui dentro **non si dorme mai**: una pausa lascia il socket non letto e rende
 * piu' probabile che due messaggi finiscano nella stessa lettura, cioe' proprio
 * il caso che fa perdere l'esito.
 */
public final class Pos17Conversazione {

    public static final int TIMEOUT_PAGAMENTO = 180000;

    private Pos17Conversazione() {
    }

    // ---- messaggi -------------------------------------------------------------

    public static final String TIPO_ACK = "ack";
    public static final String TIPO_NAK = "nak";
    public static final String TIPO_STATO = "stato";
    public static final String TIPO_FRAME = "frame";

    /** Un messaggio completo estratto dal flusso. */
    public static class Messaggio {
        public final String tipo;
        public final byte[] grezzo;
        /** Messaggio applicativo, senza STX/ETX/LRC. Solo per i frame. */
        public final String payload;
        public final boolean lrcOk;
        /** Solo per i messaggi di stato del display. */
        public final String testo;

        Messaggio(String tipo, byte[] grezzo, String payload, boolean lrcOk, String testo) {
            this.tipo = tipo;
            this.grezzo = grezzo;
            this.payload = payload;
            this.lrcOk = lrcOk;
            this.testo = testo;
        }
    }

    /**
     * Riassemblatore del flusso.
     *
     * Si alimenta con quello che torna dalla lettura — che NON e' un messaggio,
     * e' un pezzo di flusso — e restituisce i messaggi completi che ci trova,
     * quanti ne trova. Risolve tre casi che prima si perdevano: ACK ed esito
     * nella stessa lettura, messaggio di stato ed esito insieme, frame spezzato
     * fra due letture.
     */
    public static class Lettore {
        private byte[] buf = new byte[0];
        public int byteScartati;

        public void aggiungi(byte[] dati, int quanti) {
            byte[] nuovo = new byte[buf.length + quanti];
            System.arraycopy(buf, 0, nuovo, 0, buf.length);
            System.arraycopy(dati, 0, nuovo, buf.length, quanti);
            buf = nuovo;
            if (buf.length > Pos17.MAX_BUFFER) {
                // un frame che non si chiude mai non deve far crescere la memoria
                int taglio = buf.length - Pos17.MAX_BUFFER;
                consuma(taglio);
                byteScartati += taglio;
            }
        }

        /** I messaggi completi presenti nel buffer, che vengono consumati. */
        public List<Messaggio> messaggi() {
            List<Messaggio> trovati = new ArrayList<Messaggio>();
            while (buf.length > 0) {
                int b = buf[0] & 0xFF;

                if (b == 0x06 || b == 0x15) {
                    if (buf.length < 3) {
                        return trovati;                     // incompleto, si aspetta
                    }
                    if (buf[1] == Pos17.ETX) {
                        byte[] grezzo = primi(3);
                        consuma(3);
                        trovati.add(new Messaggio(b == 0x06 ? TIPO_ACK : TIPO_NAK,
                                grezzo, null, true, null));
                        continue;
                    }
                    scartaUno();
                    continue;
                }

                if (b == Pos17.SOH) {
                    if (buf.length < Pos17.LUNGH_STATO) {
                        return trovati;                     // incompleto, si aspetta
                    }
                    if (buf[Pos17.LUNGH_STATO - 1] != Pos17.EOT) {
                        scartaUno();
                        continue;
                    }
                    byte[] grezzo = primi(Pos17.LUNGH_STATO);
                    consuma(Pos17.LUNGH_STATO);
                    String testo = Pos17.testoDi(grezzo, 1, grezzo.length - 2).trim();
                    trovati.add(new Messaggio(TIPO_STATO, grezzo, null, true, testo));
                    continue;
                }

                if (b == Pos17.STX) {
                    int fine = -1;
                    for (int i = 1; i < buf.length; i++) {
                        if (buf[i] == Pos17.ETX) {
                            fine = i;
                            break;
                        }
                    }
                    if (fine < 0 || buf.length < fine + 2) {
                        return trovati;                     // incompleto, si aspetta
                    }
                    byte[] grezzo = primi(fine + 2);        // STX..ETX + LRC
                    consuma(fine + 2);
                    String payload = Pos17.testoDi(grezzo, 1, fine - 1);
                    trovati.add(new Messaggio(TIPO_FRAME, grezzo, payload,
                            Pos17.verificaLrc(grezzo), null));
                    continue;
                }

                scartaUno();
            }
            return trovati;
        }

        /** Byte non ancora consumati, utili al registro di una diagnosi. */
        public byte[] resto() {
            return buf.clone();
        }

        private byte[] primi(int quanti) {
            byte[] p = new byte[quanti];
            System.arraycopy(buf, 0, p, 0, quanti);
            return p;
        }

        private void consuma(int quanti) {
            byte[] nuovo = new byte[buf.length - quanti];
            System.arraycopy(buf, quanti, nuovo, 0, nuovo.length);
            buf = nuovo;
        }

        private void scartaUno() {
            consuma(1);
            byteScartati++;
        }
    }

    // ---- che cosa stiamo aspettando -------------------------------------------

    /**
     * Decide quale frame e' la risposta attesa e come leggerlo.
     *
     * Serve perche' il comando di stato ha un formato tutto suo, mentre il
     * resto del dialogo — riscontri, riassemblaggio, LRC, NAK — non cambia.
     */
    public interface Attesa {
        boolean accetta(String payload);

        Map<String, String> interpreta(String payload, String idPosAtteso);
    }

    /** L'esito di una transazione: il caso normale. */
    public static final Attesa ESITO = new Attesa() {
        public boolean accetta(String payload) {
            return Pos17.eEsito(payload);
        }

        public Map<String, String> interpreta(String payload, String idPosAtteso) {
            return Pos17.parseEsito(payload, idPosAtteso);
        }
    };

    /** La risposta al comando di stato terminale. */
    public static final Attesa STATO = new Attesa() {
        public boolean accetta(String payload) {
            return Pos17.eStato(payload);
        }

        public Map<String, String> interpreta(String payload, String idPosAtteso) {
            Map<String, String> d = new LinkedHashMap<String, String>();
            d.put("id_pos", payload.substring(0, 8).trim());
            d.put("codice_messaggio", "s");
            String stato = payload.length() > 30 ? payload.substring(30, 31) : "";
            d.put("stato", stato);
            d.put("descrizione_stato", descrizioneStato(stato));
            d.put("riassunto", "stato terminale: " + descrizioneStato(stato));
            return d;
        }
    };

    static String descrizioneStato(String codice) {
        if ("0".equals(codice)) return "non configurato";
        if ("1".equals(codice)) return "configurato, DLL non ancora fatta";
        if ("2".equals(codice)) return "operativo";
        if ("3".equals(codice)) return "non allineato";
        if ("4".equals(codice)) return "chiave KMPB corrotta";
        if ("5".equals(codice)) return "DLL in attesa";
        if ("6".equals(codice)) return "aggiornamento software in attesa";
        return "stato sconosciuto";
    }

    // ---- l'esito del dialogo ---------------------------------------------------

    /**
     * Come e' finito il dialogo.
     *
     * `tipo` vale: esito (risposta attesa, in `dati`), nak (il terminale ha
     * rifiutato il NOSTRO frame), chiusura, timeout, reset, errore.
     */
    public static class Risultato {
        public String tipo;
        public String motivo;
        public Map<String, String> dati;
        public double secondi;
        public String grezzoHex;

        Risultato(String tipo, String motivo, Map<String, String> dati,
                  double secondi, String grezzoHex) {
            this.tipo = tipo;
            this.motivo = motivo;
            this.dati = dati;
            this.secondi = secondi;
            this.grezzoHex = grezzoHex;
        }

        public boolean eEsito() {
            return "esito".equals(tipo) && dati != null;
        }
    }

    // ---- il giro completo ------------------------------------------------------

    public interface Registro {
        void scrivi(String messaggio);
    }

    private static final Registro SILENZIO = new Registro() {
        public void scrivi(String messaggio) {
        }
    };

    /**
     * Apre la connessione, manda il comando e aspetta la risposta.
     *
     * ⚠️ Il timeout e' **per singola lettura**, non complessivo: un terminale che
     * manda un messaggio di stato ogni tanto tiene viva la conversazione. E'
     * voluto — e' il cliente davanti alla cassa che decide quanto ci mette — ma
     * va saputo, perche' vale anche per la connessione: verso un indirizzo che
     * non risponde si aspetta tutto il timeout solo per connettersi.
     */
    public static Risultato esegui(String ip, int porta, byte[] frame, int timeout,
                                   String idPosAtteso, Attesa attesa, Registro log) {
        Registro l = (log == null) ? SILENZIO : log;
        Attesa a = (attesa == null) ? ESITO : attesa;
        Socket socket = new Socket();
        long inizio = System.currentTimeMillis();
        try {
            socket.connect(new InetSocketAddress(ip.trim(), porta), timeout);
            socket.setSoTimeout(timeout);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            out.write(frame);
            out.flush();
            inizio = System.currentTimeMillis();
            l.scrivi("[>] inviati " + frame.length + " byte");

            return attendi(in, out, inizio, idPosAtteso, a, l);
        } catch (java.net.SocketTimeoutException e) {
            return new Risultato("timeout", "Timeout: il POS non risponde",
                    null, secondi(inizio), "");
        } catch (java.net.ConnectException e) {
            return new Risultato("errore", "Il terminale " + ip + ":" + porta
                    + " rifiuta la connessione: spento o indirizzo sbagliato",
                    null, secondi(inizio), "");
        } catch (Exception e) {
            return new Risultato("errore", "Errore parlando col terminale: "
                    + e.getMessage(), null, secondi(inizio), "");
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                // chiudere un socket gia' chiuso non e' un problema
            }
        }
    }

    public static Risultato attendi(InputStream in, OutputStream out, long inizio,
                             String idPosAtteso, Attesa attesa, Registro registro) {
        // chiamabile anche senza registro: la diagnosi e' un di piu', non un obbligo
        final Registro log = (registro == null) ? SILENZIO : registro;
        Lettore lettore = new Lettore();
        StringBuilder grezzo = new StringBuilder();
        byte[] buf = new byte[65536];

        while (true) {
            int quanti;
            try {
                quanti = in.read(buf);
            } catch (java.net.SocketTimeoutException e) {
                log.scrivi("[!] nessuna risposta entro il timeout");
                return new Risultato("timeout", "Timeout: il POS non risponde",
                        null, secondi(inizio), grezzo.toString());
            } catch (java.net.SocketException e) {
                // ⚠️ Il terminale accetta la connessione e poi la AZZERA. Non e'
                // la rete: e' lui che rifiuta di parlare, tipicamente perche' e'
                // fermo su un messaggio da chiudere o ha gia' una sessione
                // aperta con un'altra cassa.
                log.scrivi("[!] connessione azzerata dal terminale");
                return new Risultato("reset",
                        "Il terminale ha azzerato la connessione subito dopo averla"
                        + " accettata: di solito e' fermo su un messaggio di errore"
                        + " da chiudere, oppure ha gia' una sessione aperta con"
                        + " un'altra cassa. GUARDA IL DISPLAY del terminale.",
                        null, secondi(inizio), grezzo.toString());
            } catch (Exception e) {
                log.scrivi("[!] errore in ricezione: " + e.getMessage());
                return new Risultato("errore", "Errore ricezione: " + e.getMessage(),
                        null, secondi(inizio), grezzo.toString());
            }

            if (quanti <= 0) {
                // ⚠️ Zero byte = ha chiuso LUI, e non e' un timeout. Il tempo
                // trascorso separa le ipotesi: entro circa un secondo il frame e'
                // stato scartato senza aprire nemmeno una transazione (checksum,
                // struttura o identificativi rifiutati); dopo secondi, sessione
                // non concessa o transazione abbandonata.
                log.scrivi("[!] connessione chiusa dal terminale dopo "
                        + secondi(inizio) + "s, zero byte");
                return new Risultato("chiusura",
                        "Il terminale ha chiuso la connessione senza mandare l'esito",
                        null, secondi(inizio), grezzo.toString());
            }

            grezzo.append(esadecimale(buf, quanti));
            log.scrivi("[<] " + quanti + " byte dopo " + secondi(inizio) + "s");
            lettore.aggiungi(buf, quanti);

            for (Messaggio m : lettore.messaggi()) {
                if (TIPO_ACK.equals(m.tipo)) {
                    log.scrivi("[<] ACK: comando preso in carico, attendo l'esito");
                    continue;
                }
                if (TIPO_NAK.equals(m.tipo)) {
                    log.scrivi("[<] NAK: il terminale ha RIFIUTATO il nostro frame");
                    return new Risultato("nak",
                            "Il terminale ha rifiutato il comando (NAK): controlla"
                            + " ID POS, ID cassa e che sul terminale sia impostato"
                            + " il protocollo 17",
                            null, secondi(inizio), grezzo.toString());
                }
                if (TIPO_STATO.equals(m.tipo)) {
                    // i messaggi di stato non si riscontrano
                    log.scrivi("[<] display: " + m.testo);
                    continue;
                }
                if (!m.lrcOk) {
                    log.scrivi("[<] frame con LRC sbagliato, chiedo la ripetizione");
                    riscontra(out, Pos17.NAK, log);
                    continue;
                }

                riscontra(out, Pos17.ACK, log);

                if (!attesa.accetta(m.payload)) {
                    // uno scontrino, o un frame che non stiamo aspettando:
                    // riscontrato — cosi' il terminale non lo ripete — ma non e'
                    // la nostra risposta
                    log.scrivi("[<] frame riscontrato ma non e' la risposta attesa,"
                            + " continuo ad attendere");
                    continue;
                }

                Map<String, String> dati = attesa.interpreta(m.payload, idPosAtteso);
                if ("false".equals(dati.get("id_pos_allineato"))) {
                    log.scrivi("[!] il terminale si presenta come " + dati.get("id_pos")
                            + " ma in configurazione c'e' " + dati.get("id_pos_configurato")
                            + ": risposta accettata comunque (si riconosce dalla"
                            + " struttura). Per pulizia allinea il campo Modello.");
                }
                log.scrivi("[<] " + dati.get("riassunto"));
                return new Risultato("esito", null, dati, secondi(inizio),
                        grezzo.toString());
            }
        }
    }

    /**
     * Manda il riscontro.
     *
     * Se il terminale ha gia' chiuso, il riscontro non parte: va detto, ma non
     * deve far perdere un esito che abbiamo gia' in mano.
     */
    private static void riscontra(OutputStream out, byte[] risposta, Registro log) {
        try {
            out.write(risposta);
            out.flush();
            log.scrivi("[>] " + (risposta == Pos17.ACK ? "ACK" : "NAK"));
        } catch (Exception e) {
            log.scrivi("[!] riscontro non inviato: " + e.getMessage());
        }
    }

    private static double secondi(long inizio) {
        return Math.round((System.currentTimeMillis() - inizio) / 100.0) / 10.0;
    }

    private static String esadecimale(byte[] dati, int quanti) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < quanti; i++) {
            sb.append(String.format("%02x", dati[i] & 0xFF));
        }
        return sb.toString();
    }
}
