import it.sublima.zeroandroid.Pos17;

import java.util.List;
import java.util.Map;

/**
 * Verifica il protocollo POS contro i frame REALI catturati dai terminali.
 *
 * I vettori sono gli stessi di `test_prot17_modulo.py` nello Zero: se il Java
 * non li riproduce identici, il driver non si scrive. Nessun hardware, nessuna
 * rete, nessuna transazione.
 */
public class TestPos17 {

    private static int passati = 0;
    private static int falliti = 0;

    // Esito approvato, carta a chip. Frame di esempio: struttura reale,
    // numeri inventati — un PAN, foss'anche mascherato, non sta in un repo.
    private static final String ESITO_OK_EMULATORE =
            "999999990E00" + "000************0000" + "ICC" + "000000"
            + "0152050" + "2" + "00000100004" + "000002" + "000101";

    // Emulatore, annullato dal terminale
    private static final String ESITO_KO_EMULATORE =
            "999999990E01" + "TRANSAZIONE ANNULLATA   "
            + "00000000000" + "2" + "00000000000" + "000000" + "000001";

    // Il caso che conta: il terminale risponde con il PROPRIO identificativo,
    // diverso da quello configurato. Confrontarli faceva scartare esiti di
    // transazioni GIA' PAGATE. Numeri inventati, struttura vera.
    private static final String ESITO_OK_ID_DIVERSO =
            "70000001" + "0E" + "00" + "0000000000000000000" + "CLI" + "000000"
            + "0000000" + "2" + "00000000000" + "000000" + "000001";

    public static void main(String[] args) throws Exception {
        lrcEIncapsulamento();
        identificativi();
        importi();
        framePagamento();
        altriComandi();
        riconoscimento();
        letturaEsiti();
        anomalie();

        System.out.println();
        System.out.println("=================================================");
        System.out.println("ESITO: " + passati + " superati, " + falliti + " falliti");
        System.out.println("=================================================");
        System.exit(falliti == 0 ? 0 : 1);
    }

    private static void lrcEIncapsulamento() {
        System.out.println("LRC e incapsulamento");
        System.out.println("--------------------");
        byte[] f = Pos17.incapsula("PROVA");
        verifica("comincia con STX", f[0] == 0x02);
        verifica("ETX prima del LRC", f[f.length - 2] == 0x03);
        verifica("il LRC si verifica da solo", Pos17.verificaLrc(f));
        verifica("lunghezza = payload + 3", f.length == 5 + 3);

        byte[] guasto = f.clone();
        guasto[guasto.length - 1] ^= 0x01;
        verifica("un LRC sbagliato viene visto", !Pos17.verificaLrc(guasto));

        // ACK e NAK del manuale: 06 03 7a e 15 03 69
        verifica("ACK vale 06 03 7a", Pos17.ACK[0] == 0x06 && Pos17.ACK[1] == 0x03
                && (Pos17.ACK[2] & 0xFF) == 0x7a);
        verifica("NAK vale 15 03 69", Pos17.NAK[0] == 0x15 && Pos17.NAK[1] == 0x03
                && (Pos17.NAK[2] & 0xFF) == 0x69);
        System.out.println();
    }

    private static void identificativi() {
        System.out.println("Identificativi: sempre 8 cifre");
        System.out.println("------------------------------");
        verifica("un ID corto si riempie di zeri a sinistra",
                "00004321".equals(Pos17.normalizzaId("4321")));
        verifica("un ID gia' giusto resta uguale",
                "99999999".equals(Pos17.normalizzaId("99999999")));
        verifica("le lettere si scartano",
                "00000123".equals(Pos17.normalizzaId("AB123")));
        verifica("un ID troppo lungo si tronca a 8",
                "12345678".equals(Pos17.normalizzaId("1234567890")));
        verifica("vuoto diventa otto zeri", "00000000".equals(Pos17.normalizzaId("")));
        verifica("null diventa otto zeri", "00000000".equals(Pos17.normalizzaId(null)));
        System.out.println();
    }

    private static void importi() throws Exception {
        System.out.println("Importi: sono soldi, quindi BigDecimal");
        System.out.println("--------------------------------------");
        // il caso che in virgola mobile fa 498.99999999999994
        verifica("4.99 fa esattamente 499 centesimi", Pos17.centesimi("4.99") == 499);
        verifica("1.00 fa 100", Pos17.centesimi("1.00") == 100);
        verifica("0.20 fa 20", Pos17.centesimi("0.20") == 20);
        verifica("21 (senza decimali) fa 2100", Pos17.centesimi("21") == 2100);
        verifica("la virgola vale come il punto", Pos17.centesimi("4,99") == 499);
        verifica("si arrotonda a metà per eccesso", Pos17.centesimi("0.005") == 1);

        verifica("zero viene rifiutato", rifiutaImporto("0"));
        verifica("un importo negativo viene rifiutato", rifiutaImporto("-5.00"));
        verifica("un importo vuoto viene rifiutato", rifiutaImporto(""));
        verifica("del testo viene rifiutato", rifiutaImporto("pippo"));
        verifica("oltre le 8 cifre viene rifiutato", rifiutaImporto("1000000.00"));
        System.out.println();
    }

    private static void framePagamento() throws Exception {
        System.out.println("Frame di pagamento: 170 byte, non 169");
        System.out.println("-------------------------------------");
        byte[] f = Pos17.framePagamento("99999999", "00000001", "1.00", "", false);
        verifica("il frame e' di 170 byte", f.length == 170);
        verifica("il LRC torna", Pos17.verificaLrc(f));

        String payload = Pos17.testoDi(f, 1, f.length - 3);
        verifica("il payload e' di 167 caratteri", payload.length() == 167);
        verifica("comincia con l'ID POS", payload.startsWith("99999999"));
        verifica("il codice comando e' P", payload.charAt(9) == 'P');
        verifica("poi c'e' l'ID cassa", payload.substring(10, 18).equals("00000001"));
        verifica("l'importo e' in centesimi con gli zeri",
                payload.substring(23, 31).equals("00000100"));
        verifica("il contratto e' allineato a destra",
                payload.substring(31, 159).equals(String.format("%128s", "")));

        // ⚠️ la trappola: un ID di 7 cifre produrrebbe 169 byte e il terminale
        // scarterebbe il pacchetto senza dire niente
        byte[] corto = Pos17.framePagamento("1234567", "54321", "1.00", "", false);
        verifica("anche con ID corti restano 170 byte", corto.length == 170);

        byte[] esteso = Pos17.framePagamento("99999999", "00000001", "1.00", "", true);
        verifica("il comando esteso usa X",
                Pos17.testoDi(esteso, 1, esteso.length - 3).charAt(9) == 'X');

        byte[] conContratto = Pos17.framePagamento("99999999", "00000001", "4.99",
                "CONTRATTO123", false);
        verifica("con contratto restano 170 byte", conContratto.length == 170);
        verifica("il contratto sta in fondo al suo campo",
                Pos17.testoDi(conContratto, 1, conContratto.length - 3)
                        .substring(31, 159).endsWith("CONTRATTO123"));
        System.out.println();
    }

    private static void altriComandi() {
        System.out.println("Comandi G e s");
        System.out.println("-------------");
        byte[] g = Pos17.frameUltimoEsito("99999999", "00000001");
        String pg = Pos17.testoDi(g, 1, g.length - 3);
        verifica("G: codice comando", pg.charAt(9) == 'G');
        verifica("G: porta anche l'ID cassa", pg.substring(10, 18).equals("00000001"));
        verifica("G: payload di 22 caratteri", pg.length() == 22);
        verifica("G: il LRC torna", Pos17.verificaLrc(g));

        byte[] s = Pos17.frameStato("99999999");
        String ps = Pos17.testoDi(s, 1, s.length - 3);
        verifica("s: codice comando", ps.charAt(9) == 's');
        verifica("s: payload di 10 caratteri", ps.length() == 10);
        verifica("s: il LRC torna", Pos17.verificaLrc(s));
        System.out.println();
    }

    private static void riconoscimento() {
        System.out.println("Riconoscere un esito dagli altri messaggi");
        System.out.println("-----------------------------------------");
        verifica("l'esito dell'emulatore e' riconosciuto",
                Pos17.eEsito(ESITO_OK_EMULATORE));
        verifica("l'esito con ID diverso e' riconosciuto", Pos17.eEsito(ESITO_OK_ID_DIVERSO));
        verifica("un KO e' comunque un esito", Pos17.eEsito(ESITO_KO_EMULATORE));

        // ⚠️ lo stato ha due zeri dove l'esito ha il suo codice: senza il filtro
        // sul codice messaggio verrebbe letto come "pagamento approvato"
        // 8 cifre di ID + '0' riservato + 's': il codice comando sta in posizione 10
        String stato = "999999990s" + "00000000000000000000" + "2";
        verifica("lo stato NON passa per un esito", !Pos17.eEsito(stato));
        verifica("lo stato e' riconosciuto come tale", Pos17.eStato(stato));
        verifica("uno scontrino non passa per un esito",
                !Pos17.eEsito("999999990S" + "righe di scontrino qui"));
        verifica("un messaggio corto non passa", !Pos17.eEsito("100"));
        System.out.println();
    }

    private static void letturaEsiti() {
        System.out.println("Lettura degli esiti reali");
        System.out.println("-------------------------");
        Map<String, String> ok = Pos17.parseEsito(ESITO_OK_EMULATORE, "99999999");
        verifica("emulatore: esito 00", "00".equals(ok.get("esito")));
        verifica("emulatore: risulta OK", "OK".equals(ok.get("esito_interno")));
        verifica("emulatore: PAN mascherato",
                "000************0000".equals(ok.get("PAN")));
        verifica("emulatore: tipo transazione ICC",
                "ICC".equals(ok.get("tipo_transazione")));
        verifica("emulatore: codice autorizzazione",
                "000000".equals(ok.get("codice_autorizzazione")));
        verifica("emulatore: identificativo allineato",
                "true".equals(ok.get("id_pos_allineato")));

        Map<String, String> ko = Pos17.parseEsito(ESITO_KO_EMULATORE, "99999999");
        verifica("KO: esito 01", "01".equals(ko.get("esito")));
        verifica("KO: risulta KO", "KO".equals(ko.get("esito_interno")));
        verifica("KO: il motivo e' leggibile",
                "TRANSAZIONE ANNULLATA".equals(ko.get("descrizione_esito")));
        // ⚠️ senza questi campi sul KO, il recupero col comando G non
        // distinguerebbe un rifiuto nuovo da un esito vecchio in memoria
        verifica("KO: lo STAN si legge lo stesso", ko.get("STAN") != null);
        verifica("KO: il progressivo si legge lo stesso",
                ko.get("num_progressivo") != null);

        // Il terminale risponde col PROPRIO identificativo: qui e' diverso da
        // quello configurato, e la transazione va comunque riconosciuta.
        Map<String, String> idDiverso = Pos17.parseEsito(ESITO_OK_ID_DIVERSO, "70000002");
        verifica("ID diverso: risulta OK", "OK".equals(idDiverso.get("esito_interno")));
        verifica("ID diverso: l'identificativo e' quello del terminale",
                "70000001".equals(idDiverso.get("id_pos")));
        verifica("ID diverso: si segnala che non combacia",
                "false".equals(idDiverso.get("id_pos_allineato")));
        verifica("ID diverso: ma l'esito resta valido",
                "00".equals(idDiverso.get("esito")));
        System.out.println("      " + idDiverso.get("riassunto"));
        System.out.println();
    }

    private static void anomalie() {
        System.out.println("Avvisi sulla configurazione");
        System.out.println("---------------------------");
        List<String> nessuna = Pos17.anomalieConfigurazione("99999999", "00000001", "");
        verifica("una configurazione giusta non da' avvisi", nessuna.isEmpty());

        List<String> corte = Pos17.anomalieConfigurazione("1234567", "00000001", "");
        verifica("un ID di 7 cifre viene segnalato", !corte.isEmpty());
        verifica("e si dice quante ne ha", corte.get(0).contains("7 caratteri"));

        List<String> vuoto = Pos17.anomalieConfigurazione("", "00000001", "");
        verifica("un ID vuoto viene segnalato", !vuoto.isEmpty());
        System.out.println();
    }

    // ---- appoggio -------------------------------------------------------------

    private static boolean rifiutaImporto(String importo) {
        try {
            Pos17.centesimi(importo);
            return false;
        } catch (Pos17.ImportoNonValido e) {
            return true;
        } catch (Exception e) {
            return true;
        }
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
