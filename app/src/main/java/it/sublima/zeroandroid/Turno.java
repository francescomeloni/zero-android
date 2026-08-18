package it.sublima.zeroandroid;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Fa in modo che verso la stessa stampante si parli uno per volta.
 *
 * Una stampante — e a maggior ragione un registratore fiscale — regge **una
 * conversazione alla volta**. Aprendogliene piu' d'una in parallelo si intasa:
 * i socket restano appesi, l'apparecchio smette di rispondere a chiunque e da
 * fuori sembra che sia bloccato il tramite. Succede facilmente quando un
 * comando lento (la chiusura Z) e' ancora in corso e nel frattempo arriva
 * un'altra stampa.
 *
 * Chi arriva dopo aspetta il suo turno; se l'attesa e' troppa rinuncia con un
 * messaggio chiaro, invece di accodarsi all'infinito.
 */
public final class Turno {

    /** Oltre questo tempo in coda si rinuncia. */
    public static final int ATTESA_MASSIMA = 180000;

    private static final Map<String, Semaphore> turni = new HashMap<String, Semaphore>();

    private Turno() {
    }

    public static class TroppaCoda extends Exception {
        public TroppaCoda(String messaggio) {
            super(messaggio);
        }
    }

    /** Attende che la stampante sia libera. Da chiudere sempre con `libera`. */
    public static void attendi(String stampante) throws TroppaCoda {
        Semaphore s = semaforo(stampante);
        try {
            if (!s.tryAcquire(ATTESA_MASSIMA, TimeUnit.MILLISECONDS)) {
                throw new TroppaCoda("la stampante " + stampante + " e' occupata da un'altra"
                        + " stampa da troppo tempo");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TroppaCoda("attesa interrotta");
        }
    }

    public static void libera(String stampante) {
        semaforo(stampante).release();
    }

    /** Quante stampe stanno aspettando quella stampante. */
    public static int inAttesa(String stampante) {
        return semaforo(stampante).getQueueLength();
    }

    private static synchronized Semaphore semaforo(String stampante) {
        String chiave = (stampante == null) ? "" : stampante.trim();
        Semaphore s = turni.get(chiave);
        if (s == null) {
            s = new Semaphore(1, true);   // in ordine di arrivo
            turni.put(chiave, s);
        }
        return s;
    }
}
