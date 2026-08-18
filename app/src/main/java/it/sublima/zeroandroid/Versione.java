package it.sublima.zeroandroid;

/**
 * Versione dell'agente e capacita' che sa gestire.
 *
 * Sublima le legge dalla risposta di /test_zero e ci decide cosa mandare: la
 * scheda del punto cassa le mostra e avvisa se il tramite e' da aggiornare.
 * Un tramite che non le dichiara viene trattato come uno che sa solo il
 * metalinguaggio a tag.
 *
 * Vedi il paragrafo 6 di PROTOCOLLO.md nel repo dello Zero.
 * Va alzata a ogni rilascio che cambia qualcosa di visibile da Sublima.
 */
public final class Versione {

    private Versione() {
    }

    public static final String NUMERO = "2026.08.18.4";

    /** Nome con cui l'agente si presenta nei log e nella pagina di stato. */
    public static final String NOME = "Zero Android";

    /**
     * Dove sta girando, in forma leggibile nella scheda del punto cassa.
     *
     * Fuori da Android resta il valore ricavato dalla JVM, cosi' `Avvio` si
     * riconosce dalle prove fatte dal PC. L'app la sovrascrive all'accensione
     * con la versione di Android del dispositivo: qui non si importa nulla di
     * Android per tenere questa classe verificabile su JVM.
     */
    private static String piattaforma = piattaformaDallaJvm();

    public static String piattaforma() {
        return piattaforma;
    }

    public static void dichiaraPiattaforma(String descrizione) {
        if (descrizione != null && !descrizione.trim().isEmpty()) {
            piattaforma = descrizione.trim();
        }
    }

    private static String piattaformaDallaJvm() {
        try {
            String sistema = System.getProperty("os.name", "sconosciuto");
            String java = System.getProperty("java.version", "");
            return sistema + " - Java " + java;
        } catch (Exception e) {
            return "sconosciuta";
        }
    }

    /**
     * Una cosa che l'agente sa (o non sa) fare.
     *
     * Sta tutto qui: da questo elenco si ricavano il JSON che legge Sublima, la
     * pagina di stato e la schermata dell'app. Prima le tre cose erano scritte
     * a mano in posti diversi, e infatti divergevano.
     */
    public static class Capacita {
        public final String chiave;
        public final String etichetta;
        public final boolean supportata;
        public final String nota;

        Capacita(String chiave, String etichetta, boolean supportata, String nota) {
            this.chiave = chiave;
            this.etichetta = etichetta;
            this.supportata = supportata;
            this.nota = nota;
        }
    }

    /**
     * Cosa l'agente copre, in ordine di importanza.
     *
     * ⚠️ Il metalinguaggio a tag e' dichiarato **non** supportato apposta: la
     * traduzione vive in un posto solo, dentro Sublima, e riscriverla qui
     * vorrebbe dire farla divergere. Dichiararlo permette alla scheda del punto
     * cassa di avvisare, invece di lasciare qualcuno a chiedersi perche' non
     * esce niente.
     */
    public static java.util.List<Capacita> elenco() {
        java.util.List<Capacita> c = new java.util.ArrayList<Capacita>();
        c.add(new Capacita("escpos_bytes", "Comande e scontrini ESC/POS", true,
                "tracciati gia' tradotti in byte"));
        c.add(new Capacita("micrelec", "Registratore fiscale Micrelec", true,
                "scontrino, display, chiusure, numero restituito a Sublima"));
        c.add(new Capacita("epson_xml", "Registratore Epson", true, "via HTTP"));
        c.add(new Capacita("custom_xml", "Registratore Custom", true, "via HTTP"));
        c.add(new Capacita("pos_prot17", "Pagamento con carta (POS Ingenico)", true,
                "protocollo 17, con recupero anti-doppio-addebito"));
        c.add(new Capacita("sse_comande", "Ascolto comande SSE, senza CORS", true,
                "e' l'agente a chiamare Sublima: il palmare non contatta nessuno"));
        c.add(new Capacita("escpos_tag", "Metalinguaggio a tag", false,
                "lo traduce Sublima: metti il punto cassa su 'Byte nativi'"));
        return c;
    }

    /** Le capacita' nella forma che Sublima si aspetta in /test_zero. */
    public static Json capacita() {
        Json j = new Json();
        for (Capacita c : elenco()) {
            j.vero(c.chiave, c.supportata);
        }
        return j;
    }

    /** Quello che l'agente NON copre, da dire con altrettanta chiarezza. */
    public static java.util.List<String> fuoriPortata() {
        java.util.List<String> f = new java.util.ArrayList<String>();
        f.add("Stampanti seriali e USB");
        f.add("Casse automatiche (Cashmatic, VNE, Pagamico)");
        return f;
    }
}
