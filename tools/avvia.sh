#!/bin/bash
# Avvia l'agente sul PC, senza Android.
#
#   ./tools/avvia.sh            in ascolto sulla 55226, come lo Zero
#   ./tools/avvia.sh 55227      su un'altra porta, per non litigare con lo Zero
#
# Compila da solo se serve. Si ferma con Ctrl+C.
#
# ⚠ Sulla 55226 puo' esserci un solo programma: se lo Zero Python e' acceso,
# spegnilo prima, oppure indica un'altra porta.

set -e
cd "$(dirname "$0")/.."

PORTA="${1:-55226}"

if [ ! -f build/classi/it/sublima/zeroandroid/Avvio.class ]; then
    echo "Compilo..."
    mkdir -p build/classi
    javac -d build/classi app/src/main/java/it/sublima/zeroandroid/*.java
fi

occupante=$(ss -ltnp 2>/dev/null | grep ":$PORTA " | grep -oP 'pid=\K[0-9]+' | head -1 || true)
if [ -n "$occupante" ]; then
    echo "La porta $PORTA e' gia' occupata dal processo $occupante:"
    ps -o pid=,args= -p "$occupante" | sed 's/^/   /'
    echo
    echo "Fermalo con:  kill $occupante"
    echo "oppure lancia l'agente su un'altra porta:  ./tools/avvia.sh 55227"
    exit 1
fi

echo "Avvio sulla porta $PORTA. Ctrl+C per fermare."
echo
exec java -cp build/classi it.sublima.zeroandroid.Avvio "$PORTA"
