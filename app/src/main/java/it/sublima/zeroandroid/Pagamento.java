package it.sublima.zeroandroid;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Il ramo POS del dispatcher: dal messaggio di Sublima al terminale.
 *
 * Il pagamento entra dallo stesso indirizzo delle stampe, ma con
 * `tipo == "POS"`, e da li' non si stampa piu' niente.
 *
 * ⚠️ **La forma della risposta e' copiata alla lettera da quella dello Zero
 * esterno, e non va "migliorata".** Il client di Sublima sa gia' digerire due
 * forme incoerenti — lo Zero esterno marca il rifiuto in testa, quello interno
 * lo annida sotto un OK — e le riconosce entrambe. Inventarne una terza
 * significa che un rifiuto non verrebbe riconosciuto e **la cassa avanzerebbe
 * su una carta rifiutata**.
 *
 * ⚠️ Attenzione ai due campi contro-intuitivi della barriera casse: `modello`
 * e' l'**ID POS** e `matricola` e' l'**ID cassa**. E' cosi' anche nella scheda
 * di configurazione, che li etichetta di conseguenza.
 */
public final class Pagamento {

    private Pagamento() {
    }

    public static String esegui(Map<String, Object> messaggio, Richiesta richiesta) {
        Map<String, Object> bc = sottoOggetto(messaggio, "auto_pag_bc");
        if (bc == null) {
            return errore("Manca la configurazione del terminale (auto_pag_bc)");
        }

        String ip = testo(bc, "ip", "");
        int porta = intero(testo(bc, "ip_port", "1000"), 1000);
        String idCassa = testo(bc, "matricola", "");   // ⚠️ matricola = ID cassa
        String idPos = testo(bc, "modello", "");       // ⚠️ modello  = ID POS
        String contratto = testo(bc, "contratto", "");
        String denominazione = testo(bc, "denominazione", "POS");
        String importo = JsonLettore.testo(messaggio, "importo", "");

        // ⚠️ `cmd` assente vale come PAGAMENTO: e' il comportamento dello Zero, e
        // chi e' piu' severo rompe i chiamanti che non lo mandano.
        String cmd = JsonLettore.testo(messaggio, "cmd", "").toUpperCase();

        final StringBuilder diario = new StringBuilder();
        Pos17Conversazione.Registro log = new Pos17Conversazione.Registro() {
            public void scrivi(String m) {
                ServerHttp.registra("[POS] " + m);
                diario.append(m).append('\n');
            }
        };

        for (String problema : Pos17.anomalieConfigurazione(idPos, idCassa, contratto)) {
            ServerHttp.registra("[POS] configurazione: " + problema);
        }

        // ⚠️ Una transazione per volta sullo stesso terminale. Lo Zero questa
        // garanzia non ce l'ha — le difese contro le risposte tardive stanno nel
        // browser — ma qui non e' un'ottimizzazione: due pagamenti sovrapposti
        // sullo stesso apparato non devono poter esistere.
        try {
            Turno.attendi(ip);
        } catch (Turno.TroppaCoda e) {
            return errore(e.getMessage());
        }

        try {
            if ("STATO".equals(cmd) || "ULTIMO".equals(cmd)) {
                return diagnosi(cmd, ip, porta, idPos, idCassa, log);
            }
            return pagamento(ip, porta, idPos, idCassa, importo, contratto,
                    denominazione, log);
        } catch (Pos17.ImportoNonValido e) {
            return errore("Importo non valido: " + e.getMessage());
        } catch (Exception e) {
            ServerHttp.registra("[POS] errore: " + e);
            return errore("Errore durante il pagamento: " + e.getMessage());
        } finally {
            Turno.libera(ip);
        }
    }

    /**
     * I comandi di sola diagnosi, dal pulsante della scheda punto cassa.
     *
     * ⚠️ Non sono un di piu': su `ULTIMO` poggia lo sblocco della cassiera
     * quando il browser va in timeout. Un agente che risponde solo ai pagamenti
     * la lascia senza via d'uscita. Nessuno dei due muove denaro o chiede la carta.
     */
    private static String diagnosi(String cmd, String ip, int porta, String idPos,
                                   String idCassa, Pos17Conversazione.Registro log) {
        Pos17Conversazione.Risultato r;
        if ("STATO".equals(cmd)) {
            r = Pos17Conversazione.esegui(ip, porta, Pos17.frameStato(idPos),
                    30000, idPos, Pos17Conversazione.STATO, log);
        } else {
            r = Pos17Pagamento.leggiUltimoEsito(ip, porta, idPos, idCassa, 30000, log);
        }

        if (r.eEsito()) {
            // `Result` dice se la DOMANDA e' riuscita, non com'e' andata la
            // transazione: quella sta dentro Message e la legge chi mostra la
            // risposta.
            return new Json()
                    .testo("Result", "OK")
                    .testo("Response", "OK")
                    .testo("comando", cmd)
                    .oggetto("Message", comeJson(r.dati))
                    .toString();
        }
        String motivo = (r.motivo == null) ? "Nessuna risposta dal POS" : r.motivo;
        return new Json()
                .testo("Result", "KO")
                .testo("Response", "KO")
                .testo("comando", cmd)
                .testo("Message", motivo)
                .testo("motivo", motivo)
                .toString();
    }

    private static String pagamento(String ip, int porta, String idPos, String idCassa,
                                    String importo, String contratto, String denominazione,
                                    Pos17Conversazione.Registro log)
            throws Pos17.ImportoNonValido {
        Pos17Pagamento.Esito esito = Pos17Pagamento.paga(ip, porta, idPos, idCassa,
                importo, contratto, true, Pos17Conversazione.TIMEOUT_PAGAMENTO, log);

        if (esito.messaggioRecupero != null) {
            ServerHttp.registra("[POS] recupero " + esito.statoRecupero + ": "
                    + esito.messaggioRecupero);
        }

        Map<String, String> dati = esito.dati();
        if (esito.riuscito() && dati != null) {
            if ("OK".equals(dati.get("esito_interno"))) {
                Json risposta = new Json()
                        .testo("Result", "OK")
                        .testo("Response", "OK")
                        .oggetto("Message", comeJson(dati))
                        .testo("esito_interno", "OK");
                if ("true".equals(dati.get("recuperato"))) {
                    // Incasso ripescato: per la cassa e' un pagamento riuscito
                    // come gli altri, ma va lasciata traccia in chiaro.
                    risposta.testo("recupero", esito.statoRecupero);
                    risposta.testo("descrizione", dati.get("descrizione_recupero"));
                    ServerHttp.registra("[POS] ESITO RECUPERATO: la risposta si era"
                            + " persa, ma il terminale aveva incassato");
                }
                return risposta.toString();
            }
            String motivo = dati.get("descrizione_esito");
            if (motivo == null || motivo.isEmpty()) {
                motivo = "Transazione non completata";
            }
            return new Json()
                    .testo("Result", "KO")
                    .testo("Response", "KO")
                    .oggetto("Message", comeJson(dati))
                    .testo("esito_interno", "KO")
                    .testo("motivo", motivo)
                    .testo("descrizione", motivo)
                    .toString();
        }

        String motivo = (esito.risultato == null || esito.risultato.motivo == null)
                ? "Nessuna risposta valida dal POS" : esito.risultato.motivo;
        ServerHttp.registra("[POS] " + denominazione + " (" + ip + "): " + motivo);
        return errore(motivo);
    }

    // ---- appoggio ---------------------------------------------------------------

    private static String errore(String motivo) {
        return new Json().testo("Result", "KO").testo("Message", motivo).toString();
    }

    private static Json comeJson(Map<String, String> dati) {
        Json j = new Json();
        if (dati != null) {
            for (Map.Entry<String, String> e : dati.entrySet()) {
                j.testo(e.getKey(), e.getValue());
            }
        }
        return j;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sottoOggetto(Map<String, Object> messaggio,
                                                    String chiave) {
        Object v = (messaggio == null) ? null : messaggio.get(chiave);
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        // puo' arrivare anche come testo con dentro il JSON
        if (v instanceof String) {
            try {
                return JsonLettore.oggetto((String) v);
            } catch (JsonLettore.JsonNonValido e) {
                return null;
            }
        }
        return null;
    }

    private static String testo(Map<String, Object> d, String chiave, String riserva) {
        Object v = (d == null) ? null : d.get(chiave);
        if (v == null) {
            return riserva;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? riserva : s;
    }

    private static int intero(String s, int riserva) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return riserva;
        }
    }

    private static List<Json> vuoto() {
        return new ArrayList<Json>();
    }
}
