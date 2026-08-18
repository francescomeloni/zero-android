package it.sublima.zeroandroid;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Esegue un lavoro di stampa: dal messaggio di Sublima alla carta.
 *
 * Passi: si legge `data`, si apre lo ZIP, si legge il manifest, e per ogni file
 * che il manifest elenca si apre un socket verso la sua stampante. L'esito
 * torna nella forma che il client di Sublima sa leggere, cioe' Result piu'
 * Details con i tre elenchi.
 *
 * ⚠️ Questo agente **non interpreta il metalinguaggio a tag**: accetta solo
 * tracciati gia' tradotti in byte, che e' quello che dichiara in /test_zero con
 * `escpos_bytes`. E' una scelta: l'interprete vive in un posto solo, dentro
 * Sublima, e non viene riscritto qui per non farlo divergere. Se arriva un
 * tracciato a tag lo si dice chiaramente invece di mandarlo alla stampante, che
 * stamperebbe le righe di tag come fossero testo.
 */
public final class Stampa implements ServerHttp.Stampe {

    /** Lavori gia' eseguiti, per non ripeterli se arrivano due volte. */
    private final Set<String> gia = new LinkedHashSet<String>();
    private static final int QUANTI_RICORDARNE = 200;

    /**
     * Porta usata al posto di quella vera, per provare contro stampanti finte.
     * A zero (l'esercizio) valgono le porte del protocollo: 9100 fissa per
     * ESC/POS, `ip_port` del manifest per i registratori.
     */
    private final int portaForzata;

    public Stampa() {
        this(0);
    }

    public Stampa(int portaForzata) {
        this.portaForzata = portaForzata;
    }

    private int portaEscpos() {
        return portaForzata > 0 ? portaForzata : Stampante.PORTA_ESCPOS;
    }

    private int portaRegistratore(int dichiarata) {
        return portaForzata > 0 ? portaForzata : dichiarata;
    }

    public String esegui(Richiesta richiesta) {
        String data = richiesta.campo("data");
        if (data == null) {
            return esito("KO", "Manca il campo data", new ArrayList<Json>(),
                    new ArrayList<Json>(), new ArrayList<Json>());
        }

        Map<String, Object> messaggio;
        try {
            messaggio = JsonLettore.oggetto(data);
        } catch (JsonLettore.JsonNonValido e) {
            return esito("KO", "Messaggio illeggibile: " + e.getMessage(),
                    new ArrayList<Json>(), new ArrayList<Json>(), new ArrayList<Json>());
        }

        String tipo = JsonLettore.testo(messaggio, "tipo", "scontrino");
        String nomeLavoro = JsonLettore.testo(messaggio, "filescontrino", "");
        String corpo = JsonLettore.testo(messaggio, "corpo_scontrino", "");

        if ("POS".equalsIgnoreCase(tipo)) {
            return Pagamento.esegui(messaggio, richiesta);
        }

        // Stessa clemenza dello Zero: un lavoro vuoto non e' un errore.
        if (corpo.isEmpty() || nomeLavoro.isEmpty()) {
            return esito("OK", "Niente da stampare (corpo vuoto)",
                    new ArrayList<Json>(), new ArrayList<Json>(), new ArrayList<Json>());
        }

        synchronized (gia) {
            if (gia.contains(nomeLavoro)) {
                ServerHttp.registra("gia' fatto, non lo ripeto: " + nomeLavoro);
                return new Json()
                        .testo("Result", "OK")
                        .testo("Message", "Gia' processato (dedup)")
                        .vero("dedup", true)
                        .toString();
            }
        }

        byte[] zip;
        try {
            zip = Base64.decodifica(corpo);
        } catch (Base64.Base64NonValido e) {
            return esito("KO", "Contenuto illeggibile: " + e.getMessage(),
                    new ArrayList<Json>(), new ArrayList<Json>(), new ArrayList<Json>());
        }

        Pacchetto pacchetto;
        try {
            pacchetto = Pacchetto.apri(zip);
        } catch (Pacchetto.PacchettoNonValido e) {
            return esito("KO", "Pacchetto non valido: " + e.getMessage(),
                    new ArrayList<Json>(), new ArrayList<Json>(), new ArrayList<Json>());
        }

        List<Json> riusciti = new ArrayList<Json>();
        List<Json> falliti = new ArrayList<Json>();
        List<Json> avvisi = new ArrayList<Json>();

        // Il lavoro si segna PRIMA di stamparlo, non dopo.
        //
        // Lo stesso lavoro puo' arrivare due volte per due strade diverse — la
        // spinta di Sublima e la stampa del browser — e una comanda lunga lascia
        // una finestra di secondi in cui la seconda copia troverebbe il registro
        // ancora vuoto e stamperebbe di nuovo. Meglio una comanda non ristampata
        // dopo un guasto che una comanda doppia in cucina; e' anche quello che
        // fa lo Zero Python.
        ricorda(nomeLavoro);

        for (String nomefile : pacchetto.daStampare()) {
            eseguiUno(nomefile, pacchetto.stampantePer(nomefile),
                    pacchetto.contenuto(nomefile), richiesta,
                    riusciti, falliti, avvisi);
        }

        int totale = riusciti.size() + falliti.size() + avvisi.size();

        if (falliti.isEmpty() && avvisi.isEmpty()) {
            return esito("OK", "Tutte le stampanti hanno completato correttamente ("
                    + riusciti.size() + "/" + totale + ")", riusciti, falliti, avvisi);
        }
        if (riusciti.isEmpty() && avvisi.isEmpty()) {
            return esito("KO", "ERRORE: Nessuna stampante ha completato correttamente (0/"
                    + totale + ")", riusciti, falliti, avvisi);
        }
        return esito("PARTIAL", "Completate " + riusciti.size() + "/" + totale
                + " stampanti - " + falliti.size() + " errori - " + avvisi.size()
                + " warnings", riusciti, falliti, avvisi);
    }

    /** Manda un singolo file alla sua stampante, secondo il driver che il manifest indica. */
    private void eseguiUno(String nomefile, Map<String, Object> stampante, byte[] contenuto,
                           Richiesta richiesta, List<Json> riusciti,
                           List<Json> falliti, List<Json> avvisi) {
        String denominazione = valore(stampante, "denominazione", "");
        String ip = valore(stampante, "ip", "");
        String tipoMf = valore(stampante, "tipo_mf", "");
        String connessione = valore(stampante, "tipo_connessione", "");

        if (stampante == null) {
            falliti.add(voce(nomefile, denominazione, ip, tipoMf, "KO",
                    "Il manifest elenca il file ma non dice a quale stampante mandarlo"));
            return;
        }
        if (contenuto == null) {
            falliti.add(voce(nomefile, denominazione, ip, tipoMf, "KO",
                    "Il manifest elenca " + nomefile + " ma nell'archivio non c'e'"));
            return;
        }
        if (!"ethernet".equalsIgnoreCase(connessione)) {
            falliti.add(voce(nomefile, denominazione, ip, tipoMf, "KO",
                    "Collegamento '" + connessione + "' non gestito: questo agente parla"
                            + " solo con stampanti di rete"));
            return;
        }

        if ("ESCPOS".equalsIgnoreCase(tipoMf)) {
            escpos(nomefile, stampante, contenuto, riusciti, falliti);
        } else if ("M".equalsIgnoreCase(tipoMf) || nomefile.endsWith(".dat")) {
            micrelec(nomefile, stampante, contenuto, richiesta,
                    riusciti, falliti, avvisi);
        } else if ("E".equalsIgnoreCase(tipoMf)) {
            xml(nomefile, stampante, contenuto, false, riusciti, falliti);
        } else if ("CUXML".equalsIgnoreCase(tipoMf)) {
            xml(nomefile, stampante, contenuto, true, riusciti, falliti);
        } else {
            falliti.add(voce(nomefile, denominazione, ip, tipoMf, "KO",
                    "Driver '" + tipoMf + "' non gestito da questo agente"));
        }
    }

    private void escpos(String nomefile, Map<String, Object> stampante, byte[] contenuto,
                        List<Json> riusciti, List<Json> falliti) {
        String denominazione = valore(stampante, "denominazione", "");
        String ip = valore(stampante, "ip", "");
        String tipoMf = valore(stampante, "tipo_mf", "");

        if (!"bytes".equalsIgnoreCase(valore(stampante, "formato_dati", "tag"))) {
            falliti.add(voce(nomefile, denominazione, ip, tipoMf, "KO",
                    "Tracciato in formato tradizionale: questo agente accetta solo byte"
                            + " gia' tradotti. Metti il punto cassa su 'Byte nativi'"));
            return;
        }
        try {
            Turno.attendi(ip);
        } catch (Turno.TroppaCoda e) {
            falliti.add(voce(nomefile, denominazione, ip, tipoMf, "KO", e.getMessage()));
            return;
        }
        try {
            Stampante.invia(ip, portaEscpos(), contenuto);
            ServerHttp.registra("stampato " + nomefile + " (" + contenuto.length
                    + " byte) su " + ip + " " + denominazione);
            riusciti.add(voce(nomefile, denominazione, ip, tipoMf, "OK",
                    "STAMPATO CORRETTAMENTE .. IP: " + ip + " " + denominazione));
        } catch (Stampante.NonRaggiungibile e) {
            ServerHttp.registra("non stampato " + nomefile + ": " + e.getMessage());
            falliti.add(voce(nomefile, denominazione, ip, tipoMf, "KO", e.getMessage()));
        } finally {
            Turno.libera(ip);
        }
    }

    /**
     * Registratore Micrelec. Se e' uno scontrino e la barriera casse lo prevede,
     * dopo la stampa si leggono numero e chiusura e si restituiscono a Sublima.
     *
     * ⚠️ Se la stampa riesce ma il numero non arriva a Sublima, la carta e' gia'
     * uscita: non e' un fallimento, ma non e' nemmeno un successo pulito. Finisce
     * fra gli avvisi, cosi' il cassiere legge che il documento e' stato stampato
     * ma non registrato, invece di non saperlo.
     */
    private void micrelec(String nomefile, Map<String, Object> stampante, byte[] contenuto,
                          Richiesta richiesta, List<Json> riusciti,
                          List<Json> falliti, List<Json> avvisi) {
        String denominazione = valore(stampante, "denominazione", "");
        String ip = valore(stampante, "ip", "");
        String tipoMf = valore(stampante, "tipo_mf", "M");
        int porta = portaRegistratore(numero(valore(stampante, "ip_port", "9101"), 9101));
        // ⚠ Il tipo si legge dal MANIFEST, per ogni stampante, non dal messaggio
        // esterno: per il display Sublima non mette `tipo` nel messaggio, e un
        // valore di riserva "scontrino" porterebbe a interrogare il registratore
        // sui dati fiscali per una riga di display, leggendo il numero dello
        // scontrino precedente. Lo Zero fa lo stesso: `tipo = v["tipo"]`.
        String tipo = valore(stampante, "tipo", "");
        boolean chiedeDati = "scontrino".equalsIgnoreCase(tipo)
                && vero(stampante, "zero_sync_chiusura_e_numero");

        try {
            Turno.attendi(ip);
        } catch (Turno.TroppaCoda e) {
            falliti.add(voce(nomefile, denominazione, ip, tipoMf, "KO", e.getMessage()));
            return;
        }
        Micrelec.Esito esito;
        try {
            esito = Micrelec.stampa(ip, porta, contenuto, chiedeDati);
        } finally {
            Turno.libera(ip);
        }
        if (!esito.riuscito) {
            ServerHttp.registra("non stampato " + nomefile + ": " + esito.messaggio);
            falliti.add(voce(nomefile, denominazione, ip, tipoMf, "KO", esito.messaggio));
            return;
        }
        ServerHttp.registra("stampato " + nomefile + " sul registratore " + ip);

        if (!chiedeDati) {
            if (esito.avvertimento != null) {
                avvisi.add(voce(nomefile, denominazione, ip, tipoMf, "ATTENZIONE",
                        esito.messaggio + " - " + esito.avvertimento));
            } else {
                riusciti.add(voce(nomefile, denominazione, ip, tipoMf, "OK",
                        esito.messaggio));
            }
            return;
        }

        // Come lo Zero: se uno dei tre dati non c'e', quel flusso non prevede la
        // registrazione e si tace. Segnalarlo come guaio allarmerebbe il cassiere
        // per una stampa che e' andata esattamente come doveva.
        String idTs = valore(stampante, "id_ts", "");
        if (idTs.isEmpty() || esito.numero == null || esito.chiusura == null) {
            ServerHttp.registra("numero non registrato su Sublima: questo flusso non lo"
                    + " prevede (numero=" + esito.numero + " chiusura=" + esito.chiusura
                    + " id_ts=" + idTs + ")");
            riusciti.add(voce(nomefile, denominazione, ip, tipoMf, "OK", esito.messaggio));
            return;
        }

        String problema = Sublima.inviaNumeroEChiusura(
                Sublima.indirizzoDa(richiesta), esito.numero, esito.chiusura, esito.reso,
                idTs, valore(stampante, "codice_univoco", ""));
        if (problema == null) {
            riusciti.add(voce(nomefile, denominazione, ip, tipoMf, "OK",
                    esito.messaggio + " - numero " + esito.numero
                            + ", chiusura " + esito.chiusura));
        } else {
            ServerHttp.registra("scontrino stampato ma numero non registrato: " + problema);
            avvisi.add(voce(nomefile, denominazione, ip, tipoMf, "ATTENZIONE",
                    "SCONTRINO STAMPATO ma numero " + esito.numero
                            + " NON registrato su Sublima: " + problema));
        }
    }

    private void xml(String nomefile, Map<String, Object> stampante, byte[] contenuto,
                     boolean custom, List<Json> riusciti, List<Json> falliti) {
        String denominazione = valore(stampante, "denominazione", "");
        String ip = valore(stampante, "ip", "");
        String tipoMf = valore(stampante, "tipo_mf", "");
        try {
            if (custom) {
                StampanteXml.custom(ip, contenuto, valore(stampante, "matricola", ""));
            } else {
                StampanteXml.epson(ip, contenuto);
            }
            ServerHttp.registra("stampato " + nomefile + " su " + ip + " " + denominazione);
            riusciti.add(voce(nomefile, denominazione, ip, tipoMf, "OK",
                    "STAMPATO CORRETTAMENTE .. IP: " + ip + " " + denominazione));
        } catch (StampanteXml.NonRiuscita e) {
            ServerHttp.registra("non stampato " + nomefile + ": " + e.getMessage());
            falliti.add(voce(nomefile, denominazione, ip, tipoMf, "KO", e.getMessage()));
        }
    }

    private static boolean vero(Map<String, Object> mappa, String chiave) {
        Object v = mappa == null ? null : mappa.get(chiave);
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        return v != null && "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static int numero(String s, int riserva) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return riserva;
        }
    }

    private void ricorda(String nomeLavoro) {
        synchronized (gia) {
            gia.add(nomeLavoro);
            // si tiene solo la coda recente: serve contro i doppi invii, non come archivio
            while (gia.size() > QUANTI_RICORDARNE) {
                gia.remove(gia.iterator().next());
            }
        }
    }

    private static Json voce(String file, String stampante, String ip, String tipoMf,
                             String stato, String messaggio) {
        return new Json()
                .testo("file", file)
                .testo("stampante", stampante)
                .testo("ip", ip)
                .testo("tipo_mf", tipoMf)
                .testo("status", stato)
                .testo("message", messaggio);
    }

    private static String esito(String risultato, String messaggio, List<Json> riusciti,
                                List<Json> falliti, List<Json> avvisi) {
        Json dettagli = new Json()
                .elenco("success", riusciti)
                .elenco("failed", falliti)
                .elenco("warnings", avvisi);
        return new Json()
                .testo("Result", risultato)
                .testo("Message", messaggio)
                .oggetto("Details", dettagli)
                .toString();
    }

    private static String valore(Map<String, Object> mappa, String chiave, String riserva) {
        if (mappa == null) {
            return riserva;
        }
        Object v = mappa.get(chiave);
        return v == null ? riserva : String.valueOf(v);
    }
}
