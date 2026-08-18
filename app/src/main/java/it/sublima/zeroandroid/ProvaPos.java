package it.sublima.zeroandroid;

import java.util.Map;

/**
 * Strumento da riga di comando per parlare con un terminale POS.
 *
 * Serve a verificare il protocollo contro l'emulatore, o contro un terminale
 * vero in modalita' di prova, prima ancora che l'agente sappia gestire i
 * pagamenti. Stampa tutto il dialogo, byte per byte.
 *
 *   java -cp build/classi it.sublima.zeroandroid.ProvaPos stato
 *   java -cp build/classi it.sublima.zeroandroid.ProvaPos ultimo
 *   java -cp build/classi it.sublima.zeroandroid.ProvaPos paga 1.00
 *
 * Indirizzo, porta e identificativi si possono cambiare con le variabili
 * POS_IP, POS_PORTA, POS_ID, CASSA_ID.
 *
 * ⚠️ `stato` e `ultimo` sono innocui: non muovono denaro e non chiedono la
 * carta. `paga` avvia una transazione vera — su un emulatore non succede nulla,
 * su un terminale collegato al circuito sì.
 */
public final class ProvaPos {

    private ProvaPos() {
    }

    public static void main(String[] args) {
        String ip = ambiente("POS_IP", "192.0.2.43");
        int porta = Integer.parseInt(ambiente("POS_PORTA", "1000"));
        String idPos = ambiente("POS_ID", "99999999");
        String idCassa = ambiente("CASSA_ID", "00000001");

        String comando = (args.length > 0) ? args[0].toLowerCase() : "stato";
        String importo = (args.length > 1) ? args[1] : "1.00";

        System.out.println("Terminale " + ip + ":" + porta
                + "   ID POS " + idPos + "   ID cassa " + idCassa);
        for (String problema : Pos17.anomalieConfigurazione(idPos, idCassa, "")) {
            System.out.println("  ⚠ " + problema);
        }
        System.out.println();

        Pos17Conversazione.Registro log = new Pos17Conversazione.Registro() {
            public void scrivi(String messaggio) {
                System.out.println("   " + messaggio);
            }
        };

        byte[] frame;
        Pos17Conversazione.Attesa attesa = Pos17Conversazione.ESITO;
        int timeout = Pos17Conversazione.TIMEOUT_PAGAMENTO;

        try {
            if ("stato".equals(comando)) {
                System.out.println("STATO del terminale (non muove denaro)");
                frame = Pos17.frameStato(idPos);
                attesa = Pos17Conversazione.STATO;
                timeout = 30000;
            } else if ("ultimo".equals(comando)) {
                System.out.println("ULTIMO esito salvato (non muove denaro)");
                frame = Pos17.frameUltimoEsito(idPos, idCassa);
                timeout = 15000;
            } else if ("paga".equals(comando)) {
                System.out.println("PAGAMENTO di " + importo + " EUR"
                        + "  —  su un terminale vero avvia una transazione");
                frame = Pos17.framePagamento(idPos, idCassa, importo, "", false);
            } else {
                System.out.println("Comandi: stato | ultimo | paga <importo>");
                System.exit(2);
                return;
            }
        } catch (Pos17.ImportoNonValido e) {
            System.out.println("Importo non valido: " + e.getMessage());
            System.exit(2);
            return;
        }

        System.out.println("   frame di " + frame.length + " byte");
        System.out.println();

        long inizio = System.currentTimeMillis();
        Pos17Conversazione.Risultato r = Pos17Conversazione.esegui(
                ip, porta, frame, timeout, idPos, attesa, log);

        System.out.println();
        System.out.println("=================================================");
        System.out.println("Esito del dialogo: " + r.tipo
                + "   (dopo " + r.secondi + " s)");
        if (r.motivo != null) {
            System.out.println(r.motivo);
        }
        if (r.dati != null) {
            for (Map.Entry<String, String> e : r.dati.entrySet()) {
                System.out.println(String.format("   %-26s %s", e.getKey(), e.getValue()));
            }
        }
        System.out.println("=================================================");
        System.exit(r.eEsito() ? 0 : 1);
    }

    private static String ambiente(String nome, String riserva) {
        String v = System.getenv(nome);
        return (v == null || v.trim().isEmpty()) ? riserva : v.trim();
    }
}
