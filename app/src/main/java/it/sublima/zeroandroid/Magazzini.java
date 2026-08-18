package it.sublima.zeroandroid;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * L'elenco dei punti vendita, chiesto a Sublima.
 *
 * Serve a una cosa sola: far scegliere il magazzino **per nome** invece che per
 * numero. L'ascolto delle comande si instrada su `id_mag`, che e' un numero, e
 * chiedere a chi installa di sapere a memoria che il locale di via Roma e' il 4
 * e' un modo eccellente di agganciare il tablet al punto vendita sbagliato — e
 * di far uscire le comande di un locale nella cucina di un altro.
 *
 * L'indirizzo e' lo stesso che usa lo Zero Python per la sua tendina, e non
 * chiede autenticazione: e' dichiarato aperto in `apirest.py`, dove sta scritto
 * che resta vivo proprio perche' lo Zero lo chiama.
 *
 * ⚠️ Nessun import di Android: la lettura della risposta si prova su JVM.
 */
public final class Magazzini {

    public static final String PERCORSO = "/pg/api/scontrino/get_magazzini";
    public static final int ATTESA = 8000;

    private Magazzini() {
    }

    /** Un punto vendita: il numero che serve al protocollo, il nome che serve a chi guarda. */
    public static final class Punto {
        public final String id;
        public final String nome;

        Punto(String id, String nome) {
            this.id = id;
            this.nome = nome;
        }

        @Override
        public String toString() {
            return nome + "  (n. " + id + ")";
        }
    }

    /**
     * Chiede l'elenco a Sublima. Solleva un'eccezione col motivo se non riesce.
     *
     * Chi chiama deve farlo fuori dal filo dell'interfaccia: su Android la rete
     * sul filo principale non e' lenta, e' vietata.
     */
    public static List<Punto> chiedi(String indirizzo) throws Exception {
        String pulito = Conf.senzaBarraFinale(indirizzo);
        if (pulito.isEmpty()) {
            throw new Exception("manca l'indirizzo di Sublima");
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(pulito + PERCORSO).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(ATTESA);
            c.setReadTimeout(ATTESA);
            c.setRequestProperty("User-Agent", Versione.NOME + "/" + Versione.NUMERO);
            int codice = c.getResponseCode();
            if (codice < 200 || codice >= 300) {
                throw new Exception("Sublima ha risposto " + codice);
            }
            return leggi(testo(c.getInputStream()));
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /**
     * Ricava l'elenco dalla risposta. Separata dalla rete apposta, per provarla.
     *
     * La risposta porta l'intero record del magazzino: qui interessano due campi
     * e tutto il resto si ignora, cosi' un campo aggiunto domani lato Sublima non
     * fa cadere niente.
     */
    public static List<Punto> leggi(String corpo) throws Exception {
        Map<String, Object> risposta = JsonLettore.oggetto(corpo);
        Object daos = risposta.get("daos");
        if (!(daos instanceof List)) {
            throw new Exception("risposta senza elenco dei magazzini");
        }
        List<Punto> punti = new ArrayList<Punto>();
        for (Object voce : (List<?>) daos) {
            if (!(voce instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) voce;
            String id = ripulisci(JsonLettore.testo(m, "id", ""));
            String nome = JsonLettore.testo(m, "denominazione", "").trim();
            if (id.isEmpty()) {
                continue;
            }
            punti.add(new Punto(id, nome.isEmpty() ? "(senza nome)" : nome));
        }
        // In ordine alfabetico: l'elenco si scorre con un dito su un tablet, e
        // l'ordine di inserimento nel database non aiuta nessuno a trovare.
        Collections.sort(punti, new Comparator<Punto>() {
            public int compare(Punto a, Punto b) {
                return a.nome.compareToIgnoreCase(b.nome);
            }
        });
        return punti;
    }

    /** Il nome del punto vendita con quel numero, o stringa vuota. */
    public static String nomeDi(List<Punto> punti, String id) {
        if (punti == null || id == null) {
            return "";
        }
        for (Punto p : punti) {
            if (p.id.equals(id.trim())) {
                return p.nome;
            }
        }
        return "";
    }

    /**
     * Gli id arrivano come numeri JSON: 4 diventa "4", ma 4.0 diventerebbe
     * "4.0" e non combacerebbe piu' con niente.
     */
    private static String ripulisci(String id) {
        String v = (id == null) ? "" : id.trim();
        if (v.endsWith(".0")) {
            v = v.substring(0, v.length() - 2);
        }
        return v;
    }

    private static String testo(InputStream in) throws Exception {
        ByteArrayOutputStream fuori = new ByteArrayOutputStream();
        byte[] pezzo = new byte[8192];
        int letti;
        while ((letti = in.read(pezzo)) > 0) {
            fuori.write(pezzo, 0, letti);
        }
        return new String(fuori.toByteArray(), "UTF-8");
    }
}
