package it.sublima.zeroandroid;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Il pagamento completo, con la rete di sicurezza contro il doppio addebito.
 *
 * IL PROBLEMA CHE RISOLVE. Se il terminale autorizza e poi l'esito non arriva —
 * rete caduta, frame perso, terminale che chiude — la cassa registra KO e
 * l'operatore rilancia il pagamento: **il cliente paga due volte**. E' successo
 * il 30/07/2026 su 4,99 EUR.
 *
 * COME. Il comando 'G' rispedisce copia dell'ultimo esito salvato dal terminale.
 * Da solo pero' non basta: quell'esito potrebbe essere di ieri. Percio' si
 * prende un'IMPRONTA di riferimento *prima* di pagare, e dopo si confronta:
 *
 *   impronta cambiata  -> l'esito e' di questa transazione, lo si riporta al
 *                         posto del KO;
 *   impronta uguale    -> la transazione non e' mai partita, si puo' rilanciare
 *                         in sicurezza;
 *   impronta ignota    -> non si dichiara niente: si mostra all'operatore cosa
 *                         dice il terminale e decide lui.
 *
 * L'impronta usa quattro campi e non il solo numero progressivo, perche' un
 * terminale azzerato puo' ricominciare la numerazione da capo: un falso "e' la
 * stessa transazione" farebbe perdere un incasso.
 */
public final class Pos17Pagamento {

    /** Esiti del tentativo di recupero. */
    public static final String RECUPERO_NON_RICHIESTO = "non_richiesto";
    public static final String RECUPERO_NON_SERVITO = "non_servito";
    public static final String RECUPERO_RIUSCITO = "recuperato";
    public static final String RECUPERO_NESSUNA_TRANSAZIONE = "nessuna_transazione";
    public static final String RECUPERO_INCERTO = "incerto";

    /**
     * ⚠️ I due tempi sono diversi apposta.
     *
     * Quello di riferimento e' del 'G' che precede OGNI pagamento: se un
     * terminale non implementasse il comando e restasse zitto, l'attesa la
     * pagherebbe il cliente in cassa a ogni transazione, e per una rete di
     * sicurezza e' un prezzo che non si puo' chiedere.
     *
     * Quello di recupero e' del 'G' che si fa DOPO un esito perso: li' qualcosa
     * e' gia' andato storto, si sta cercando di capire se il cliente ha pagato,
     * e vale la pena aspettare di piu'.
     */
    public static final int TIMEOUT_RIFERIMENTO = 5000;
    public static final int TIMEOUT_RECUPERO = 15000;

    /**
     * Terminali che al comando 'G' non rispondono.
     *
     * Non e' un'ottimizzazione, e' una misura operativa: esistono terminali —
     * l'emulatore in casa e' uno di questi — che rispondono benissimo allo stato
     * e al pagamento ma al 'G' restano muti fino al timeout. Su un apparato cosi'
     * il giro di riferimento costerebbe secondi a OGNI pagamento, e ad aspettare
     * sarebbe il cliente in cassa.
     *
     * Dopo due tentativi a vuoto si smette di chiedere e si riprova mezz'ora
     * dopo: un aggiornamento di firmware puo' aggiungere il comando, e non si
     * vuole restare senza rete di sicurezza per sempre per colpa di due timeout.
     *
     * La chiave e' indirizzo:porta e non l'identificativo del terminale, perche'
     * e' l'apparato che risponde o no, e l'ID configurato puo' benissimo essere
     * quello sbagliato.
     */
    private static final Map<String, long[]> G_SENZA_RISPOSTA =
            new ConcurrentHashMap<String, long[]>();
    public static final int TENTATIVI_G_PRIMA_DI_RINUNCIARE = 2;
    public static final long PAUSA_DOPO_RINUNCIA_G = 1800000L;

    private Pos17Pagamento() {
    }

    /** Come e' andato il pagamento, compreso l'eventuale recupero. */
    public static class Esito {
        public Pos17Conversazione.Risultato risultato;
        public String statoRecupero = RECUPERO_NON_RICHIESTO;
        public String messaggioRecupero;
        public String improntaPrima;
        public Map<String, String> esitoRecuperato;

        public boolean riuscito() {
            return risultato != null && risultato.eEsito();
        }

        public Map<String, String> dati() {
            return (risultato == null) ? null : risultato.dati;
        }
    }

    /**
     * Identifica una transazione dentro la memoria del terminale.
     *
     * Quattro campi invece del solo progressivo: un terminale azzerato puo'
     * ricominciare la numerazione, e scambiare due transazioni per una farebbe
     * perdere un incasso.
     */
    public static String impronta(Map<String, String> dati) {
        if (dati == null) {
            return "";
        }
        return valore(dati, "STAN") + "|" + valore(dati, "codice_autorizzazione")
                + "|" + valore(dati, "dati_temporali") + "|" + valore(dati, "esito");
    }

    /** Comando 'G': copia dell'ultimo esito salvato dal terminale. */
    public static Pos17Conversazione.Risultato leggiUltimoEsito(
            String ip, int porta, String idPos, String idCassa, int timeout,
            Pos17Conversazione.Registro log) {
        // ⚠️ Connessione dedicata: il terminale chiude spesso dopo aver risposto
        // a un comando, quindi riusare quella del pagamento non e' affidabile.
        return Pos17Conversazione.esegui(ip, porta,
                Pos17.frameUltimoEsito(idPos, idCassa), timeout, idPos,
                Pos17Conversazione.ESITO, log);
    }

    /**
     * Pagamento con la rete di sicurezza.
     *
     * Quando il recupero riesce, l'esito risulta riuscito come gli altri e nei
     * dati compare `recuperato`: per la cassa e' un pagamento andato a buon
     * fine, ma il registro e l'operatore sanno che e' stato ripescato.
     */
    public static Esito paga(String ip, int porta, String idPos, String idCassa,
                             Object importoEuro, String contratto, boolean conRecupero,
                             int timeout, Pos17Conversazione.Registro log)
            throws Pos17.ImportoNonValido {
        Pos17Conversazione.Registro l = (log == null)
                ? new Pos17Conversazione.Registro() {
                    public void scrivi(String m) {
                    }
                } : log;
        Esito esito = new Esito();
        long centRichiesti = Pos17.centesimi(importoEuro);

        // 1. L'impronta di riferimento. Se non si riesce a prenderla non si
        //    blocca niente: si paga lo stesso, ma senza rete di sicurezza, e lo
        //    si scrive nel registro.
        String improntaPrima = null;
        if (conRecupero && gDaSaltare(ip, porta)) {
            l.scrivi("[G] impronta di riferimento SALTATA: questo terminale non ha"
                    + " risposto al comando 'G' le ultime " + TENTATIVI_G_PRIMA_DI_RINUNCIARE
                    + " volte, e non si fa aspettare il cliente in cassa a ogni"
                    + " pagamento. Si riprova automaticamente fra mezz'ora.");
        } else if (conRecupero) {
            l.scrivi("[G] chiedo l'ultimo esito salvato, per l'impronta di riferimento");
            Pos17Conversazione.Risultato rif = leggiUltimoEsito(ip, porta, idPos,
                    idCassa, TIMEOUT_RIFERIMENTO, l);
            gRegistra(ip, porta, rif.eEsito());
            if (rif.eEsito()) {
                improntaPrima = impronta(rif.dati);
                l.scrivi("[G] impronta di riferimento: " + improntaPrima);
            } else {
                l.scrivi("[G] impronta di riferimento NON disponibile (" + rif.tipo
                        + "): il pagamento parte comunque, ma senza rete di"
                        + " sicurezza sul recupero");
            }
        }
        esito.improntaPrima = improntaPrima;

        // 2. Il pagamento.
        byte[] frame = Pos17.framePagamento(idPos, idCassa, importoEuro, contratto, false);
        esito.risultato = Pos17Conversazione.esegui(ip, porta, frame, timeout, idPos,
                Pos17Conversazione.ESITO, l);

        if (esito.risultato.eEsito()) {
            esito.statoRecupero = RECUPERO_NON_SERVITO;
            return esito;
        }
        if (!conRecupero) {
            esito.statoRecupero = RECUPERO_NON_RICHIESTO;
            return esito;
        }

        // 3. L'esito si e' perso: si chiede al terminale cosa ha in memoria.
        //    Questo 'G' si tenta SEMPRE, anche sui terminali che risultano non
        //    rispondere: succede solo quando un pagamento e' gia' andato storto,
        //    e' la sola occasione di capire se il cliente ha pagato, e
        //    rinunciarci per risparmiare qualche secondo vorrebbe dire
        //    rinunciare a dei soldi.
        l.scrivi("[G] esito perso (" + esito.risultato.tipo
                + "): chiedo al terminale l'ultimo risultato");
        Pos17Conversazione.Risultato ripescato = leggiUltimoEsito(ip, porta, idPos,
                idCassa, TIMEOUT_RECUPERO, l);
        gRegistra(ip, porta, ripescato.eEsito());

        if (!ripescato.eEsito()) {
            return incerto(esito, l, "Il terminale non ha saputo dire l'esito"
                    + " dell'ultima transazione (" + ripescato.tipo + "): prima di"
                    + " rilanciare il pagamento controlla lo scontrino del POS.", null);
        }

        Map<String, String> dati = ripescato.dati;
        String improntaDopo = impronta(dati);

        if (improntaPrima == null) {
            return incerto(esito, l, "Non si puo' dire se appartenga a questo"
                    + " pagamento, perche' non e' stato possibile leggere l'esito"
                    + " precedente. Il terminale riporta: "
                    + valore(dati, "descrizione_codice_esito") + ", importo autorizzato "
                    + orNd(dati.get("importo_host_euro")) + ", PAN "
                    + orNd(dati.get("PAN")) + ", autorizzazione "
                    + orNd(dati.get("codice_autorizzazione"))
                    + ". CONTROLLA prima di rilanciare.", dati);
        }

        if (improntaDopo.equals(improntaPrima)) {
            esito.statoRecupero = RECUPERO_NESSUNA_TRANSAZIONE;
            esito.messaggioRecupero = "Il terminale non registra nessuna transazione"
                    + " nuova: il pagamento non e' partito e si puo' rilanciare.";
            esito.esitoRecuperato = dati;
            l.scrivi("[G] " + esito.messaggioRecupero);
            esito.risultato.motivo = unisci(esito.risultato.motivo, esito.messaggioRecupero);
            return esito;
        }

        // Impronta cambiata: la transazione e' nostra. Se il terminale riporta
        // anche l'importo autorizzato, lo si controlla: un importo diverso vuol
        // dire che stiamo guardando un'altra transazione, e dichiararla nostra
        // costerebbe soldi.
        String importoHost = valore(dati, "importo_host_centesimi");
        if (soloCifre(importoHost) && Long.parseLong(importoHost) != centRichiesti) {
            return incerto(esito, l, "Il terminale riporta una transazione nuova ma"
                    + " di importo diverso (" + importoHost + " centesimi invece di "
                    + centRichiesti + "): non la si attribuisce a questo pagamento."
                    + " CONTROLLA lo scontrino del POS.", dati);
        }

        String messaggio = "Esito ripescato dal terminale col comando 'G': la"
                + " transazione era stata eseguita anche se la risposta si era persa.";
        l.scrivi("[G] RECUPERATO: " + messaggio + " (esito " + dati.get("esito") + ")");
        Map<String, String> recuperati = new LinkedHashMap<String, String>(dati);
        recuperati.put("recuperato", "true");
        recuperati.put("descrizione_recupero", messaggio);

        esito.risultato = new Pos17Conversazione.Risultato("esito", null, recuperati,
                esito.risultato.secondi, esito.risultato.grezzoHex);
        esito.statoRecupero = RECUPERO_RIUSCITO;
        esito.messaggioRecupero = messaggio;
        esito.esitoRecuperato = recuperati;
        return esito;
    }

    // ---- memoria dei terminali muti al 'G' -------------------------------------

    static boolean gDaSaltare(String ip, int porta) {
        long[] voce = G_SENZA_RISPOSTA.get(ip + ":" + porta);
        if (voce == null || voce[0] < TENTATIVI_G_PRIMA_DI_RINUNCIARE) {
            return false;
        }
        if ((System.currentTimeMillis() - voce[1]) > PAUSA_DOPO_RINUNCIA_G) {
            G_SENZA_RISPOSTA.remove(ip + ":" + porta);
            return false;
        }
        return true;
    }

    static void gRegistra(String ip, int porta, boolean riuscito) {
        String k = ip + ":" + porta;
        if (riuscito) {
            G_SENZA_RISPOSTA.remove(k);
            return;
        }
        long[] voce = G_SENZA_RISPOSTA.get(k);
        long falliti = (voce == null) ? 1 : voce[0] + 1;
        G_SENZA_RISPOSTA.put(k, new long[]{falliti, System.currentTimeMillis()});
    }

    /** Azzera la memoria: serve alle prove e dopo aver cambiato un apparato. */
    public static void dimenticaTerminaliSenzaG() {
        G_SENZA_RISPOSTA.clear();
    }

    // ---- utilita' ---------------------------------------------------------------

    private static Esito incerto(Esito esito, Pos17Conversazione.Registro log,
                                 String messaggio, Map<String, String> dati) {
        log.scrivi("[G] " + messaggio);
        esito.statoRecupero = RECUPERO_INCERTO;
        esito.messaggioRecupero = messaggio;
        esito.esitoRecuperato = dati;
        esito.risultato.motivo = unisci(esito.risultato.motivo, messaggio);
        return esito;
    }

    private static String unisci(String a, String b) {
        return ((a == null ? "" : a) + " " + b).trim();
    }

    private static String valore(Map<String, String> d, String chiave) {
        String v = (d == null) ? null : d.get(chiave);
        return (v == null) ? "" : v.trim();
    }

    private static String orNd(String v) {
        return (v == null || v.trim().isEmpty()) ? "n/d" : v.trim();
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
