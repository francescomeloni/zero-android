#!/bin/bash
# Compila l'agente e lancia tutte le verifiche.
#
# Gira su JVM: non servono emulatore, dispositivo o stampante. I casi di prova
# li costruisce Python con gli stessi zipfile e pickle che usa Sublima.
#
#   ./tools/prova.sh

set -e
cd "$(dirname "$0")/.."

echo "== Preparo i casi di prova =="
python3 test/genera_casi.py > /dev/null
python3 test/genera_pacchetti.py > /dev/null
echo "   fatti"
echo

echo "== Compilo =="
rm -rf build/classi
mkdir -p build/classi
# Le classi che parlano con Android (servizio, schermata, avvio al boot) non
# compilano senza android.jar e non servono ai test: si riconoscono dagli import
# invece di tenerne un elenco a mano, che prima o poi resterebbe indietro.
grep -L "^import android" app/src/main/java/it/sublima/zeroandroid/*.java > build/sorgenti.txt
ls test/*.java >> build/sorgenti.txt
javac -d build/classi @build/sorgenti.txt
echo "   compilato"
echo

falliti=0
# Ogni prova si lancia UNA volta sola e se ne tiene l'uscita: prima girava due
# volte, una per decidere e una per stampare, e con le prove che aprono socket
# significava farle davvero due volte.
for prova in TestPickle TestServer TestStampa TestMicrelec TestXml TestPos17 TestPos17Flusso TestSse; do
    echo "== $prova =="
    uscita="$(java -cp build/classi "$prova" 2>&1)" || true
    if echo "$uscita" | grep -q "0 falliti"; then
        echo "$uscita" | grep "ESITO"
    else
        echo "$uscita"
        falliti=1
    fi
    echo
done

if [ "$falliti" = "0" ]; then
    echo "Tutte le verifiche superate."
else
    echo "Ci sono verifiche fallite."
    exit 1
fi
