#!/bin/bash
# Costruisce l'APK firmato, senza Gradle.
#
#   ./tools/costruisci-apk.sh
#
# Usa solo quello che c'e' gia' nell'SDK: aapt2 -> javac -> d8 -> zipalign ->
# apksigner. Niente da scaricare, niente rete: funziona oggi e fra due anni.
#
# La chiave di firma si cerca in KEYSTORE (o in ~/.zero_android/firma.jks) e, se
# non c'e', se ne genera una di sviluppo.
#
# ⚠ Cambiare chiave su un'app gia' installata obbliga a disinstallarla prima,
#   perdendo la configurazione. Se questa app dovra' essere aggiornata nel
#   tempo, conserva il file della chiave e la sua password.

set -e
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
API="${API:-34}"
BT="$SDK/build-tools/${BUILD_TOOLS:-34.0.0}"
JAR="$SDK/platforms/android-$API/android.jar"

KEYSTORE="${KEYSTORE:-$HOME/.zero_android/firma.jks}"
KEYPASS="${KEYPASS:-zeroandroid}"
KEYALIAS="${KEYALIAS:-zero}"

for strumento in "$BT/aapt2" "$BT/d8" "$BT/apksigner" "$BT/zipalign"; do
    [ -x "$strumento" ] || { echo "Manca $strumento"; exit 1; }
done
[ -f "$JAR" ] || { echo "Manca $JAR"; exit 1; }

rm -rf build/apk
mkdir -p build/apk/classi build/apk/res

# La versione si legge da Versione.NUMERO e non si scrive due volte: quella e'
# la stessa che Sublima riceve in /test_zero, e averne una copia nel manifest
# significherebbe farle divergere.
#
# Il codice numerico che Android usa per capire cos'e' piu' recente si ricava
# dalla data: 2026.08.18.2 -> 26081802. Cresce da solo a ogni rilascio, e questo
# conta perche' Android RIFIUTA di installare sopra una versione con codice piu'
# basso. Il quarto numero (rilasci dello stesso giorno) arriva fino a 99.
VERSIONE="$(sed -n 's/.*NUMERO = "\([^"]*\)".*/\1/p' \
    app/src/main/java/it/sublima/zeroandroid/Versione.java | head -1)"
CODICE="$(python3 - "$VERSIONE" <<'PY'
import re, sys
v = sys.argv[1]
m = re.match(r"^(\d{4})\.(\d{1,2})\.(\d{1,2})(?:\.(\d{1,2}))?$", v)
if not m:
    sys.exit("versione non interpretabile: " + repr(v))
anno, mese, giorno, rilascio = m.groups()
print(((int(anno) % 100 * 100 + int(mese)) * 100 + int(giorno)) * 100
      + int(rilascio or 0))
PY
)" || { echo "Versione non interpretabile in Versione.java: '$VERSIONE'"; exit 1; }
echo "== Versione =="
echo "   $VERSIONE  (codice $CODICE)"

echo "== Risorse =="
"$BT/aapt2" compile --dir app/src/main/res -o build/apk/res.zip
"$BT/aapt2" link \
    -I "$JAR" \
    --manifest app/src/main/AndroidManifest.xml \
    --version-code "$CODICE" \
    --version-name "$VERSIONE" \
    --java build/apk/gen \
    -o build/apk/base.apk \
    build/apk/res.zip
mkdir -p build/apk/gen
echo "   compilate"

echo "== Codice =="
mkdir -p build/apk/gen
find build/apk/gen -name "*.java" > build/apk/sorgenti.txt 2>/dev/null || true
find app/src/main/java -name "*.java" >> build/apk/sorgenti.txt
javac -source 8 -target 8 -nowarn -bootclasspath "$JAR" -cp "$JAR" \
    -d build/apk/classi @build/apk/sorgenti.txt 2>&1 | grep -v "^Note:" || true
echo "   compilato"

echo "== Dex =="
"$BT/d8" --lib "$JAR" --output build/apk $(find build/apk/classi -name "*.class")
echo "   convertito"

echo "== Assemblo =="
cd build/apk
cp base.apk non-firmato.apk
zip -q non-firmato.apk classes.dex
cd ../..
"$BT/zipalign" -f 4 build/apk/non-firmato.apk build/apk/allineato.apk

echo "== Firma =="
if [ ! -f "$KEYSTORE" ]; then
    echo "   nessuna chiave in $KEYSTORE: ne genero una di SVILUPPO"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$KEYALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KEYPASS" -keypass "$KEYPASS" \
        -dname "CN=Zero Android, O=Sublima, C=IT" >/dev/null 2>&1
    echo "   chiave creata: conservala, serve per gli aggiornamenti futuri"
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEYALIAS" \
    --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
    --out zero-android.apk build/apk/allineato.apk
"$BT/apksigner" verify --print-certs zero-android.apk | head -3

echo
echo "Fatto: $(pwd)/zero-android.apk ($(du -h zero-android.apk | cut -f1))"
echo "Per installarlo su un dispositivo collegato:  adb install -r zero-android.apk"
