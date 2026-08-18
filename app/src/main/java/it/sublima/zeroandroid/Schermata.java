package it.sublima.zeroandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * La schermata dell'app: dice se l'agente e' acceso e che indirizzo scrivere
 * in Sublima.
 *
 * Volutamente scarna e senza file di layout: chi la apre deve capire in tre
 * secondi se le stampe passano, e quale indirizzo mettere nella scheda del
 * punto cassa. Le impostazioni vere stanno in Sublima, non qui.
 *
 * L'unica eccezione e' l'aggancio per l'ascolto delle comande, che si impara
 * dal TEST ZERO ma si puo' anche scrivere a mano: se l'aggancio automatico non
 * riesce, senza questi tre campi non ci sarebbe modo ne' di rimediare ne' di
 * capire a cosa il tablet e' attaccato.
 */
public class Schermata extends Activity {

    private TextView stato;
    private TextView ascolto;
    private TextView indirizzo;
    private TextView registro;
    private EditText campoProfilo;
    private EditText campoMagazzino;
    private EditText campoLicenza;
    private Button interruttore;
    private TextView nomeMagazzino;
    private final Handler ogniTanto = new Handler();

    private final Runnable aggiorna = new Runnable() {
        public void run() {
            mostra();
            ogniTanto.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle statoSalvato) {
        super.onCreate(statoSalvato);

        LinearLayout radice = new LinearLayout(this);
        radice.setOrientation(LinearLayout.VERTICAL);
        radice.setPadding(40, 60, 40, 40);

        TextView titolo = new TextView(this);
        titolo.setText(Versione.NOME + " " + Versione.NUMERO);
        titolo.setTextSize(22);
        radice.addView(titolo);

        stato = new TextView(this);
        stato.setTextSize(17);
        stato.setPadding(0, 30, 0, 10);
        radice.addView(stato);

        // Lo stato dell'ascolto sta subito sotto quello del server, e non piu'
        // in basso: quando le comande arrivano da li', e' questa la riga che
        // dice se il locale sta stampando.
        ascolto = new TextView(this);
        ascolto.setTextSize(15);
        ascolto.setPadding(0, 4, 0, 14);
        radice.addView(ascolto);

        indirizzo = new TextView(this);
        indirizzo.setTextSize(15);
        indirizzo.setPadding(0, 10, 0, 20);
        radice.addView(indirizzo);

        // Cosa copre l'agente: la stessa fonte della pagina web e di /test_zero.
        // Chi ha il tablet in mano deve poter rispondere a "ma questo cosa sa
        // fare?" senza chiamare nessuno.
        TextView copre = new TextView(this);
        StringBuilder sb = new StringBuilder();
        for (Versione.Capacita c : Versione.elenco()) {
            sb.append(c.supportata ? "✓  " : "✗  ").append(c.etichetta);
            if (c.nota != null && !c.nota.isEmpty()) {
                sb.append("\n      ").append(c.nota);
            }
            sb.append('\n');
        }
        sb.append("\nNon copre:");
        for (String v : Versione.fuoriPortata()) {
            sb.append("\n✗  ").append(v);
        }
        copre.setText(sb.toString());
        copre.setTextSize(12);
        copre.setPadding(0, 6, 0, 20);
        radice.addView(copre);

        Button avvia = new Button(this);
        avvia.setText("Avvia");
        avvia.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                accendi();
            }
        });
        radice.addView(avvia);

        Button ferma = new Button(this);
        ferma.setText("Ferma");
        ferma.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopService(new Intent(Schermata.this, Servizio.class));
            }
        });
        radice.addView(ferma);

        radice.addView(riquadroAggancio());

        TextView etichetta = new TextView(this);
        etichetta.setText("Registro");
        etichetta.setPadding(0, 30, 0, 6);
        radice.addView(etichetta);

        registro = new TextView(this);
        registro.setTextSize(11);
        registro.setMovementMethod(new ScrollingMovementMethod());
        ScrollView scorri = new ScrollView(this);
        scorri.addView(registro);
        radice.addView(scorri, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView tutto = new ScrollView(this);
        tutto.addView(radice);
        setContentView(tutto);
        accendi();
    }

    /**
     * I tre campi dell'aggancio, con il loro valore attuale gia' dentro.
     *
     * Mostrarli sempre non e' un vezzo: la domanda "a quale profilo e' attaccato
     * questo tablet" non ha nessun'altra risposta sul posto, e in un locale con
     * piu' punti vendita e' la prima cosa che si sbaglia.
     */
    private View riquadroAggancio() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 34, 0, 10);

        TextView titolo = new TextView(this);
        titolo.setText("Ascolto comande SSE");
        titolo.setTextSize(14);
        box.addView(titolo);

        TextView spiega = new TextView(this);
        spiega.setText("Serve solo dove c'e' la ristorazione, quindi nasce SPENTO."
                + " Acceso, l'agente si collega a Sublima e aspetta le comande:"
                + " i palmari non devono piu' raggiungere questo dispositivo.");
        spiega.setTextSize(11);
        spiega.setPadding(0, 2, 0, 8);
        box.addView(spiega);

        // L'interruttore generale, prima dell'aggancio: e' la domanda che si
        // pone chi installa, e la risposta per nove installazioni su dieci e' no.
        interruttore = new Button(this);
        interruttore.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean adesso = !Conf.acceso();
                Conf.accendi(adesso);
                avvisa(adesso ? "Ascolto comande SSE attivato" : "Ascolto comande SSE spento");
                mostraInterruttore();
            }
        });
        box.addView(interruttore);
        mostraInterruttore();

        TextView spiegaAggancio = new TextView(this);
        spiegaAggancio.setText("A quale profilo si aggancia: di norma si compila da solo"
                + " premendo TEST ZERO nella scheda del punto cassa. Si scrive a mano"
                + " solo se quello non riesce.");
        spiegaAggancio.setTextSize(11);
        spiegaAggancio.setPadding(0, 14, 0, 8);
        box.addView(spiegaAggancio);

        campoProfilo = campo(box, "Indirizzo di Sublima", Conf.urlProfilo());
        campoMagazzino = campo(box, "Magazzino (punto vendita)", Conf.idMagazzino());

        // Il magazzino si sceglie per NOME, non per numero: sapere a memoria che
        // il locale di via Roma e' il 4 e' il modo piu' facile di attaccare il
        // tablet al punto vendita sbagliato, e di far uscire le comande di un
        // locale nella cucina di un altro.
        nomeMagazzino = new TextView(this);
        nomeMagazzino.setTextSize(12);
        nomeMagazzino.setPadding(0, 2, 0, 0);
        box.addView(nomeMagazzino);

        Button scegli = new Button(this);
        scegli.setText("Scegli il magazzino per nome...");
        scegli.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                chiediElenco();
            }
        });
        box.addView(scegli);

        campoLicenza = campo(box, "Licenza (facoltativa)", Conf.licenza());
        mostraNomeMagazzino();

        Button aggancia = new Button(this);
        aggancia.setText("Aggancia a mano");
        aggancia.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Forzato: chi digita localhost sul banco di prova sa cosa sta
                // facendo, mentre lo stesso indirizzo arrivato da solo dal
                // server e' quasi sempre un guasto di configurazione.
                String problema = Conf.aggancia(
                        campoProfilo.getText().toString(),
                        campoMagazzino.getText().toString(),
                        campoLicenza.getText().toString(), true);
                if (problema == null) {
                    // Chi si prende la briga di scrivere i tre campi a mano
                    // vuole l'ascolto: accenderlo qui evita di dover premere
                    // anche l'interruttore e chiedersi perche' non parte.
                    Conf.accendi(true);
                    mostraInterruttore();
                    avvisa("Agganciato al magazzino " + Conf.idMagazzino());
                } else {
                    avvisa(problema);
                }
            }
        });
        box.addView(aggancia);

        Button sgancia = new Button(this);
        sgancia.setText("Sgancia");
        sgancia.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Conf.sgancia();
                mostraNomeMagazzino();
                campoProfilo.setText("");
                campoMagazzino.setText("");
                campoLicenza.setText("");
                avvisa("Sganciato: non ricevo piu' comande");
            }
        });
        box.addView(sgancia);

        return box;
    }

    /**
     * Chiede a Sublima l'elenco dei punti vendita e lo mostra per nome.
     *
     * ⚠️ La rete va su un filo suo: su Android una chiamata HTTP dal filo
     * dell'interfaccia non e' lenta, e' vietata e chiude l'app. Il ritorno
     * all'interfaccia passa da runOnUiThread.
     */
    private void chiediElenco() {
        final String indirizzo = campoProfilo.getText().toString().trim();
        if (indirizzo.isEmpty()) {
            avvisa("Scrivi prima l'indirizzo di Sublima");
            return;
        }
        avvisa("Chiedo l'elenco a Sublima...");
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    final java.util.List<Magazzini.Punto> punti = Magazzini.chiedi(indirizzo);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            mostraElenco(punti);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            avvisa("Elenco non ottenuto: " + e.getMessage());
                        }
                    });
                }
            }
        }, "zero-magazzini");
        t.setDaemon(true);
        t.start();
    }

    private void mostraElenco(final java.util.List<Magazzini.Punto> punti) {
        if (punti == null || punti.isEmpty()) {
            avvisa("Sublima non ha restituito nessun punto vendita");
            return;
        }
        final String[] righe = new String[punti.size()];
        for (int i = 0; i < punti.size(); i++) {
            righe[i] = punti.get(i).toString();
        }
        new AlertDialog.Builder(this)
                .setTitle("Scegli il punto vendita")
                .setItems(righe, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int quale) {
                        Magazzini.Punto p = punti.get(quale);
                        campoMagazzino.setText(p.id);
                        Conf.scrivi(Conf.NOME_MAGAZZINO, p.nome);
                        mostraNomeMagazzino();
                        avvisa("Scelto " + p.nome + ": ora premi «Aggancia a mano»");
                    }
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    /** Il nome sotto il campo, cosi' il numero non resta muto. */
    private void mostraNomeMagazzino() {
        if (nomeMagazzino == null) {
            return;
        }
        String nome = Conf.nomeMagazzino();
        nomeMagazzino.setText(nome.isEmpty()
                ? "Nome non ancora noto: scegli dall'elenco, oppure premi TEST ZERO in Sublima."
                : ("Punto vendita: " + nome));
    }

    /** L'etichetta dell'interruttore dice lo STATO, non l'azione. */
    private void mostraInterruttore() {
        if (interruttore == null) {
            return;
        }
        boolean acceso = Conf.acceso();
        interruttore.setText(acceso
                ? "Ascolto comande SSE: ATTIVO  (tocca per spegnere)"
                : "Ascolto comande SSE: SPENTO  (tocca per attivare)");
    }

    private EditText campo(LinearLayout dove, String etichetta, String valore) {
        TextView t = new TextView(this);
        t.setText(etichetta);
        t.setTextSize(11);
        t.setPadding(0, 8, 0, 0);
        dove.addView(t);

        EditText e = new EditText(this);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setText(valore);
        dove.addView(e);
        return e;
    }

    private void avvisa(String messaggio) {
        Toast.makeText(this, messaggio, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ogniTanto.post(aggiorna);
    }

    @Override
    protected void onPause() {
        ogniTanto.removeCallbacks(aggiorna);
        super.onPause();
    }

    private void accendi() {
        Intent servizio = new Intent(this, Servizio.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(servizio);
        } else {
            startService(servizio);
        }
    }

    private void mostra() {
        if (Servizio.acceso()) {
            stato.setText("Acceso - " + Servizio.stato());
            stato.setTextColor(Color.parseColor("#1B7F3B"));
        } else {
            String s = Servizio.stato();
            stato.setText(s.isEmpty() ? "Spento" : s);
            stato.setTextColor(Color.parseColor("#B00020"));
        }
        mostraAscolto();
        // Anche l'interruttore, perche' non lo muove solo chi lo tocca: un 503
        // "SSE disattivato" da Sublima lo spegne, e l'etichetta deve seguirlo.
        mostraInterruttore();
        mostraNomeMagazzino();
        indirizzo.setText("Nella scheda del punto cassa di Sublima scrivi come IP cassa:\n"
                + mioIndirizzo()
                + "\n\nSe le stampe non arrivano quando lo schermo e' spento, togli"
                + " l'app dal risparmio energetico e concedile l'avvio automatico.");
        registro.setText(ServerHttp.registroRecente());
    }

    /**
     * ⚠️ "Collegato" da solo direbbe una mezza verita': il collegamento regge
     * anche quando nessun punto cassa del magazzino e' impostato per mandare le
     * comande da questa parte, e in quel caso non arriva niente pur essendo
     * tutto verde. Per questo si mostrano anche i battiti e l'ultima comanda:
     * sono le due cose che distinguono "vivo" da "vivo e serve a qualcosa".
     */
    private void mostraAscolto() {
        if (!Conf.agganciato()) {
            ascolto.setText("Ascolto comande SSE: non configurato\n"
                    + "Premi TEST ZERO nella scheda del punto cassa.");
            ascolto.setTextColor(Color.parseColor("#666666"));
            return;
        }
        String dove = "magazzino " + Conf.idMagazzino() + " su " + Conf.urlProfilo();
        if (!Conf.sseAttivo()) {
            ascolto.setText("Ascolto comande SSE: spento\n" + dove);
            ascolto.setTextColor(Color.parseColor("#666666"));
            return;
        }
        if (!ClienteSse.collegato()) {
            ascolto.setText("Ascolto comande SSE: NON collegato\n"
                    + ClienteSse.descrizione() + "\n" + dove);
            ascolto.setTextColor(Color.parseColor("#B00020"));
            return;
        }
        String ultima = ClienteSse.ultimoLavoro();
        ascolto.setText("Ascolto comande SSE: collegato da "
                + ClienteSse.secondiCollegato() + "s\n"
                + dove + "\n"
                + ClienteSse.battiti() + " battiti"
                + (ultima.isEmpty() ? "" : " - ultima comanda: " + ultima));
        ascolto.setTextColor(Color.parseColor("#1B7F3B"));
    }

    /**
     * L'indirizzo del dispositivo sulla rete: e' quello da scrivere in Sublima.
     *
     * Lo chiede alla stessa funzione che usa la pagina web: leggendolo per conto
     * proprio dal WifiManager, su un dispositivo con cavo e Wi-Fi insieme le due
     * UI mostravano indirizzi diversi.
     */
    private String mioIndirizzo() {
        String a = ServerHttp.indirizzoLocale();
        return (a != null) ? a : "indirizzo non rilevato: controlla il Wi-Fi";
    }
}
