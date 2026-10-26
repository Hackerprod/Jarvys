# S6 — linux_status: diagnóstico independiente de lectura real

2026-10-26. Inspección de fuente únicamente; sin ejecutar Linux, instalar SDK, modificar producción ni publicar.

## Conclusión

**No es posible conservar la misma prueba viva del rootfs mutable y garantizar a la vez cero ejecución arbitraria de ese rootfs solo cambiando login/PATH/rutas/recursos.** Un seam host-only sí evita ejecución guest, pero cambia el contrato: informa estado instalado/metadatos observados y readiness viva **no comprobada**, nunca convierte READY guardado en un probe READY actual. No se propone reemplazar status por execgate ni añadir permisos.

## Cadena observada

1. `LinuxStatusTool.statusJson`: lee state y, cuando phase es READY, llama runtime.probe. Su descripción promete estado de instalación y resultado de probe PRoot.
2. `LinuxEnvironment.probe`: LinuxProbe ejecuta comando mediante exec con timeout nulo y token uncancellable, no el token recibido por status.
3. `LinuxProbe.probe`: intenta setExecutable en binarios host; comando `uname -m; id -u; grep ... /etc/os-release; echo ok`; evalúa salida/exit para declarar READY.
4. `LinuxEnvironment.execute`: prepara certificados, workspace y temporales, chmod de ejecutables nativos, launcher y limpieza. `ProotLauncher.plan` crea copia libtalloc y selecciona shell guest.
5. `ProotLauncher.buildArgv`: rootfs mutable, `/workspace` enlazado, `/dev`, `/proc`, `/sys`; ejecuta guest `/usr/bin/env -i`, PATH con `/usr/local` delante, shell `/bin/bash` o `/bin/sh` con `-lc` y eval.
6. Construir `LinuxEnvironment` crea por defecto `RootfsInstaller`. Su init hace mkdir, recupera/renombra previous, borra staging y puede publicar estado corregido. Ni siquiera evitar probe basta si el status instancia esa ruta.

## Por qué no-login + absolutos no bastan

- `-c`/no perfiles elimina un vector de startup; PATH fijo elimina redirección por búsqueda. No autentica `/usr/bin/env`, el shell, `uname`, `id`, `grep` ni sus symlinks, intérprete ELF, librerías o configuración de carga guest.
- Un binario sustituido en una ruta absoluta puede escribir, abrir red, lanzar procesos o imprimir exactamente el probe esperado. Usar builtins conserva shell mutable. `env -i` también empieza ejecutando un binario mutable; no prueba aislamiento.
- Hash previo de archivos sueltos no fija la cadena de dependencias ni evita sustitución concurrente entre verificación y ejecución. Un snapshot verificado/inmutable con ejecutables confiables sería otra arquitectura; no certificaría sin más el rootfs activo.
- Quitar workspace bind reduce exposición específica, no impide efectos en rootfs y accesos permitidos por PRoot. Límites de tiempo/salida/CPU acotan recursos, no autoridad ni efectos; un efecto puede ocurrir antes del timeout. PRoot no es una frontera de seguridad.
- Incluso un helper empaquetado y estático probaría la ejecución de ese helper/PRoot, no que shell/comandos del rootfs mutable sean sanos o funcionen. No confundir dichas garantías.

## Seam host-only y cambio semántico necesario

Propuesta de lectura de metadata dedicada, sin construir RootfsInstaller, LinuxEnvironment ni ProotLauncher: leer snapshot del estado guardado, ABI del host, existencia/tipo/propiedades estructurales del rootfs y componentes empaquetados. El patrón `FlavorLinuxTools.ProjectInspectionRuntime` ya evita constructor installer para project status y sirve de referencia, no de certificación completa.

Separar claramente `installation_phase` guardada, observaciones actuales y `live_probe=not_run`/readiness desconocida. Si se conserva el campo histórico status=READY por compatibilidad, debe quedar inequívocamente documentado como fase guardada; no emitir probe.ready=true. Lectura no puede detectar SELinux/ptrace/loader/runtime funcional a tiempo real. No inventar un último probe con fecha si no existe recibo durable de él; tampoco presentar metadata de distro como validación de ejecución.

La metadata es dato no confiable: no source/eval de os-release, no ejecución para medir versión, no recuperación/reparación. Validar ancla y tipos; no seguir symlinks fuera de raíz ni leer FIFO/device. Para garantías contra mutación concurrente, usar apertura segura/no-follow y comprobar identidad/tipo del descriptor, no solo canonicalPath antes de abrir. Fallo/cambio concurrente => observación unavailable/unknown, no reparación. Usar presupuestos de lectura ya establecidos o justificar los derivados del formato; sin topes arbitrarios nuevos. "Cero writes" significa cero escrituras iniciadas por la aplicación, no prometer ausencia de atime del filesystem al leer.

No conservar silenciosamente la descripción que promete probe vivo. Si esa semántica debe mantenerse exactamente, la exigencia de lectura pura queda sin resolver; no anunciar una solución parcial como segura.

## Pruebas necesarias (sin ejecutar payloads en esta investigación)

- Fake process executor y constructor installer que fallen si se llaman: status host-only no llama ninguno; tampoco chmod/mkdir/certificates/copy/delete/rename ni runtime.probe/exec.
- Fixtures con perfiles de login, PATH/binarios guest/env/shell sustituidos y enlaces a loaders/librerías: solo archivos inertes; status no los ejecuta ni produce marcas. Comprobar entradas/snapshots de filesystem, no confiar únicamente en que no apareció un marker.
- FIFO/device/symlink afuera/cambio entre lookup y lectura: rechazo o unknown, sin bloqueo ni escape; no reintento reparador.
- State READY con rootfs ausente/corrupto o binarios alterados: estado almacenado y observación actual diferenciados; nunca readiness viva certificada.
- Sin instalación/ABI incompatible/interrupción de instalación: consultar no crea directorios, no limpia staging ni cambia estado. Cancelación respeta lectura y no lanza proceso.
- Contrato UI/tool/prompt: nadie interpreta fase READY como probe nuevo. Pruebas actuales que esperan llamada runtime.probe deben revisarse como cambio semántico deliberado, no acomodarse silenciosamente.

**Pendiente:** decisión de contrato host-only frente a mantener probe vivo. No implementación ni PASS; no solución basada únicamente en no-login/absolutos/timeout ni ampliación de permisos.

## Propuesta breve para decisión del dueño

- **Actual:** status puede lanzar código del Linux modificable y preparar/reconciliar archivos; no es una lectura pura. No se cambia ahora producto ni permiso: el gap queda abierto.
- **Alternativa propuesta:** status host-only muestra «instalado»/fase guardada y observaciones, con «funcionamiento no verificado ahora». No instancia el constructor que recupera/borra/escribe instalación.
- **Verificación viva separada:** mantenerla solo dentro de una acción ya autorizada cuyo contrato incluya ejecutar probe (por ejemplo setup cuando corresponda), con su recibo/admisión y resultado real. Si se desea una acción nueva de diagnóstico, pedir decisión de producto/autorización; no añadirla ni ampliar grants automáticamente. Un probe anterior no certifica el estado actual después de mutaciones.
- **Pruebas mínimas:** status dispara cero executor/installer/operaciones de escritura aun con perfiles/PATH/binarios guest alterados; metadata peligrosa se rechaza; READY guardado no se muestra como probe vivo; acción autorizada conserva comprobación real y recibo sin que status la invoque.

Estado tras decisión de coordinación: **mantener gap abierto, sin implementación**, pendiente de decisión explícita sobre semántica del producto.
