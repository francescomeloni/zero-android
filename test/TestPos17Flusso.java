import it.sublima.zeroandroid.Pos17;
import it.sublima.zeroandroid.Pos17Conversazione;
import it.sublima.zeroandroid.Pos17Conversazione.Lettore;
import it.sublima.zeroandroid.Pos17Conversazione.Messaggio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Verifica il riassemblaggio del flusso e i riscontri.
 *
 * Sono i casi in cui questo protocollo ha gia' fatto perdere denaro: ACK ed
 * esito arrivati insieme, frame spezzato a meta', messaggi di stato in mezzo,
 * ritrasmissioni. Nessuna rete, nessun terminale.
 */
public class TestPos17Flusso {

    private static int passati = 0;
    private static int falliti = 0;

    private static final String ESITO_OK =
            "999999990E00" + "000************0000" + "ICC" + "000000"
            + "0152050" + "2" + "00000100004" + "000002" + "000101";

    public static void main(String[] args) throws Exception {
        unoPerVolta();
        tuttoInsieme();
        spezzato();
        aGoccia();
        statoInMezzo();
        lrcGuasto();
        spazzatura();
        riscontri();
        nakEChiusura();

        System.out.println();
        System.out.println("=================================================");
        System.out.println("ESITO: " + passati + " superati, " + falliti + " falliti");
        System.out.println("=================================================");
        System.exit(falliti == 0 ? 0 : 1);
    }

    private static void unoPerVolta() {
        System.out.println("Il caso normale: un messaggio per lettura");
        System.out.println("----------------------------------------");
        Lettore l = new Lettore();
        l.aggiungi(Pos17.ACK, Pos17.ACK.length);
        List<Messaggio> m = l.messaggi();
        verifica("l'ACK viene riconosciuto", m.size() == 1
                && Pos17Conversazione.TIPO_ACK.equals(m.get(0).tipo));

        byte[] esito = Pos17.incapsula(ESITO_OK);
        l.aggiungi(esito, esito.length);
        m = l.messaggi();
        verifica("l'esito viene riconosciuto", m.size() == 1
                && Pos17Conversazione.TIPO_FRAME.equals(m.get(0).tipo));
        verifica("il LRC risulta giusto", m.get(0).lrcOk);
        verifica("il payload e' quello mandato", ESITO_OK.equals(m.get(0).payload));
        System.out.println();
    }

    private static void tuttoInsieme() {
        System.out.println("ACK ed esito nella STESSA lettura");
        System.out.println("---------------------------------");
        // ⚠️ E' il caso dell'incidente: prima vinceva il test dell'ACK e l'esito
        // veniva buttato via. Un pagamento incassato risultava fallito.
        byte[] esito = Pos17.incapsula(ESITO_OK);
        byte[] insieme = unisci(Pos17.ACK, esito);
        Lettore l = new Lettore();
        l.aggiungi(insieme, insieme.length);
        List<Messaggio> m = l.messaggi();
        verifica("si trovano DUE messaggi, non uno", m.size() == 2);
        verifica("il primo e' l'ACK",
                Pos17Conversazione.TIPO_ACK.equals(m.get(0).tipo));
        verifica("il secondo e' l'esito, e non va perso",
                Pos17Conversazione.TIPO_FRAME.equals(m.get(1).tipo));
        verifica("l'esito e' integro", ESITO_OK.equals(m.get(1).payload));
        System.out.println();
    }

    private static void spezzato() {
        System.out.println("Frame spezzato fra due letture");
        System.out.println("------------------------------");
        byte[] esito = Pos17.incapsula(ESITO_OK);
        int meta = esito.length / 2;
        Lettore l = new Lettore();
        l.aggiungi(primi(esito, meta), meta);
        verifica("con meta' frame non si conclude niente", l.messaggi().isEmpty());
        l.aggiungi(resto(esito, meta), esito.length - meta);
        List<Messaggio> m = l.messaggi();
        verifica("arrivata la seconda meta', il frame si ricompone", m.size() == 1);
        verifica("ed e' integro", ESITO_OK.equals(m.get(0).payload));
        System.out.println();
    }

    private static void aGoccia() {
        System.out.println("Un byte per volta");
        System.out.println("-----------------");
        byte[] esito = Pos17.incapsula(ESITO_OK);
        Lettore l = new Lettore();
        int trovatiPrima = 0;
        for (int i = 0; i < esito.length - 1; i++) {
            l.aggiungi(new byte[]{esito[i]}, 1);
            trovatiPrima += l.messaggi().size();
        }
        verifica("prima dell'ultimo byte non si conclude niente", trovatiPrima == 0);
        l.aggiungi(new byte[]{esito[esito.length - 1]}, 1);
        verifica("con l'ultimo byte il messaggio esce", l.messaggi().size() == 1);
        System.out.println();
    }

    private static void statoInMezzo() {
        System.out.println("Messaggi di stato del display fra ACK ed esito");
        System.out.println("---------------------------------------------");
        byte[] stato = new byte[22];
        stato[0] = Pos17.SOH;
        byte[] testo = Pos17.byteDi("INSERIRE CARTA      ");
        System.arraycopy(testo, 0, stato, 1, 20);
        stato[21] = Pos17.EOT;

        byte[] tutto = unisci(unisci(Pos17.ACK, stato), Pos17.incapsula(ESITO_OK));
        Lettore l = new Lettore();
        l.aggiungi(tutto, tutto.length);
        List<Messaggio> m = l.messaggi();
        verifica("si trovano tre messaggi", m.size() == 3);
        verifica("il secondo e' lo stato",
                Pos17Conversazione.TIPO_STATO.equals(m.get(1).tipo));
        verifica("e si legge cosa dice il display",
                "INSERIRE CARTA".equals(m.get(1).testo));
        verifica("l'esito in coda non va perso",
                Pos17Conversazione.TIPO_FRAME.equals(m.get(2).tipo));
        System.out.println();
    }

    private static void lrcGuasto() {
        System.out.println("Frame con LRC sbagliato");
        System.out.println("-----------------------");
        byte[] esito = Pos17.incapsula(ESITO_OK);
        esito[esito.length - 1] ^= 0x01;
        Lettore l = new Lettore();
        l.aggiungi(esito, esito.length);
        List<Messaggio> m = l.messaggi();
        verifica("il frame viene comunque estratto", m.size() == 1);
        verifica("ma segnalato come corrotto", !m.get(0).lrcOk);
        System.out.println();
    }

    private static void spazzatura() {
        System.out.println("Byte estranei nel flusso");
        System.out.println("------------------------");
        byte[] esito = Pos17.incapsula(ESITO_OK);
        byte[] con = unisci(new byte[]{0x41, 0x42, 0x43}, esito);
        Lettore l = new Lettore();
        l.aggiungi(con, con.length);
        List<Messaggio> m = l.messaggi();
        verifica("l'esito si ritrova lo stesso", m.size() == 1
                && ESITO_OK.equals(m.get(0).payload));
        verifica("e si conta quanto si e' scartato", l.byteScartati == 3);
        System.out.println();
    }

    private static void riscontri() throws Exception {
        System.out.println("I riscontri: senza, il terminale ripete e poi chiude");
        System.out.println("---------------------------------------------------");
        byte[] flusso = unisci(Pos17.ACK, Pos17.incapsula(ESITO_OK));
        ByteArrayOutputStream mandati = new ByteArrayOutputStream();
        Pos17Conversazione.Risultato r = Pos17Conversazione.attendi(
                new ByteArrayInputStream(flusso), mandati,
                System.currentTimeMillis(), "99999999",
                Pos17Conversazione.ESITO, null);

        verifica("si arriva all'esito", r.eEsito());
        verifica("l'esito e' quello giusto", "00".equals(r.dati.get("esito")));
        byte[] risposte = mandati.toByteArray();
        verifica("e' stato mandato un riscontro", risposte.length == 3);
        verifica("ed e' un ACK, non un NAK", risposte[0] == 0x06);
        System.out.println();
    }

    private static void nakEChiusura() throws Exception {
        System.out.println("Il terminale rifiuta, o chiude");
        System.out.println("------------------------------");
        Pos17Conversazione.Risultato r = Pos17Conversazione.attendi(
                new ByteArrayInputStream(Pos17.NAK), new ByteArrayOutputStream(),
                System.currentTimeMillis(), "99999999",
                Pos17Conversazione.ESITO, null);
        verifica("il NAK viene riconosciuto", "nak".equals(r.tipo));
        verifica("e si dice cosa controllare", r.motivo.contains("protocollo 17"));

        r = Pos17Conversazione.attendi(
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                System.currentTimeMillis(), "99999999",
                Pos17Conversazione.ESITO, null);
        verifica("la chiusura senza risposta viene riconosciuta",
                "chiusura".equals(r.tipo));
        verifica("e NON viene scambiata per un esito", !r.eEsito());
        System.out.println();
    }

    // ---- appoggio -------------------------------------------------------------

    private static byte[] unisci(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static byte[] primi(byte[] a, int quanti) {
        byte[] r = new byte[quanti];
        System.arraycopy(a, 0, r, 0, quanti);
        return r;
    }

    private static byte[] resto(byte[] a, int da) {
        byte[] r = new byte[a.length - da];
        System.arraycopy(a, da, r, 0, r.length);
        return r;
    }

    private static void verifica(String cosa, boolean condizione) {
        if (condizione) {
            passati++;
            System.out.println("  [OK ] " + cosa);
        } else {
            falliti++;
            System.out.println("  [NO ] " + cosa);
        }
    }
}
