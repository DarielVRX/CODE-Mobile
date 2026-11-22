package com.codemobile.exec

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.codemobile.files.FileRepository
import java.io.File
import java.util.concurrent.Callable

/**
 * Ejecuta scripts Python embebidos vía Chaquopy. No se valida nada antes de
 * ejecutar (ej. cierres de paréntesis pendientes): si el código tiene un error
 * de sintaxis, el traceback real de Python es la señal — se deja "crashear"
 * a propósito, por decisión explícita, en vez de duplicar advertencias.
 */
object PythonRunner {

    private var started = false

    fun ensureStarted(context: Context) {
        if (!started) {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            started = true
        }
    }

    /** Pequeña clase Python, definida como texto y ejecutada una sola vez
     *  (no por cada run()), cuyas instancias actúan como sys.stdin: solo
     *  implementa readline(), que es lo único que input() necesita (ver
     *  CPython: input() cae a sys.stdin.readline() cuando sys.stdin no es
     *  un tty real — y acá nunca lo es, corriendo embebido sin terminal).
     *  [on_readline] es un Callable de Kotlin pasado como PyObject: Python
     *  lo ve como una función normal invocable con __call__, sin hacer
     *  falta ningún archivo .py propio del proyecto. readline() DEBE
     *  devolver la línea CON su salto al final ("texto\n"), igual que un
     *  stdin real — si no, input() no encuentra el '\n' que espera y arma
     *  mal el resultado. */
    private const val STDIN_BRIDGE_SRC = """
class _StdinBridge:
    def __init__(self, on_readline):
        self._on_readline = on_readline
    def readline(self, *args):
        return self._on_readline()
    def read(self, *args):
        return self._on_readline()
    def isatty(self):
        return False
"""

    /** [onInputRequest] recibe, cada vez que el script bloquea en input(), el
     *  texto de stdout/stderr acumulado desde la última vez que se le avisó
     *  (nunca repetido) — así quien implementa el callback (EditorActivity)
     *  puede volcarlo a la consola junto con el prompt ANTES de bloquear
     *  esperando la respuesta, en vez de recién mostrar todo junto al final
     *  (ver ExecutionDispatcher: antes onResult solo se llamaba una vez al
     *  terminar, con outBuffer entero leído de punta a punta).
     *
     *  [sourceFile] es el .py activo en el editor (null si es un archivo
     *  "sin guardar" — sin ruta real no hay ninguna carpeta de proyecto
     *  que agregar a sys.path, ese caso no modifica sys.path, igual que
     *  siempre). Antes de este parámetro, exec(code) corría totalmente
     *  aislado: un "import otro_archivo_del_proyecto" nunca se resolvía
     *  (solo existía la stdlib empaquetada de Chaquopy), aunque la tecla G
     *  (ver GotoResolver) ya permitiera NAVEGAR a esa definición en el
     *  editor — confirmado como pendiente real, no supuesto, leyendo este
     *  mismo archivo antes del cambio. Se usa
     *  FileRepository.projectRootFor(sourceFile) (misma raíz que
     *  GotoResolver: repo git que contiene al archivo, o rootDir() entero
     *  si no hay repo) como entrada nueva de sys.path, insertada al
     *  principio (índice 0, mismo criterio que Python usa para el propio
     *  directorio del script al correr uno real) y removida siempre en el
     *  finally, para no acumular rutas entre ejecuciones sucesivas (cada
     *  run() debe dejar sys.path exactamente como lo encontró, se haya
     *  completado con éxito, con excepción, o el propio script haya hecho
     *  algo raro con sys.path — remove() por valor, no por índice, para
     *  no desincronizarse si el script insertó/sacó algo antes). */
    fun run(
        context: Context,
        code: String,
        sourceFile: File?,
        onInputRequest: (pendingOutput: String) -> String
    ): ExecutionResult {
        ensureStarted(context)
        val startTime = System.currentTimeMillis()

        val py = Python.getInstance()
        val builtins = py.getModule("builtins")
        val sys = py.getModule("sys")
        val io = py.getModule("io")

        val projectRootPath = sourceFile?.let { FileRepository.projectRootFor(it).absolutePath }
        if (projectRootPath != null) {
            sys.get("path")?.callAttr("insert", 0, projectRootPath)
        }

        // System.setOut()/setErr() de Java NO sirve: Chaquopy tiene su propio
        // sys.stdout del lado Python (print() escribe ahí, no en System.out de
        // Java), así que hay que redirigir sys.stdout/sys.stderr desde Python
        // mismo con io.StringIO(), y restaurarlos siempre al terminar.
        val originalStdout = sys.get("stdout")
        val originalStderr = sys.get("stderr")
        val originalStdin = sys.get("stdin")
        val outBuffer = io.callAttr("StringIO")
        val errBuffer = io.callAttr("StringIO")
        sys.put("stdout", outBuffer)
        sys.put("stderr", errBuffer)

        // Posición hasta donde ya se le mostró outBuffer al usuario (ver
        // readPendingOutput más abajo): StringIO no tiene "leer y vaciar" de
        // una, así que se lee todo con getvalue() y se recorta el prefijo ya
        // entregado en una llamada anterior, para no repetirlo en cada
        // input() sucesivo del mismo script.
        var outConsumedUpTo = 0

        fun readPendingOutput(): String {
            val full = outBuffer.callAttr("getvalue").toString()
            val pending = if (outConsumedUpTo < full.length) full.substring(outConsumedUpTo) else ""
            outConsumedUpTo = full.length
            return pending
        }

        // input() real necesita que sys.stdin también quede redirigido (no
        // solo stdout/stderr), o Python lanza EOFError al no tener una
        // fuente de la que leer. Se arma el stdin-bridge en un dict de
        // globals propio de este run (no el de bridgeGlobals a nivel de
        // objeto) para no filtrar estado entre ejecuciones distintas, se
        // instancia pasándole onInputRequest (que Python ve como una
        // función Java invocable) y se lo asigna a sys.stdin.
        // onInputRequest() BLOQUEA este hilo (el de ExecutionDispatcher,
        // nunca el de UI) hasta que el usuario responda — ver
        // ExecutionDispatcher/EditorActivity. Antes de bloquear, se le pasa
        // el output pendiente (que incluye el prompt que el script haya
        // impreso con print(..., end="") o como argumento de input()).
        val bridgeGlobals = builtins.callAttr("dict")
        builtins.callAttr("exec", STDIN_BRIDGE_SRC, bridgeGlobals)
        val stdinBridgeClass = bridgeGlobals.callAttr("get", "_StdinBridge")
        // PyObject.fromJava necesita un objeto Java cuya interfaz Chaquopy
        // sepa traducir a __call__() de Python. java.util.concurrent.Callable
        // es la interfaz "invocable sin argumentos" estándar que Chaquopy
        // reconoce para esto; la lambda Kotlin onInputRequest (tipo
        // kotlin.jvm.functions.Function1, una interfaz distinta y propia de
        // Kotlin) no tiene esa garantía, así que se envuelve explícitamente
        // acá en vez de confiar en que Chaquopy la detecte sola.
        val onReadlineCallable = PyObject.fromJava(Callable { onInputRequest(readPendingOutput()) })
        val stdinBridge = stdinBridgeClass?.call(onReadlineCallable)
        sys.put("stdin", stdinBridge)

        return try {
            // exec(code) sin un diccionario de globals explícito intenta inspeccionar
            // el frame del llamador (vía sys._getframe) para resolverlos — y llamado
            // desde Java, sin un frame Python real detrás, eso falla con un error de
            // frame no disponible. Pasar un dict nuevo evita esa inspección por completo.
            val globals = builtins.callAttr("dict")
            builtins.callAttr("exec", code, globals)

            val duration = System.currentTimeMillis() - startTime
            val errText = errBuffer.callAttr("getvalue").toString().ifBlank { null }
            // Solo lo producido DESPUÉS del último volcado a consola (si
            // hubo algún input() en el medio) — lo anterior ya se mostró en
            // readPendingOutput() antes de cada bloqueo, y repetirlo acá
            // duplicaría ese texto en la consola.
            ExecutionResult(readPendingOutput(), errText, duration)
        } catch (e: PyException) {
            val duration = System.currentTimeMillis() - startTime
            ExecutionResult(readPendingOutput(), e.message ?: e.toString(), duration)
        } finally {
            sys.put("stdout", originalStdout)
            sys.put("stderr", originalStderr)
            sys.put("stdin", originalStdin)
            if (projectRootPath != null) {
                // remove() por valor: si el string ya no está (ej. el
                // propio script lo sacó de sys.path), Python lanza
                // ValueError — se ignora a propósito, el objetivo es solo
                // "no dejarlo puesto si sigue ahí", no garantizar su
                // presencia.
                runCatching { sys.get("path")?.callAttr("remove", projectRootPath) }
            }
        }
    }
}
