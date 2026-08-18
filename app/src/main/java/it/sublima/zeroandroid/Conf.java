package it.sublima.zeroandroid;

/**
 * L'unica configurazione che l'agente ha bisogno di ricordare.
 *
 * Fino a ieri non ne aveva nessuna, ed era una scelta: tutto quello che serve
 * per stampare — indirizzi, porte, tipo di registratore, matricola — viaggia nel
 * manifest di ogni singolo lavoro, e l'indirizzo di Sublima si ricavava
 * dall'header Origin della richiesta in arrivo. L'agente restava senza stato e
 * la configurazione viveva tutta in Sublima.
 *
 * L'ascolto delle comande capovolge il verso: e' l'agente a chiamare Sublima, e
 * per chiamarla deve sapere dove sta, quale punto vendita rappresenta e con
 * quale licenza presentarsi. Quelle tre cose vanno ricordate fra un riavvio e
 * l'altro, e questa e' la sola ragione per cui questa classe esiste.
 *
 * ⚠️ Non importa nulla di Android di proposito: `tools/prova.sh` esclude dalla
 * compilazione dei test ogni classe che lo faccia, e questa deve restare
 * verificabile su JVM. Il deposito vero si inietta dall'esterno — le preferenze
 * di sistema sull'app, un file sul PC.
 */
public final class Conf {

    private Conf() {
    }

    /** Indirizzo di Sublima, schema compreso e senza barra finale. */
    public static final String URL_PROFILO = "url_profilo";

    /**
     * Il punto **vendita**, non il punto cassa.
     *
     * E' l'unica chiave di instradamento delle comande: un tramite per magazzino
     * che serve N punti cassa. Instradare per punto cassa e' stato un difetto
     * vero, che scartava in silenzio le stampe delle altre casse dello stesso
     * locale.
     */
    public static final String ID_MAGAZZINO = "id_magazzino";

    /** Solo un'etichetta nei log di Sublima: non identifica e non autorizza. */
    public static final String LICENZA = "zero_licenza";

    /**
     * Il nome del punto vendita, per chi guarda.
     *
     * Non serve al protocollo, che instrada sul numero. Serve a rispondere a
     * "questo tablet a quale locale e' attaccato" senza andare a cercare la
     * corrispondenza fra un numero e un posto.
     */
    public static final String NOME_MAGAZZINO = "nome_magazzino";

    /** Interruttore locale dell'ascolto comande: "on" oppure niente. */
    public static final String SSE_ATTIVO = "sse_attivo";

    /** Dove si ricordano le chiavi fra un avvio e l'altro. */
    public interface Deposito {
        String leggi(String chiave);

        void scrivi(String chiave, String valore);
    }

    /**
     * Deposito di riserva: tiene tutto in memoria e dimentica allo spegnimento.
     *
     * Non e' un ripiego silenzioso — e' quello che usano i test, e quello che
     * resta se il deposito vero non si riesce ad aprire. In quel caso l'agente
     * funziona lo stesso finche' resta acceso, e la schermata lo dice.
     */
    public static class InMemoria implements Deposito {
        private final java.util.Map<String, String> valori =
                new java.util.HashMap<String, String>();

        public synchronized String leggi(String chiave) {
            return valori.get(chiave);
        }

        public synchronized void scrivi(String chiave, String valore) {
            if (valore == null) {
                valori.remove(chiave);
            } else {
                valori.put(chiave, valore);
            }
        }
    }

    private static volatile Deposito deposito = new InMemoria();

    public static void usaDeposito(Deposito d) {
        deposito = (d == null) ? new InMemoria() : d;
    }

    public static String leggi(String chiave, String riserva) {
        try {
            String v = deposito.leggi(chiave);
            return (v == null || v.trim().isEmpty()) ? riserva : v.trim();
        } catch (Exception e) {
            return riserva;
        }
    }

    public static void scrivi(String chiave, String valore) {
        try {
            deposito.scrivi(chiave, valore == null ? null : valore.trim());
        } catch (Exception e) {
            ServerHttp.registra("configurazione non salvata (" + chiave + "): " + e.getMessage());
        }
    }

    // ---- le domande che il resto del codice pone davvero -------------------

    public static String urlProfilo() {
        return senzaBarraFinale(leggi(URL_PROFILO, ""));
    }

    public static String idMagazzino() {
        return leggi(ID_MAGAZZINO, "");
    }

    public static String licenza() {
        return leggi(LICENZA, "");
    }

    public static String nomeMagazzino() {
        return leggi(NOME_MAGAZZINO, "");
    }

    /** «PRINCIPALE (n. 4)», o solo il numero se il nome non si conosce. */
    public static String magazzinoLeggibile() {
        String nome = nomeMagazzino();
        String id = idMagazzino();
        if (id.isEmpty()) {
            return "";
        }
        return nome.isEmpty() ? ("n. " + id) : (nome + " (n. " + id + ")");
    }

    /**
     * Vero quando c'e' abbastanza per provare a collegarsi.
     *
     * La licenza non entra: Sublima la usa solo come etichetta nei log, e
     * pretenderla impedirebbe di collegarsi a chi non ce l'ha.
     */
    public static boolean agganciato() {
        return !urlProfilo().isEmpty() && !idMagazzino().isEmpty();
    }

    /**
     * Vero se l'ascolto delle comande va tentato.
     *
     * ⚠️ Il difetto e' **spento**, e non e' prudenza generica: l'ascolto serve
     * solo dove c'e' la ristorazione, cioe' a una installazione su dieci. Sulle
     * altre nove una connessione tentata non e' innocua — riesce, perche' il
     * livello del punto cassa non viene controllato all'apertura del flusso, e
     * poi tiene occupato per sempre uno dei sei thread di uWSGI del profilo per
     * non consegnare niente. Si accende quando qualcuno lo chiede: dalla
     * schermata dell'app, dalla pagina di stato, o dal TEST ZERO di un punto
     * cassa che dichiara di volere l'SSE.
     */
    public static boolean sseAttivo() {
        return acceso() && agganciato();
    }

    /** L'interruttore da solo, senza guardare se c'e' un aggancio. */
    public static boolean acceso() {
        return "on".equalsIgnoreCase(leggi(SSE_ATTIVO, "off"));
    }

    public static void accendi(boolean si) {
        scrivi(SSE_ATTIVO, si ? "on" : "off");
    }

    /**
     * Registra a quale profilo e magazzino ci si aggancia.
     *
     * Ritorna il motivo del rifiuto, o null se e' andata. Rifiuta un indirizzo
     * che dal dispositivo non porta da nessuna parte invece di accettarlo e
     * fallire poi: e' lo stesso guasto del QR non scansionabile, e si
     * manifesterebbe come "l'ascolto comande non funziona".
     */
    public static String aggancia(String url, String idMagazzino, String licenza) {
        return aggancia(url, idMagazzino, licenza, false);
    }

    /**
     * Come sopra, ma `forzato` salta il controllo sull'indirizzo cieco.
     *
     * Serve a chi lo scrive a mano: sul banco di prova l'agente e Sublima
     * possono davvero stare sulla stessa macchina, e li' `localhost` e'
     * l'indirizzo giusto. Dall'aggancio automatico invece si rifiuta, perche'
     * li' significa quasi sempre che il server non sa dire il proprio indirizzo.
     */
    public static String aggancia(String url, String idMagazzino, String licenza,
                                  boolean forzato) {
        String pulito = senzaBarraFinale(url == null ? "" : url.trim());
        if (pulito.isEmpty()) {
            return "manca l'indirizzo di Sublima";
        }
        if (!pulito.startsWith("http://") && !pulito.startsWith("https://")) {
            return "l'indirizzo deve cominciare con http:// o https://";
        }
        if (!forzato && indirizzoCieco(pulito)) {
            return "l'indirizzo " + pulito + " vale solo sul server:"
                    + " da questo dispositivo non porta da nessuna parte."
                    + " Configura l'indirizzo del server in Setup -> Generale";
        }
        String mag = (idMagazzino == null) ? "" : idMagazzino.trim();
        if (mag.isEmpty()) {
            return "manca il magazzino";
        }

        // Cambiando punto vendita il nome vecchio diventa una bugia, e una
        // bugia sullo schermo e' peggio di un numero nudo: chi chiama scrive
        // quello nuovo subito dopo, se lo conosce.
        if (!mag.equals(idMagazzino())) {
            scrivi(NOME_MAGAZZINO, null);
        }
        scrivi(URL_PROFILO, pulito);
        scrivi(ID_MAGAZZINO, mag);
        scrivi(LICENZA, licenza);
        return null;
    }

    public static void sgancia() {
        scrivi(URL_PROFILO, null);
        scrivi(ID_MAGAZZINO, null);
        scrivi(LICENZA, null);
        scrivi(NOME_MAGAZZINO, null);
    }

    /** Indirizzi che significano "me stesso" e quindi non sono raggiungibili da qui. */
    static boolean indirizzoCieco(String url) {
        String h = ospite(url).toLowerCase();
        return h.isEmpty() || h.equals("localhost") || h.equals("127.0.0.1")
                || h.equals("0.0.0.0") || h.equals("::1") || h.startsWith("127.");
    }

    static String ospite(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    static String senzaBarraFinale(String s) {
        String v = (s == null) ? "" : s.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
