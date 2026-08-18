import it.sublima.zeroandroid.ClienteSse;
import it.sublima.zeroandroid.Conf;
import it.sublima.zeroandroid.Magazzini;
import it.sublima.zeroandroid.Richiesta;
import it.sublima.zeroandroid.ServerHttp;
import it.sublima.zeroandroid.Stampa;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Prova l'ascolto delle comande contro un finto Sublima, in-process.
 *
 * E' il test che conta di piu' del lotto: l'ascolto e' l'unica parte
 * dell'agente che parla con il server invece di rispondergli, e i modi in cui
 * puo' andare storto — una rete che cade in silenzio, un server che si riavvia,
 * un 503 che dice due cose diverse — non si vedono provando a mano.
 *
 * Il finto server e' scritto qui e non in Python, come gia' i finti registratori:
 * cosi' la batteria resta un solo `javac` e non serve nulla installato.
 *
 * Gira su JVM: nessuna rete vera, nessun dispositivo.
 */
public class TestSse {

    private static int passati = 0;
    private static int falliti = 0;
    // I nomi dei controlli caduti si ripetono in fondo: le prove qui dentro
    // parlano in rete e stampano molto, e un [NO ] a meta' pagina si perde.
    private static final List<String> caduti = new ArrayList<String>();

    private static FintoSublima server;

    public static void main(String[] args) throws Exception {
        server = new FintoSublima();
        server.avvia();
        System.out.println("Finto Sublima in ascolto sulla porta " + server.porta);
        System.out.println();

        try {
            leggeRighe();
            elencoMagazzini();
            configurazione();
            connessioneEBattiti();
            righeStrane();
            comandaStampataEConfermata();
            comandaGrande();
            stampaFallitaSiConfermaLoStesso();
            spentoDalServer();
            pausaDalServer();
            troppoRapidi();
            rispostaNonEUnFlusso();
            siRicollega();
            scalettaDelleAttese();
            riavvioNonEUnFallimento();
            unSoloLavoroDaDueStrade();
            daCapoAFondo();
        } finally {
            server.chiudi();
        }

        System.out.println();
        System.out.println("=================================================");
        for (String c : caduti) {
            System.out.println("CADUTO: " + c);
        }
        System.out.println("ESITO: " + passati + " superati, " + falliti + " falliti");
        System.out.println("=================================================");
        System.exit(falliti == 0 ? 0 : 1);
    }

    // ---- il lettore di righe ------------------------------------------------

    private static void leggeRighe() throws Exception {
        System.out.println("Il lettore di righe del flusso");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        InputStream in = flusso("uno\r\ndue\n\ntre");
        verifica("toglie il ritorno a capo di Windows",
                "uno".equals(ClienteSse.riga(in, buf)));
        verifica("legge la riga dopo", "due".equals(ClienteSse.riga(in, buf)));
        verifica("la riga vuota resta vuota", "".equals(ClienteSse.riga(in, buf)));
        verifica("l'ultima riga senza a-capo non si perde",
                "tre".equals(ClienteSse.riga(in, buf)));
        verifica("finito il flusso torna null", ClienteSse.riga(in, buf) == null);

        // Una riga `data:` porta uno ZIP intero in base64: se il lettore la
        // spezzasse, l'evento arriverebbe a meta' e non se ne accorgerebbe
        // nessuno finche' non manca una comanda.
        StringBuilder lunga = new StringBuilder();
        for (int i = 0; i < 300000; i++) {
            lunga.append('Q');
        }
        String letta = ClienteSse.riga(flusso(lunga + "\n"), buf);
        verifica("una riga da 300 mila caratteri arriva intera",
                letta != null && letta.length() == 300000);
        System.out.println();
    }

    // ---- l'elenco dei punti vendita -----------------------------------------

    private static void elencoMagazzini() throws Exception {
        System.out.println("L'elenco dei punti vendita, per sceglierli per nome");

        // La risposta vera di /pg/api/scontrino/get_magazzini porta l'intero
        // record: qui interessano due campi, e il resto deve poter cambiare
        // domani senza far cadere niente.
        String corpo = "{\"Result\":\"OK\",\"daos\":["
                + "{\"id\":4,\"denominazione\":\"VIA ROMA\",\"visibile\":true,\"sse\":null},"
                + "{\"id\":1,\"denominazione\":\"PRINCIPALE\",\"altro\":\"ignoto\"},"
                + "{\"id\":7,\"denominazione\":\"  BAR CENTRALE  \"}"
                + "],\"Message\":\"...\"}";
        java.util.List<Magazzini.Punto> punti = Magazzini.leggi(corpo);

        verifica("li legge tutti e tre", punti.size() == 3);
        // In ordine alfabetico: l'elenco si scorre con un dito, e l'ordine del
        // database non aiuta nessuno a trovare il proprio locale.
        verifica("in ordine alfabetico", punti.get(0).nome.equals("BAR CENTRALE")
                && punti.get(1).nome.equals("PRINCIPALE")
                && punti.get(2).nome.equals("VIA ROMA"));
        verifica("toglie gli spazi attorno al nome", punti.get(0).nome.equals("BAR CENTRALE"));
        // Un id che diventasse "4.0" non combacerebbe piu' con niente, e
        // l'ascolto si aggancerebbe a un magazzino che non esiste.
        verifica("l'id resta un numero pulito", punti.get(2).id.equals("4"));
        verifica("ritrova il nome dal numero",
                "PRINCIPALE".equals(Magazzini.nomeDi(punti, "1")));
        verifica("e non inventa nomi per numeri che non ci sono",
                "".equals(Magazzini.nomeDi(punti, "99")));

        java.util.List<Magazzini.Punto> senzaId = Magazzini.leggi(
                "{\"daos\":[{\"denominazione\":\"SENZA NUMERO\"},{\"id\":2}]}");
        verifica("scarta le voci senza numero", senzaId.size() == 1);
        verifica("e tiene quelle senza nome", senzaId.get(0).nome.equals("(senza nome)"));

        boolean caduto = false;
        try {
            Magazzini.leggi("{\"Result\":\"KO\"}");
        } catch (Exception e) {
            caduto = true;
        }
        verifica("una risposta senza elenco non passa in silenzio", caduto);
        System.out.println();
    }

    // ---- la configurazione --------------------------------------------------

    private static void configurazione() throws Exception {
        System.out.println("L'aggancio al profilo");
        Conf.usaDeposito(new Conf.InMemoria());

        verifica("senza indirizzo non si aggancia",
                Conf.aggancia("", "7", "LIC") != null);
        verifica("senza magazzino non si aggancia",
                Conf.aggancia("http://192.0.2.9:55224", "", "LIC") != null);
        verifica("un indirizzo senza schema si rifiuta",
                Conf.aggancia("192.0.2.9:55224", "7", "LIC") != null);

        // Il guasto piu' probabile in campo: il server non sa dire il proprio
        // indirizzo e manda localhost. Dal tablet non porta da nessuna parte, e
        // accettarlo si manifesterebbe come "l'ascolto non funziona".
        String cieco = Conf.aggancia("http://127.0.0.1:55224", "7", "LIC");
        verifica("localhost dall'aggancio automatico si rifiuta", cieco != null);
        verifica("e si dice perche'", cieco != null && cieco.contains("dispositivo"));
        verifica("scritto a mano invece si accetta",
                Conf.aggancia("http://127.0.0.1:55224", "7", "LIC", true) == null);

        verifica("un indirizzo buono si aggancia",
                Conf.aggancia("http://192.0.2.9:55224/", "7", "LIC") == null);
        verifica("la barra finale sparisce",
                "http://192.0.2.9:55224".equals(Conf.urlProfilo()));
        verifica("il magazzino e' ricordato", "7".equals(Conf.idMagazzino()));
        verifica("agganciato", Conf.agganciato());

        // ⚠️ Agganciato NON vuol dire in ascolto, e il difetto e' spento apposta:
        // l'ascolto serve a una installazione su dieci, e sulle altre nove una
        // connessione tentata riuscirebbe e terrebbe occupato per sempre uno dei
        // sei thread di uWSGI del profilo per non consegnare niente.
        verifica("ma l'ascolto nasce SPENTO", !Conf.sseAttivo());
        verifica("e l'interruttore lo dice", !Conf.acceso());

        Conf.accendi(true);
        verifica("acceso, allora ascolta", Conf.sseAttivo());
        Conf.accendi(false);
        verifica("e si rispegne", !Conf.sseAttivo());

        Conf.sgancia();
        verifica("sganciato", !Conf.agganciato());
        System.out.println();
    }

    // ---- connessione e battiti ----------------------------------------------

    private static void connessioneEBattiti() throws Exception {
        System.out.println("Connessione, presentazione e battiti");
        preparaConf();
        server.copione(risposta(
                "event: connected\ndata: {\"status\":\"ok\"}\n\n",
                "event: keepalive\ndata: {\"timestamp\":1}\n\n",
                "event: keepalive\ndata: {\"timestamp\":2}\n\n",
                "event: keepalive\ndata: {\"timestamp\":3}\n\n"));

        Registratrice stampe = new Registratrice("{\"Result\":\"OK\"}");
        ClienteSse cliente = accendi(stampe);
        try {
            verifica("si collega", attendi(new Condizione() {
                public boolean vera() {
                    return ClienteSse.collegato();
                }
            }, 4000));
            verifica("conta i battiti", attendi(new Condizione() {
                public boolean vera() {
                    return ClienteSse.battiti() >= 3;
                }
            }, 4000));

            String query = server.ultimaQuery;
            verifica("manda il magazzino", query != null && query.contains("id_mag=7"));
            verifica("manda la licenza", query != null && query.contains("zero_licenza=LIC7"));
            String teste = server.ultimeIntestazioni.toLowerCase();
            verifica("chiede un flusso di eventi", teste.contains("accept: text/event-stream"));
            // Chiedere identity toglie di mezzo ogni sorpresa di
            // bufferizzazione fra la compressione e i byte dell'evento.
            verifica("chiede i byte non compressi", teste.contains("accept-encoding: identity"));
        } finally {
            spegni(cliente);
        }
        System.out.println();
    }

    private static void righeStrane() throws Exception {
        System.out.println("Righe di servizio e dati su piu' righe");
        preparaConf();
        server.copione(risposta(
                ": questo e' un commento e va ignorato\n\n",
                "event: qualcosa_di_nuovo\ndata: {\"boh\":1}\n\n",
                // Lo standard permette di spezzare i dati su piu' righe: oggi
                // Sublima non lo fa, ma un client che non le riunisse
                // fallirebbe in silenzio il giorno che cominciasse.
                "event: print_action\ndata: {\"filescontrino\":\"spezzato.zip\",\n"
                        + "data: \"corpo_scontrino\":\"QUJD\"}\n\n"));

        Registratrice stampe = new Registratrice("{\"Result\":\"OK\"}");
        ClienteSse cliente = accendi(stampe);
        try {
            verifica("il commento non fa cadere il collegamento",
                    attendi(unaStampa(stampe), 4000));
            verifica("i dati spezzati si riuniscono",
                    stampe.ultimoDato != null
                            && stampe.ultimoDato.contains("\"corpo_scontrino\":\"QUJD\""));
            verifica("un evento sconosciuto non arriva mai in stampa",
                    stampe.maiVisto("boh"));
        } finally {
            spegni(cliente);
        }
        System.out.println();
    }

    // ---- il giro completo di una comanda ------------------------------------

    private static void comandaStampataEConfermata() throws Exception {
        System.out.println("Una comanda: stampata e confermata");
        preparaConf();
        server.copione(risposta(
                "id: ab12\nevent: print_action\ndata: {\"type\":\"print_action\","
                        + "\"filescontrino\":\"comanda1.zip\","
                        + "\"corpo_scontrino\":\"UEsDBA==\",\"id_pos\":3}\n\n"));

        Registratrice stampe = new Registratrice(
                "{\"Result\":\"OK\",\"Message\":\"Tutte le stampanti\"}");
        ClienteSse cliente = accendi(stampe);
        try {
            verifica("la stampa parte", attendi(unaStampa(stampe), 4000));
            verifica("le arriva il messaggio intero",
                    stampe.ultimoDato != null
                            && stampe.ultimoDato.contains("comanda1.zip")
                            && stampe.ultimoDato.contains("UEsDBA=="));
            // Con l'ascolto l'indirizzo di Sublima e' noto: e' quello che
            // permette di restituire il numero dello scontrino fiscale, cosa
            // che dalla spinta del server non si poteva fare.
            verifica("l'origine e' il profilo agganciato",
                    Conf.urlProfilo().equals(stampe.ultimaOrigine));

            verifica("l'esito torna a Sublima", attendi(new Condizione() {
                public boolean vera() {
                    return server.quantiAck >= 1;
                }
            }, 4000));
            String ack = server.ultimoAck;
            verifica("con il nome del lavoro", ack.contains("filescontrino=comanda1.zip"));
            verifica("con l'esito in chiaro", ack.contains("status=OK"));
            verifica("con la licenza", ack.contains("zero_licenza=LIC7"));
            // Il server fa json.loads su questo campo: deve essere il JSON
            // intero, non un riassunto.
            verifica("e con il risultato per intero",
                    ack.contains("print_result=") && ack.contains("Tutte"));
        } finally {
            spegni(cliente);
        }
        System.out.println();
    }

    private static void comandaGrande() throws Exception {
        System.out.println("Una comanda da un megabyte su una riga sola");
        preparaConf();
        StringBuilder grosso = new StringBuilder(1024 * 1024);
        for (int i = 0; i < 1024 * 1024; i++) {
            grosso.append('A');
        }
        server.copione(risposta("event: print_action\ndata: {\"filescontrino\":\"grossa.zip\","
                + "\"corpo_scontrino\":\"" + grosso + "\"}\n\n"));

        final Registratrice stampe = new Registratrice("{\"Result\":\"OK\"}");
        ClienteSse cliente = accendi(stampe);
        try {
            verifica("arriva", attendi(unaStampa(stampe), 8000));
            verifica("e arriva intera",
                    stampe.ultimoDato != null
                            && stampe.ultimoDato.contains("\"" + grosso.substring(0, 64)));
            verifica("senza perdere un byte",
                    stampe.ultimoDato != null
                            && stampe.ultimoDato.length() > 1024 * 1024);
        } finally {
            spegni(cliente);
        }
        System.out.println();
    }

    private static void stampaFallitaSiConfermaLoStesso() throws Exception {
        System.out.println("Una stampa fallita si confessa");
        preparaConf();
        server.copione(risposta("event: print_action\ndata: {\"filescontrino\":\"rotta.zip\","
                + "\"corpo_scontrino\":\"QQ==\"}\n\n"));

        Registratrice stampe = new Registratrice("{\"Result\":\"KO\",\"Message\":\"spenta\"}");
        ClienteSse cliente = accendi(stampe);
        try {
            // Tacere qui lascerebbe il browser ad aspettare un esito che non
            // arriva, e lo ZIP sul server per sempre.
            verifica("l'esito negativo viene comunque mandato", attendi(new Condizione() {
                public boolean vera() {
                    return server.quantiAck >= 1;
                }
            }, 4000));
            verifica("e dice KO", server.ultimoAck.contains("status=KO"));
        } finally {
            spegni(cliente);
        }
        System.out.println();
    }

    // ---- i rifiuti del server -----------------------------------------------

    private static void spentoDalServer() throws Exception {
        System.out.println("503 con Result KO: spento per sempre");
        preparaConf();
        server.copione(rifiuto(503,
                "{\"Result\":\"KO\",\"Message\":\"SSE disattivato - Attivare da Setup\"}"));

        Registratrice stampe = new Registratrice("{\"Result\":\"OK\"}");
        ClienteSse cliente = accendi(stampe);
        try {
            verifica("smette di ascoltare", attendi(new Condizione() {
                public boolean vera() {
                    return !Conf.sseAttivo();
                }
            }, 4000));
            int quante = server.connessioni;
            Thread.sleep(400);
            // Insistere su un server che ha detto basta e' solo rumore nei suoi
            // log, e da li' nascono le segnalazioni sbagliate.
            verifica("e non riprova", server.connessioni <= quante + 1);
            verifica("lo dice", ClienteSse.descrizione().contains("Spento da Sublima"));
        } finally {
            spegni(cliente);
        }
        System.out.println();
    }

    private static void pausaDalServer() throws Exception {
        System.out.println("503 con Result PAUSED: aspetta quanto gli si dice");
        preparaConf();
        server.copione(rifiuto(503,
                "{\"Result\":\"PAUSED\",\"pause_seconds\":7,\"Message\":\"moduli spenti\"}"));

        Registratrice stampe = new Registratrice("{\"Result\":\"OK\"}");
        final Orologio orologio = new Orologio();
        ClienteSse cliente = new ClienteSse(stampe, orologio);
        cliente.avvia();
        try {
            verifica("aspetta i secondi chiesti", attendi(new Condizione() {
                public boolean vera() {
                    return orologio.attese.contains(7000L);
                }
            }, 4000));
            // Confondere PAUSED con KO spegnerebbe l'ascolto di un impianto che
            // stava solo aspettando che riaccendessero un modulo.
            verifica("ma non si spegne", Conf.sseAttivo());
        } finally {
            spegni(cliente);
        }
        System.out.println();
    }

    private static void troppoRapidi() throws Exception {
        System.out.println("429: ci siamo riconnessi troppo in fretta");
        preparaConf();
        server.copione(rifiuto(429,
                "{\"Result\":\"KO\",\"Message\":\"Riconnessione troppo rapida, attendi\"}"));

        Registratrice stampe = new Registratrice("{\"Result\":\"OK\"}");
        final Orologio orologio = new Orologio();
        ClienteSse cliente = new ClienteSse(stampe, orologio);
        cliente.avvia();
        try {
            verifica("riprova invece di arrendersi", attendi(new Condizione() {
                public boolean vera() {
                    return server.connessioni >= 2;
                }
            }, 4000));
            verifica("non si spegne", Conf.sseAttivo());
            verifica("e lo scrive come difetto nostro",
                    ClienteSse.descrizione().contains("429"));
            // Sotto i tre secondi Sublima mette alla porta: un ciclo impaziente
            // si auto-bandisce e sembra un guasto del server.
            verifica("mai una attesa sotto i tre secondi",
                    orologio.minima() >= 3000);
        } finally {
            spegni(cliente);
        }
        System.out.println();
    }

    private static void rispostaNonEUnFlusso() throws Exception {
        System.out.println("Risposta 200 ma non e' un flusso di eventi");
        preparaConf();
        server.copione(paginaQualunque());

        Registratrice stampe = new Registratrice("{\"Result\":\"OK\"}");
        ClienteSse cliente = accendi(stampe);
        try {
            // Succede con un proxy o un portale captive di mezzo: senza questo
            // controllo si proverebbe a stampare una pagina di errore HTML.
            verifica("se ne accorge", attendi(new Condizione() {
                public boolean vera() {
                    return ClienteSse.descrizione().contains("flusso di eventi");
                }
            }, 4000));
            verifica("e non stampa niente", stampe.quante == 0);
        } finally {
            spegni(cliente);
        }
        System.out.println();
    }

    // ---- riconnessione ------------------------------------------------------

    private static void siRicollega() throws Exception {
        System.out.println("Il collegamento cade: si ricollega da solo");
        preparaConf();
        server.copione(cade("event: connected\ndata: {}\n\n"));

        Registratrice stampe = new Registratrice("{\"Result\":\"OK\"}");
        final Orologio orologio = new Orologio();
        ClienteSse cliente = new ClienteSse(stampe, orologio);
        cliente.avvia();
        try {
            verifica("riprova senza che nessuno intervenga", attendi(new Condizione() {
                public boolean vera() {
                    return server.connessioni >= 3;
                }
            }, 6000));
        } finally {
            spegni(cliente);
        }
        System.out.println();
    }

    private static void scalettaDelleAttese() throws Exception {
        System.out.println("La scaletta delle attese fra un tentativo e l'altro");
        preparaConf();
        server.copione(cade("event: connected\ndata: {}\n\n"));

        Registratrice stampe = new Registratrice("{\"Result\":\"OK\"}");
        final Orologio orologio = new Orologio();
        ClienteSse cliente = new ClienteSse(stampe, orologio);
        cliente.avvia();
        try {
            attendi(new Condizione() {
                public boolean vera() {
                    return orologio.attese.size() >= 4;
                }
            }, 8000);
            List<Long> a = orologio.copia();
            // Raddoppia fino a un minuto: un impianto senza rete non deve
            // martellare, ma nemmeno restare giu' mezz'ora per un riavvio.
            verifica("comincia da cinque secondi", a.size() > 0 && a.get(0) == 5000L);
            verifica("poi dieci", a.size() > 1 && a.get(1) == 10000L);
            verifica("poi venti", a.size() > 2 && a.get(2) == 20000L);
            verifica("poi quaranta", a.size() > 3 && a.get(3) == 40000L);
            verifica("mai sotto i tre secondi", orologio.minima() >= 3000);
        } finally {
            spegni(cliente);
        }
        System.out.println();
    }

    private static void riavvioNonEUnFallimento() throws Exception {
        System.out.println("Un riavvio del server non e' un guasto");
        preparaConf();
        server.copione(cade("event: server_shutdown\ndata: {\"reason\":\"restart\"}\n\n"));

        Registratrice stampe = new Registratrice("{\"Result\":\"OK\"}");
        final Orologio orologio = new Orologio();
        ClienteSse cliente = new ClienteSse(stampe, orologio);
        cliente.avvia();
        try {
            attendi(new Condizione() {
                public boolean vera() {
                    return orologio.attese.size() >= 4;
                }
            }, 8000);
            // Se contasse come fallimento, un riavvio di Sublima manderebbe
            // tutti i tramiti in attesa crescente proprio mentre torna su.
            boolean sempreCinque = true;
            for (Long v : orologio.copia()) {
                if (v != 5000L) {
                    sempreCinque = false;
                }
            }
            verifica("l'attesa non cresce mai", sempreCinque);
        } finally {
            spegni(cliente);
        }
        System.out.println();
    }

    // ---- i due ingressi -----------------------------------------------------

    private static void unSoloLavoroDaDueStrade() throws Exception {
        System.out.println("Lo stesso lavoro dall'ascolto e dal browser");
        preparaConf();
        // Un pacchetto vero: la deduplicazione si segna dopo aver aperto lo ZIP,
        // quindi con un archivio finto non scatterebbe e il test direbbe il
        // falso. La stampa poi fallisce (nessuna stampante), e va bene: quel che
        // si prova qui e' che il secondo arrivo non riparta.
        String dati = caso("lavoro_byte").trim().replace("\n", "");
        server.copione(risposta("event: print_action\ndata: " + dati + "\n\n"));

        // Un solo oggetto Stampa per i due ingressi: e' la ragione per cui la
        // stessa comanda consegnata da tutt'e due le strade esce una volta sola.
        Contatore stampa = new Contatore();
        ClienteSse cliente = accendi(stampa);
        try {
            verifica("arriva dall'ascolto", attendi(new Condizione() {
                public boolean vera() {
                    return stampa.quante >= 1;
                }
            }, 4000));
            String seconda = stampa.esegui(Richiesta.sintetica(dati, Conf.urlProfilo()));
            verifica("la seconda copia non si stampa", stampa.quante == 1);
            verifica("e lo dice", seconda.contains("dedup"));
        } finally {
            spegni(cliente);
        }
        System.out.println();
    }

    // ---- da capo a fondo ----------------------------------------------------

    private static void daCapoAFondo() throws Exception {
        System.out.println("Da capo a fondo: evento vero, stampante vera, esito vero");
        preparaConf();

        ServerSocket stampante = new ServerSocket(0, 50,
                java.net.InetAddress.getByName("127.0.0.1"));
        final int portaStampante = stampante.getLocalPort();
        final byte[][] ricevuto = new byte[1][];
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Socket c = stampante.accept();
                    c.setSoTimeout(1500);
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    try {
                        while ((n = c.getInputStream().read(b)) > 0) {
                            buf.write(b, 0, n);
                        }
                    } catch (Exception e) {
                        // il mittente ha chiuso: quel che e' arrivato basta
                    }
                    ricevuto[0] = buf.toByteArray();
                    c.close();
                } catch (Exception e) {
                    // il server chiude a fine prova
                }
            }
        });
        t.setDaemon(true);
        t.start();

        // Il pacchetto e' quello vero, costruito da test/genera_pacchetti.py con
        // gli stessi zipfile e pickle di Sublima. E notare cosa NON c'e'
        // nell'evento: il campo `tipo`. Il percorso di stampa deve reggerlo.
        String dati = caso("lavoro_byte").trim().replace("\n", "");
        server.copione(risposta("event: print_action\ndata: " + dati + "\n\n"));

        ClienteSse cliente = accendi(new Stampa(portaStampante));
        try {
            verifica("la stampante riceve", attendi(new Condizione() {
                public boolean vera() {
                    return ricevuto[0] != null && ricevuto[0].length > 0;
                }
            }, 6000));
            verifica("l'esito torna a Sublima", attendi(new Condizione() {
                public boolean vera() {
                    return server.quantiAck >= 1;
                }
            }, 4000));
            verifica("ed e' un OK", server.ultimoAck.contains("status=OK"));
        } finally {
            spegni(cliente);
            stampante.close();
        }
        System.out.println();
    }

    // ---- appoggio -----------------------------------------------------------

    private interface Condizione {
        boolean vera();
    }

    /** Chi stampa, nei test: registra e restituisce l'esito che gli si dice. */
    private static final class Registratrice implements ServerHttp.Stampe {
        private final String esito;
        volatile int quante;
        volatile String ultimoDato;
        volatile String ultimaOrigine;
        final List<String> ricevuti = Collections.synchronizedList(new ArrayList<String>());

        Registratrice(String esito) {
            this.esito = esito;
        }

        public String esegui(Richiesta richiesta) {
            ultimoDato = richiesta.campo("data");
            ultimaOrigine = richiesta.intestazione("origin");
            ricevuti.add(ultimoDato == null ? "" : ultimoDato);
            quante++;
            return esito;
        }

        /** Vero se NESSUNA delle stampe chieste conteneva quel testo. */
        boolean maiVisto(String pezzo) {
            synchronized (ricevuti) {
                for (String r : ricevuti) {
                    if (r.contains(pezzo)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /** Come sopra, ma con la deduplicazione vera dello Stampa in mezzo. */
    private static final class Contatore implements ServerHttp.Stampe {
        private final Stampa vero = new Stampa(1);
        volatile int quante;

        public String esegui(Richiesta richiesta) {
            String r = vero.esegui(richiesta);
            if (!r.contains("dedup")) {
                quante++;
            }
            return r;
        }
    }

    /** L'attesa finta: registra quanto le si chiede e non dorme davvero. */
    private static final class Orologio implements ClienteSse.Pausa {
        final List<Long> attese = Collections.synchronizedList(new ArrayList<Long>());

        public void dormi(long millisecondi) {
            attese.add(millisecondi);
        }

        List<Long> copia() {
            synchronized (attese) {
                return new ArrayList<Long>(attese);
            }
        }

        long minima() {
            long m = Long.MAX_VALUE;
            for (Long v : copia()) {
                m = Math.min(m, v);
            }
            return (m == Long.MAX_VALUE) ? 3000 : m;
        }
    }

    private static Condizione unaStampa(final Registratrice r) {
        return new Condizione() {
            public boolean vera() {
                return r.quante >= 1;
            }
        };
    }

    private static void preparaConf() {
        Conf.usaDeposito(new Conf.InMemoria());
        // Forzato: il finto server sta su 127.0.0.1, che dall'aggancio
        // automatico si rifiuta apposta.
        Conf.aggancia("http://127.0.0.1:" + server.porta, "7", "LIC7", true);
        // Acceso a mano: il difetto e' spento, e senza questo tutte le prove
        // qui sotto guarderebbero un client che non si collega mai.
        Conf.accendi(true);
        server.azzera();
    }

    private static ClienteSse accendi(ServerHttp.Stampe stampe) {
        ClienteSse c = new ClienteSse(stampe, new Orologio());
        c.avvia();
        return c;
    }

    private static void spegni(ClienteSse c) throws Exception {
        c.ferma();
        Thread.sleep(120);
    }

    private static boolean attendi(Condizione c, long millisecondi) throws Exception {
        long fine = System.currentTimeMillis() + millisecondi;
        while (System.currentTimeMillis() < fine) {
            if (c.vera()) {
                return true;
            }
            Thread.sleep(20);
        }
        return c.vera();
    }

    private static InputStream flusso(String testo) throws Exception {
        return new java.io.ByteArrayInputStream(testo.getBytes("UTF-8"));
    }

    private static String caso(String nome) throws Exception {
        File f = new File("test/casi/" + nome + ".data");
        if (!f.exists()) {
            System.out.println("Manca " + f.getPath()
                    + ": lancia prima  python3 test/genera_pacchetti.py");
            System.exit(2);
        }
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void verifica(String cosa, boolean condizione) {
        if (condizione) {
            passati++;
            System.out.println("  [OK ] " + cosa);
        } else {
            falliti++;
            caduti.add(cosa);
            System.out.println("  [NO ] " + cosa);
        }
    }

    // ---- il finto Sublima ---------------------------------------------------

    /** Cosa il finto server deve rispondere alla prossima connessione. */
    private static final class Copione {
        int codice = 200;
        String tipo = "text/event-stream";
        String corpo = "";
        String[] eventi = new String[0];
        /** Quanto tiene aperto dopo l'ultimo evento, come farebbe un server vero. */
        long attesaFinale = 1500;
    }

    /**
     * Manda gli eventi e poi resta aperto un momento.
     *
     * Il momento serve: chiudendo subito, il client si ricollega e riceve tutto
     * una seconda volta, e le verifiche che contano quante volte e' successo
     * qualcosa diventerebbero una lotteria.
     */
    private static Copione risposta(String... eventi) {
        Copione c = new Copione();
        c.eventi = eventi;
        return c;
    }

    /** Come sopra, ma chiude subito: e' cosi' che si prova la riconnessione. */
    private static Copione cade(String... eventi) {
        Copione c = risposta(eventi);
        c.attesaFinale = 0;
        return c;
    }

    private static Copione rifiuto(int codice, String corpo) {
        Copione c = new Copione();
        c.codice = codice;
        c.tipo = "application/json";
        c.corpo = corpo;
        return c;
    }

    private static Copione paginaQualunque() {
        Copione c = new Copione();
        c.tipo = "text/html; charset=utf-8";
        c.corpo = "<html><body>portale della rete</body></html>";
        return c;
    }

    /**
     * Un Sublima quel tanto che basta: risponde al flusso e raccoglie gli esiti.
     *
     * Risponde in chunked come quello vero, perche' e' li' che una libreria HTTP
     * puo' decidere di bufferizzare e far arrivare gli eventi a mazzi invece che
     * quando succedono.
     */
    private static final class FintoSublima {
        ServerSocket socket;
        int porta;
        volatile Copione prossimo = new Copione();
        volatile int connessioni;
        volatile int quantiAck;
        volatile String ultimaQuery = "";
        volatile String ultimeIntestazioni = "";
        volatile String ultimoAck = "";

        void avvia() throws Exception {
            socket = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
            porta = socket.getLocalPort();
            Thread t = new Thread(new Runnable() {
                public void run() {
                    while (!socket.isClosed()) {
                        try {
                            final Socket c = socket.accept();
                            Thread u = new Thread(new Runnable() {
                                public void run() {
                                    try {
                                        servi(c);
                                    } catch (Exception e) {
                                        // il cliente se n'e' andato
                                    } finally {
                                        try {
                                            c.close();
                                        } catch (Exception e) {
                                            // niente
                                        }
                                    }
                                }
                            });
                            u.setDaemon(true);
                            u.start();
                        } catch (Exception e) {
                            return;
                        }
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        void copione(Copione c) {
            this.prossimo = c;
        }

        void azzera() {
            connessioni = 0;
            quantiAck = 0;
            ultimoAck = "";
            ultimaQuery = "";
            ultimeIntestazioni = "";
        }

        void chiudi() throws Exception {
            socket.close();
        }

        private void servi(Socket c) throws Exception {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();

            String prima = riga(in);
            if (prima == null) {
                return;
            }
            StringBuilder teste = new StringBuilder();
            int lunghezza = 0;
            String h;
            while ((h = riga(in)) != null && !h.isEmpty()) {
                teste.append(h).append('\n');
                if (h.toLowerCase().startsWith("content-length:")) {
                    lunghezza = Integer.parseInt(h.substring(15).trim());
                }
            }

            if (prima.startsWith("POST /pg/api/zero/ack_print")) {
                byte[] corpo = new byte[lunghezza];
                int letti = 0;
                while (letti < lunghezza) {
                    int n = in.read(corpo, letti, lunghezza - letti);
                    if (n < 0) {
                        break;
                    }
                    letti += n;
                }
                ultimoAck = java.net.URLDecoder.decode(
                        new String(corpo, 0, letti, "UTF-8"), "UTF-8");
                quantiAck++;
                testo(out, 200, "application/json", "{\"Result\":\"OK\"}");
                return;
            }

            connessioni++;
            ultimeIntestazioni = teste.toString();
            int domanda = prima.indexOf('?');
            int spazio = prima.indexOf(' ', domanda < 0 ? 0 : domanda);
            ultimaQuery = (domanda < 0) ? ""
                    : prima.substring(domanda + 1, spazio < 0 ? prima.length() : spazio);

            Copione atteso = prossimo;
            if (atteso.codice != 200) {
                testo(out, atteso.codice, atteso.tipo, atteso.corpo);
                return;
            }
            if (!atteso.tipo.startsWith("text/event-stream")) {
                testo(out, 200, atteso.tipo, atteso.corpo);
                return;
            }

            StringBuilder testa = new StringBuilder();
            testa.append("HTTP/1.1 200 OK\r\n");
            testa.append("Content-Type: text/event-stream\r\n");
            testa.append("Cache-Control: no-cache\r\n");
            testa.append("X-Accel-Buffering: no\r\n");
            testa.append("Transfer-Encoding: chunked\r\n\r\n");
            out.write(testa.toString().getBytes("UTF-8"));
            out.flush();

            for (String evento : atteso.eventi) {
                pezzo(out, evento);
            }
            if (atteso.attesaFinale > 0) {
                Thread.sleep(atteso.attesaFinale);
            }
            out.write("0\r\n\r\n".getBytes("UTF-8"));
            out.flush();
        }

        private void pezzo(OutputStream out, String s) throws Exception {
            byte[] b = s.getBytes("UTF-8");
            out.write((Integer.toHexString(b.length) + "\r\n").getBytes("UTF-8"));
            out.write(b);
            out.write("\r\n".getBytes("UTF-8"));
            out.flush();
        }

        private void testo(OutputStream out, int codice, String tipo, String corpo)
                throws Exception {
            byte[] b = corpo.getBytes("UTF-8");
            StringBuilder testa = new StringBuilder();
            testa.append("HTTP/1.1 ").append(codice).append(" X\r\n");
            testa.append("Content-Type: ").append(tipo).append("\r\n");
            testa.append("Content-Length: ").append(b.length).append("\r\n");
            testa.append("Connection: close\r\n\r\n");
            out.write(testa.toString().getBytes("UTF-8"));
            out.write(b);
            out.flush();
        }

        private String riga(InputStream in) throws Exception {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    byte[] d = buf.toByteArray();
                    int n = d.length;
                    if (n > 0 && d[n - 1] == '\r') {
                        n--;
                    }
                    return new String(d, 0, n, "UTF-8");
                }
                buf.write(b);
            }
            return (buf.size() > 0) ? new String(buf.toByteArray(), "UTF-8") : null;
        }
    }
}
