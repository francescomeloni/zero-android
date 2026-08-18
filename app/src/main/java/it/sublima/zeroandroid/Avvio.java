package it.sublima.zeroandroid;

/**
 * Avvio dell'agente da riga di comando, senza Android.
 *
 * Serve per provarlo dal PC contro un Sublima vero prima di installare
 * qualunque cosa sul telefono, e resta il modo piu' rapido per vedere cosa
 * arriva davvero da una stampa.
 *
 *   java -cp build/classi it.sublima.zeroandroid.Avvio [porta]
 *
 * Senza argomenti usa la 55226, la stessa dello Zero: quindi lo Zero esterno va
 * spento, altrimenti la porta e' gia' occupata.
 */
public final class Avvio {

    private Avvio() {
    }

    public static void main(String[] args) throws Exception {
        int porta = ServerHttp.PORTA_ZERO;
        if (args.length > 0) {
            try {
                porta = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Porta non valida: " + args[0]);
                System.exit(2);
            }
        }

        // Da PC la configurazione sta in un file: cosi' l'aggancio al profilo
        // sopravvive a un riavvio anche qui, e l'ascolto comande si prova per
        // intero senza tirare in ballo Android.
        Conf.usaDeposito(new DepositoFile());

        Stampa stampa = new Stampa();
        ServerHttp server = new ServerHttp(porta, stampa);
        try {
            server.avvia();
        } catch (java.net.BindException e) {
            System.err.println("La porta " + porta + " e' gia' occupata: probabilmente c'e'"
                    + " lo Zero acceso. Spegnilo, oppure indica un'altra porta.");
            System.exit(3);
        }

        // Lo stesso oggetto Stampa dei due ingressi: cosi' un lavoro arrivato da
        // tutt'e due le strade viene riconosciuto e stampato una volta sola.
        ClienteSse ascolto = new ClienteSse(stampa);
        ascolto.avvia();

        System.out.println(Versione.NOME + " " + Versione.NUMERO);
        System.out.println("Indicalo come IP cassa nella scheda del punto cassa, poi premi"
                + " TEST ZERO ESTERNO.");
        if (Conf.agganciato()) {
            System.out.println("Ascolto comande SSE: " + Conf.urlProfilo()
                    + " magazzino " + Conf.idMagazzino());
        } else {
            System.out.println("Ascolto comande SSE: non configurato (lo imposta il TEST ZERO).");
        }
        System.out.println("Ctrl+C per fermarlo.");

        // resta in piedi finche' non lo si ferma
        Thread.currentThread().join();
    }
}
