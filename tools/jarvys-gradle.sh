#!/bin/bash
# Uso: jarvys-gradle.sh start <nombre> <args gradle...>   (lanza en background y vuelve ya)
#      jarvys-gradle.sh wait  <nombre>                    (bloquea hasta 9 min; imprime resumen corto)
#      jarvys-gradle.sh summary <nombre>                  (imprime el resumen si existe)
#      jarvys-gradle.sh verify-suite <nombre> [playTests fullTests] (verifica ejecución real y XML frescos)
D=/root/Projects/Jarvys/app/.build-logs
mode="$1"; name="$2"; shift 2 2>/dev/null
LOG="$D/$name.log"; SUM="$D/$name.log.summary"
case "$mode" in
start)
  date +%s > "$D/$name.start"
  rm -f "$SUM"
  pkill -f KotlinCompileDaemon; pkill -f GradleDaemon; sleep 2
  setsid nohup bash -c '
    cd /root/Projects/Jarvys/app
    S=$(date +%s)
    env ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew --no-daemon \
      -Dorg.gradle.jvmargs="-Xmx1280m -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process \
      -Dorg.gradle.workers.max=2 '"$(printf '%q ' "$@")"' > "'"$LOG"'" 2>&1
    rc=$?
    E=$(date +%s)
    {
      echo "exit=$rc seconds=$((E-S))"
      grep -E "^BUILD (SUCCESSFUL|FAILED)" "'"$LOG"'" | tail -1
      grep -E "tests completed" "'"$LOG"'" | tail -2
      echo "-- tests rojos:"; grep -E " FAILED$" "'"$LOG"'" | sort -u | head -15
      echo "-- errores kotlin/java:"; grep -E "^e: |error:" "'"$LOG"'" | head -12 | cut -c1-220
      if [ $rc -ne 0 ] && ! grep -qE "^BUILD (SUCCESSFUL|FAILED)" "'"$LOG"'"; then echo "SIN LINEA BUILD y exit!=0: proceso muerto (OOM?)"; dmesg 2>/dev/null | grep -i "killed process" | tail -2 | cut -c1-140; fi
    } > "'"$SUM"'"
  ' > /dev/null 2>&1 < /dev/null &
  disown
  echo "lanzado $name; log=$LOG; espera con: $0 wait $name"
  ;;
verify-suite)
  python3 - "$name" "$LOG" "$SUM" "$D/$name.start" "${1:-474}" "${2:-501}" <<'PY'
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

name, log_path, summary_path, start_path = sys.argv[1:5]
expected = {"Play": int(sys.argv[5]), "Full": int(sys.argv[6])}
if not all(os.path.isfile(path) for path in (log_path, summary_path, start_path)):
    raise SystemExit(f"Falta log/summary/start para {name}")
summary = open(summary_path, encoding="utf-8").read()
log = open(log_path, encoding="utf-8", errors="replace").read()
if not re.search(r"^exit=0\b", summary, re.M) or not re.search(r"^BUILD SUCCESSFUL\b", log, re.M):
    raise SystemExit(f"Gradle no terminó correctamente: {summary.strip()}")
start = int(open(start_path, encoding="utf-8").read().strip())
for flavor, expected_tests in expected.items():
    task = f"test{flavor}DebugUnitTest"
    if not re.search(rf"^> Task :app:{task}$", log, re.M):
        raise SystemExit(f"No se encontró ejecución de :app:{task}; revisa UP-TO-DATE/log")
    files = glob.glob(f"/root/Projects/Jarvys/app/app/build/test-results/{task}/TEST-*.xml")
    if not files:
        raise SystemExit(f"No hay XML para {task}")
    stale = [path for path in files if os.path.getmtime(path) < start]
    if stale:
        raise SystemExit(f"XML anterior al inicio de la corrida {name}: {stale[:3]}")
    roots = [ET.parse(path).getroot() for path in files]
    counts = {key: sum(int(root.attrib.get(key, 0)) for root in roots)
              for key in ("tests", "failures", "errors", "skipped")}
    if counts["tests"] != expected_tests or counts["failures"] or counts["errors"]:
        raise SystemExit(f"Conteo inválido para {task}: esperado={expected_tests}; obtenido={counts}")
    print(f"{task}: ejecutada; XML frescos={len(files)}; expected_tests={expected_tests}; {counts}")
PY
  ;;
wait)
  for i in $(seq 1 108); do [ -s "$SUM" ] && { cat "$SUM"; exit 0; }; sleep 5; done
  echo "aun corriendo tras 9 min; vuelve a llamar wait $name"; tail -n 2 "$LOG" | cut -c1-160
  ;;
summary) cat "$SUM" 2>/dev/null || echo "sin resumen aun";;
*) echo "uso: start|wait|summary"; exit 2;;
esac
