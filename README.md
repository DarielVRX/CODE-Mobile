# Codeck

Editor de código nativo para Android, pensado para programar cómodo desde el
teléfono: explorador de archivos, editor con resaltado de sintaxis, teclado
propio orientado a código, ejecución integrada de Python y JavaScript, e
integración básica con Git — todo sin depender de Termux ni de un IDE externo.

Este proyecto está en fase de prueba: la idea de esta etapa es que lo instalen
y lo exploren sin guía previa, para evaluar qué tan intuitivo resulta. Por eso
no hay acá una guía de uso detallada — si algo no se entiende al primer
contacto, esa es información valiosa en sí misma.

## Primer uso

Al abrir la app por primera vez, Android pide el permiso de **"acceso a
todos los archivos"** (ver sección de permisos más abajo) — es necesario
para que el explorador y el editor puedan leer/escribir en cualquier carpeta
del almacenamiento. Se concede una sola vez desde Ajustes; sin él, el
explorador no puede listar ni abrir nada.

La pantalla inicial es el explorador de archivos.

## Qué incluye

- Explorador de archivos sobre el almacenamiento general del dispositivo.
- Editor de código con resaltado de sintaxis para Python y JavaScript.
- Teclado propio orientado a escribir código.
- Ejecución integrada de Python y JavaScript, con salida en una consola.
- Integración básica con Git (requiere un repositorio ya existente en el
  almacenamiento).

## Permisos utilizados

| Permiso | Para qué se usa |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` (y `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` como respaldo en versiones de Android que los requieren) | Leer y escribir archivos en cualquier carpeta del almacenamiento general del dispositivo, no solo en una carpeta propia de la app. Es lo que permite que el explorador funcione como un gestor de archivos real. |
| `INTERNET` | Operaciones de Git que necesitan red (`fetch`, `pull`, `push`, `clone`). |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Mantener viva una operación de Git en curso (por ejemplo, un `push` largo) aunque la app pase a segundo plano. |
| `POST_NOTIFICATIONS` | Mostrar el estado de esa operación de Git en una notificación mientras corre en segundo plano. |

Codeck no solicita permisos de cámara, micrófono, contactos, ubicación ni
ningún otro dato sensible. No incluye telemetría ni analítica: no se envía
ningún dato de uso a servidores propios ni de terceros.

## Detalles técnicos

- **Plataforma**: Android nativo, Kotlin.
- **Editor de texto**: subclase de `EditText` con resaltado por regex y
  autocompletado de pares de brackets/comillas.
- **Teclado**: implementación propia (no un IME de sistema).
- **Ejecución de código**: cada lenguaje corre en un runtime embebido dentro
  de la propia app (ver tabla de licencias), sin spawnear procesos externos
  ni depender de un entorno tipo Termux.
- **Git**: implementado sobre JGit (ver tabla de licencias) — no invoca al
  binario `git` del sistema.

## Licencias de terceros

Codeck usa las siguientes bibliotecas de código abierto. Se listan sus
licencias para referencia; ninguna de ellas impone restricciones sobre el
código que vos escribís o ejecutás con la app.

| Biblioteca | Uso | Licencia |
|---|---|---|
| [Chaquopy](https://chaquo.com/chaquopy/) | Ejecución de Python embebido | MIT. Desde la versión 12.0.1 Chaquopy es gratuito y de código abierto sin restricción de uso comercial (versiones anteriores tenían una licencia propia de pago para distribución cerrada; este proyecto usa la 17.0.0, ya sin esa restricción). |
| [Rhino](https://github.com/mozilla/rhino) | Ejecución de JavaScript embebido | Mozilla Public License 2.0 (MPL-2.0). |
| [JGit](https://www.eclipse.org/jgit/) | Operaciones de Git | Eclipse Distribution License 1.0 (EDL-1.0, variante BSD). |
| AndroidX / Jetpack (Core, AppCompat, Material, ConstraintLayout, RecyclerView, ViewPager2, DocumentFile, Security-Crypto) | Componentes estándar de UI y almacenamiento seguro | Apache License 2.0. |
| Kotlin Coroutines | Concurrencia (operaciones de Git/ejecución sin bloquear la UI) | Apache License 2.0. |

Este README no reemplaza el archivo de avisos de licencia completo
(`NOTICE`/`LICENSE` de cada dependencia, incluidos en el artefacto
compilado); se incluye acá como referencia rápida.

## Licencia de este proyecto

El código de Codeck en sí (no las bibliotecas de terceros de la tabla de
arriba) se distribuye bajo una licencia propia de código disponible, sin
redistribución: podés ver, compilar y modificar el código para uso personal,
pero no redistribuirlo ni publicar compilaciones propias derivadas de él. Ver
el archivo [`LICENSE`](./LICENSE) para el texto completo.

## Aviso legal

Codeck es un editor y entorno de ejecución de propósito general: no audita,
sanitiza ni restringe de ningún modo el código que el usuario escribe o
ejecuta con él. La app no se hace responsable por el contenido,
comportamiento o efectos del código ejecutado, ni por la pérdida de datos
derivada de operaciones de archivo o de Git realizadas a través de ella (es
responsabilidad del usuario mantener sus propias copias de seguridad).

El acceso de amplio alcance al almacenamiento (`MANAGE_EXTERNAL_STORAGE`) le
da a la app, y por lo tanto a cualquier código que el usuario ejecute desde
ella, la posibilidad de leer y modificar archivos fuera de su propia carpeta
— el mismo alcance que tendría cualquier gestor de archivos. Se recomienda
no abrir ni ejecutar código de origen no confiable con este permiso
concedido.
