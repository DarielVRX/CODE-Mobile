package com.codemobile.files

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Acceso simple al almacenamiento general del dispositivo (no SAF/sandbox).
 * Requiere MANAGE_EXTERNAL_STORAGE, concedido manualmente por el usuario en Ajustes
 * (ver PermissionHelper). Arranca en el almacenamiento raíz compartido.
 */
object FileRepository {

    fun rootDir(): File = Environment.getExternalStorageDirectory()

    /** true si [target] es una carpeta y contiene un ".git" directamente
     *  adentro (raíz de un repositorio). Solo mira un nivel, no sube ni
     *  baja — marcar visualmente solo la raíz del repo es una decisión
     *  explícita (ver FileExplorerAdapter): estando ya dentro de un repo es
     *  evidente por el propio contexto de navegación, no hace falta
     *  propagar la marca a cada subcarpeta. */
    fun isGitRepoRoot(target: File): Boolean =
        target.isDirectory && File(target, ".git").exists()

    /** Raíz del repo que contiene a [dir], buscando desde el propio [dir]
     *  hacia arriba (a diferencia de BackupManager.findRepoRoot, que
     *  arranca en el padre de un ARCHIVO y nunca chequea el archivo mismo
     *  — acá [dir] puede ser justo la raíz del repo). Null si [dir] no
     *  está dentro de ningún repo. Usado para el fondo grande del
     *  explorador (ver ExplorerController): debe seguir detectándose en
     *  cualquier subcarpeta dentro del repo, no solo en su raíz exacta. */
    fun findRepoRoot(dir: File): File? {
        var current: File? = dir
        while (current != null) {
            if (isGitRepoRoot(current)) return current
            current = current.parentFile
        }
        return null
    }

    /** "Raíz de proyecto" para resolver imports/módulos de [file] (ver
     *  GotoResolver, y PythonRunner.run al ejecutar): la raíz del repo git
     *  que contiene a [file], o rootDir() entero si [file] no está dentro
     *  de ningún repo. No existe otra noción de "raíz de proyecto" en el
     *  código — se reutiliza el mismo mecanismo que ya usa el explorador
     *  (findRepoRoot) en vez de inventar un concepto nuevo. */
    fun projectRootFor(file: File): File =
        findRepoRoot(file.parentFile ?: file) ?: rootDir()

    /** true si [target] es un archivo/carpeta oculto — nombre con punto
     *  inicial (convención Unix/Android que todo explorador de archivos
     *  respeta) o con el atributo real de "hidden" del sistema de archivos
     *  (File.isHidden ya cubre ambos casos en Android). Usado por
     *  FileExplorerAdapter para darles un diseño visual distinto (ver ahí),
     *  ya no para excluirlos de list()/searchRecursive (ver esos
     *  comentarios: antes sí los filtraban, ahora siempre se listan). */
    fun isHiddenEntry(target: File): Boolean = target.isHidden || target.name.startsWith(".")

    /** Lista carpetas y archivos de [dir], carpetas primero, orden alfabético.
     *
     *  BUG encontrado y corregido (faltaba la posibilidad de ver archivos y
     *  carpetas ocultos de forma permanente): antes filtraba !it.isHidden,
     *  así que un archivo o carpeta oculta (nombre con punto inicial en
     *  Unix, o con el
     *  atributo "hidden" real que File.isHidden también respeta en
     *  Android/Linux) nunca aparecía en el explorador, sin ninguna forma de
     *  verlo. Ahora se listan siempre — distinguirlos visualmente queda del
     *  lado de FileExplorerAdapter (ver isHiddenEntry ahí), no de filtrarlos
     *  acá. */
    fun list(dir: File): List<File> {
        val children = dir.listFiles() ?: return emptyList()
        return children
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /** Busca recursivamente dentro de [dir] (incluyendo subcarpetas) todo
     *  archivo/carpeta cuyo nombre contenga [query] (sin distinguir
     *  mayúsculas/minúsculas). [query] vacío o solo espacios devuelve lista
     *  vacía en vez de listar todo — el buscador del explorador usa list()
     *  normal cuando no hay texto, así que una búsqueda
     *  vacía nunca debería llegar hasta aquí, pero se cubre el caso igual
     *  para que la función sea segura de llamar con cualquier input.
     *  Mismo criterio que list() (ver su comentario): ya no excluye
     *  ocultos, entra a cualquier carpeta y encuentra cualquier archivo. */
    fun searchRecursive(dir: File, query: String): List<File> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val needle = trimmed.lowercase()
        val results = mutableListOf<File>()
        fun walk(current: File) {
            val children = current.listFiles() ?: return
            for (child in children) {
                if (child.name.lowercase().contains(needle)) {
                    results.add(child)
                }
                if (child.isDirectory) {
                    walk(child)
                }
            }
        }
        walk(dir)
        return results.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /** Usado solo por saveAs (guardar-como de un archivo sin nombre):
     *  ahí sí es correcto reusar el archivo si ya existe, porque "guardar
     *  como" sobreescribe a propósito. Crear un archivo nuevo desde el
     *  explorador usa createFileChecked en su lugar, que no permite
     *  pisar algo existente. */
    fun createFile(dir: File, name: String): File {
        val f = File(dir, name)
        if (!f.exists()) {
            f.createNewFile()
        }
        return f
    }

    /** Resultado explícito de crear una carpeta: a diferencia
     *  de createFile/saveAs, que silenciosamente reusan el archivo si ya
     *  existe, crear una carpeta por nombre desde el explorador necesita que
     *  la UI distinga "ya existe algo con ese nombre" de "el nombre es
     *  invalido" de "el sistema de archivos lo rechazó" — cada caso amerita
     *  un mensaje distinto al usuario. */
    sealed class CreateDirectoryResult {
        data class Success(val dir: File) : CreateDirectoryResult()
        data class InvalidName(val reason: String) : CreateDirectoryResult()
        object AlreadyExists : CreateDirectoryResult()
        object IoFailure : CreateDirectoryResult()
    }

    /** Mismo shape que CreateDirectoryResult, para crear un archivo nuevo
     *  desde el explorador: a diferencia de createFile (pensado para
     *  saveAs, que sí debe poder reusar/sobreescribir), esta variante NO
     *  abre el archivo existente si el nombre ya está en uso — eso
     *  escondía el problema en vez de prevenirlo, porque el usuario podía
     *  creer que creó un archivo nuevo cuando en realidad abrió uno
     *  preexistente con contenido ya escrito. */
    sealed class CreateFileResult {
        data class Success(val file: File) : CreateFileResult()
        data class InvalidName(val reason: String) : CreateFileResult()
        object AlreadyExists : CreateFileResult()
        object IoFailure : CreateFileResult()
    }

    /** Crea un archivo nuevo de nombre [name] dentro de [parentDir],
     *  igual de estricto con el nombre que createDirectory (ver
     *  invalidNameReason) y sin pisar un archivo existente. */
    fun createFileChecked(parentDir: File, name: String): CreateFileResult {
        val trimmed = name.trim()
        invalidNameReason(trimmed)?.let { return CreateFileResult.InvalidName(it) }
        val target = File(parentDir, trimmed)
        if (target.exists()) {
            return CreateFileResult.AlreadyExists
        }
        return try {
            if (target.createNewFile()) {
                CreateFileResult.Success(target)
            } else {
                CreateFileResult.IoFailure
            }
        } catch (e: java.io.IOException) {
            CreateFileResult.IoFailure
        }
    }

    /** Mismo shape que CreateDirectoryResult, para renombrar (archivo o
     *  carpeta, misma operación de filesystem: File.renameTo). Se declara
     *  aparte (no se reusa CreateDirectoryResult) porque Success acá carga
     *  el File renombrado real, y semánticamente "crear" y "renombrar" son
     *  operaciones distintas aunque comparan el mismo tipo de validación de
     *  nombre — conflar los dos resultados bajo un nombre sería confuso en
     *  el call site. */
    sealed class RenameResult {
        data class Success(val renamed: File) : RenameResult()
        data class InvalidName(val reason: String) : RenameResult()
        object AlreadyExists : RenameResult()
        object IoFailure : RenameResult()
    }

    /** Nombres reservados por convención en este explorador: no técnicamente
     *  inválidos para el sistema de archivos, pero confusos o redundantes si
     *  se permitieran acá (".", ".." ya tienen significado especial de
     *  navegación en cualquier explorador). Compartido entre createDirectory
     *  y rename: ambas operaciones aceptan/rechazan el mismo tipo de nombre. */
    private val RESERVED_NAMES = setOf(".", "..")

    /** Valida un nombre de archivo/carpeta ingresado por el usuario, antes de
     *  tocar el disco. Null si es válido; el motivo de rechazo si no.
     *  Compartida por createDirectory y rename para no duplicar las mismas
     *  tres reglas en dos lugares. */
    private fun invalidNameReason(trimmedName: String): String? = when {
        trimmedName.isEmpty() -> "Name can't be empty"
        trimmedName.contains('/') || trimmedName.contains('\\') -> "Name can't contain / or \\"
        trimmedName in RESERVED_NAMES -> "\"$trimmedName\" is a reserved name"
        else -> null
    }

    /** Crea una subcarpeta de [name] dentro de [parentDir]. Valida el nombre
     *  antes de tocar el disco: ni vacío, ni con separadores de ruta (evita
     *  que un nombre como "a/b" cree una ruta anidada por accidente o se
     *  escape de parentDir con "../algo"), ni los nombres reservados de
     *  navegación. */
    fun createDirectory(parentDir: File, name: String): CreateDirectoryResult {
        val trimmed = name.trim()
        invalidNameReason(trimmed)?.let { return CreateDirectoryResult.InvalidName(it) }
        val target = File(parentDir, trimmed)
        if (target.exists()) {
            return CreateDirectoryResult.AlreadyExists
        }
        return if (target.mkdir()) {
            CreateDirectoryResult.Success(target)
        } else {
            CreateDirectoryResult.IoFailure
        }
    }

    /** Renombra [target] (archivo o carpeta, misma operación para ambos) a
     *  [newName] dentro del mismo directorio padre. Mismas validaciones de
     *  nombre que createDirectory. Caso particular que esta función NO
     *  resuelve: si [target] está abierto en una pestaña (OpenFile.file ==
     *  target), quien llama debe actualizar esa referencia después de un
     *  Success — ver EditorActivity.renameExplorerItem, que sincroniza
     *  OpenFile.file/displayName/extension si corresponde. Caso especial:
     *  renombrar a exactamente el mismo nombre actual es un no-op (Success
     *  con el mismo File) en vez de AlreadyExists — un diálogo de renombrar
     *  que el usuario cierra sin cambiar nada no debería tratarse como error. */
    fun rename(target: File, newName: String): RenameResult {
        val trimmed = newName.trim()
        invalidNameReason(trimmed)?.let { return RenameResult.InvalidName(it) }
        if (trimmed == target.name) {
            return RenameResult.Success(target)
        }
        val destination = File(target.parentFile, trimmed)
        if (destination.exists()) {
            return RenameResult.AlreadyExists
        }
        return if (target.renameTo(destination)) {
            RenameResult.Success(destination)
        } else {
            RenameResult.IoFailure
        }
    }

    sealed class DeleteResult {
        data class Success(val deletedCount: Int) : DeleteResult()
        object IoFailure : DeleteResult()
    }

    /** Cuenta cuántos archivos (no carpetas) hay dentro de [target], contando
     *  [target] mismo como 1 si es un archivo suelto. Usado para el mensaje
     *  de confirmación antes de borrar ("¿eliminar carpeta 'X' y sus N
     *  archivos?"), para que el usuario sepa el alcance real del borrado
     *  recursivo antes de confirmar. */
    fun countFiles(target: File): Int {
        if (!target.isDirectory) return 1
        return target.listFiles()?.sumOf { countFiles(it) } ?: 0
    }

    /** Elimina [target] (archivo o carpeta). Si es carpeta, borra todo su
     *  contenido de forma recursiva sin advertencias adicionales por
     *  archivo — la única confirmación es la que pide EditorActivity antes
     *  de llamar a esta función, mostrando el conteo de countFiles(). */
    fun delete(target: File): DeleteResult {
        val count = countFiles(target)
        return if (target.deleteRecursively()) {
            DeleteResult.Success(count)
        } else {
            DeleteResult.IoFailure
        }
    }

    sealed class CopyResult {
        data class Success(val copied: File) : CopyResult()
        object AlreadyExists : CopyResult()
        /** [target] es una carpeta y [destDir] es ella misma o está adentro
         *  de ella (ej. copiar "carpetaA" dentro de "carpetaA/sub"), lo cual
         *  crearía una recursión infinita si se permitiera. */
        object DestinationInsideTarget : CopyResult()
        object IoFailure : CopyResult()
    }

    sealed class MoveResult {
        data class Success(val moved: File) : MoveResult()
        object AlreadyExists : MoveResult()
        object DestinationInsideTarget : MoveResult()
        object IoFailure : MoveResult()
    }

    /** true si [destDir] es [target] mismo, o está contenido dentro de
     *  [target] (comparando rutas absolutas con separador, para no
     *  confundir "carpetaA" con "carpetaAB" por un prefijo parcial). Cubre
     *  tanto copiar/mover una carpeta a sí misma como a cualquier
     *  subcarpeta propia. Para un [target] que es archivo, siempre false
     *  (un archivo no puede "contener" un destino). */
    private fun isDestinationInsideTarget(target: File, destDir: File): Boolean {
        if (!target.isDirectory) return false
        val targetPath = target.absolutePath
        val destPath = destDir.absolutePath
        return destPath == targetPath || destPath.startsWith(targetPath + File.separator)
    }

    /** Copia recursivamente [src] (archivo u carpeta con todo su contenido)
     *  dentro de [dst], que debe ser la ruta completa de destino (no el
     *  directorio contenedor). Privada: no valida nada por sí misma, los
     *  casos de error ya se descartaron en copy() antes de llamarla. */
    private fun copyRecursively(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdir()
            src.listFiles()?.forEach { child ->
                copyRecursively(child, File(dst, child.name))
            }
        } else {
            src.copyTo(dst)
        }
    }

    /** Copia [target] (archivo o carpeta) dentro de [destDir], conservando
     *  el mismo nombre. No sobrescribe: si ya existe algo con ese nombre en
     *  destDir, devuelve AlreadyExists (quien llama decide si eso cuenta
     *  como error para la operación en lote o no, ver EditorActivity). */
    fun copy(target: File, destDir: File): CopyResult {
        if (isDestinationInsideTarget(target, destDir)) {
            return CopyResult.DestinationInsideTarget
        }
        val destination = File(destDir, target.name)
        if (destination.exists()) {
            return CopyResult.AlreadyExists
        }
        return try {
            copyRecursively(target, destination)
            CopyResult.Success(destination)
        } catch (e: Exception) {
            CopyResult.IoFailure
        }
    }

    /** Mueve [target] (archivo o carpeta) dentro de [destDir], conservando
     *  el mismo nombre. Intenta File.renameTo primero (operación atómica
     *  cuando origen y destino están en el mismo volumen, caso típico en
     *  este almacenamiento compartido); si falla, recurre a copiar y luego
     *  borrar el original, para cubrir el caso de volúmenes distintos. */
    fun move(target: File, destDir: File): MoveResult {
        if (isDestinationInsideTarget(target, destDir)) {
            return MoveResult.DestinationInsideTarget
        }
        val destination = File(destDir, target.name)
        if (destination.exists()) {
            return MoveResult.AlreadyExists
        }
        if (target.renameTo(destination)) {
            return MoveResult.Success(destination)
        }
        return try {
            copyRecursively(target, destination)
            if (target.deleteRecursively()) {
                MoveResult.Success(destination)
            } else {
                // La copia quedó hecha pero no se pudo borrar el original:
                // se deja la copia (mejor un duplicado que perder el
                // archivo) y se reporta el fallo para que el usuario revise.
                MoveResult.IoFailure
            }
        } catch (e: Exception) {
            MoveResult.IoFailure
        }
    }

    /** Escribe [content] a [target] de forma atómica: primero a un archivo
     *  temporal EN LA MISMA CARPETA (el rename atómico solo se garantiza
     *  dentro del mismo filesystem/partición — un temp en otro lado, como
     *  cacheDir, podría degradar a copia+borrado) y luego lo renombra sobre
     *  [target]. Escribir directo con target.writeText(...) truncaría el
     *  archivo real ANTES de escribir el contenido nuevo: si el proceso
     *  muere a mitad de camino (batería, memoria, I/O), el archivo
     *  quedaría truncado/corrupto, perdiendo tanto lo nuevo como lo viejo.
     *  Con el temp+rename, el peor caso si el proceso muere a mitad de la
     *  escritura es un .tmp incompleto sin tocar; [target] nunca pasa por
     *  un estado intermedio corrupto. */
    private fun writeAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp${System.nanoTime()}")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // renameTo puede fallar en algunos filesystems/condiciones raras
            // (ver su doc: no garantiza atomicidad entre TODOS los
            // filesystems posibles, solo en la inmensa mayoría de los
            // casos reales en Android). Fallback explícito en vez de dejar
            // target sin escribir: ya no es atómico en este camino, pero
            // sigue siendo mejor que no escribir nada.
            target.writeText(content)
            tmp.delete()
        }
    }

    fun save(context: Context, openFile: OpenFile) {
        val f = openFile.file ?: return
        writeAtomically(f, openFile.content.toString())
        openFile.isDirty = false
        openFile.savedContent = openFile.content.toString()
        // El archivo real ya quedó al día: el backup de esta sesión deja de
        // hacer falta, así que se limpia al guardar con Save.
        // BackupManager.findRepoRoot sube desde la carpeta del archivo, no
        // asume que openFile ya lo tenga cacheado.
        val repoRoot = BackupManager.findRepoRoot(f)
        val strategy = repoRoot?.let { BackupManager.savedStrategyFor(context, it) }
        BackupManager.deleteBackup(context, f, repoRoot, strategy)
    }

    /** Guarda un archivo nuevo (sin File asociado aún) en [dir] con el nombre dado. */
    fun saveAs(context: Context, openFile: OpenFile, dir: File, fileName: String): File {
        val f = createFile(dir, fileName)
        writeAtomically(f, openFile.content.toString())
        openFile.file = f
        openFile.displayName = fileName.substringBeforeLast('.', fileName)
        openFile.extension = fileName.substringAfterLast('.', "")
        openFile.isDirty = false
        openFile.savedContent = openFile.content.toString()
        val repoRoot = BackupManager.findRepoRoot(f)
        val strategy = repoRoot?.let { BackupManager.savedStrategyFor(context, it) }
        BackupManager.deleteBackup(context, f, repoRoot, strategy)
        return f
    }

    /** Extensiones reconocidas para elegir comportamiento de autoindentación/
     *  resaltado Y QUÉ RUNNER usa el botón Run (ver ExecutionDispatcher).
     *  HTML abre un preview en un panel WebView separado; SQL corre contra
     *  un SQLite persistente por archivo; CSS no es ejecutable por sí solo
     *  (solo tiene sentido dentro de un HTML que lo referencie), así que
     *  Run sobre un .css cae en PLAIN. */
    enum class Lang { PYTHON, JAVASCRIPT, HTML, SQL, KOTLIN, PLAIN }

    fun langFor(extension: String): Lang = when (extension.lowercase()) {
        "py" -> Lang.PYTHON
        "js", "mjs", "cjs" -> Lang.JAVASCRIPT
        "html", "htm" -> Lang.HTML
        "sql" -> Lang.SQL
        "kt", "kts" -> Lang.KOTLIN
        else -> Lang.PLAIN
    }

    /** Resuelve un nombre de archivo que no choque con nada existente en
     *  [dir], agregando un sufijo numérico entre paréntesis antes de la
     *  extensión si [baseName] ya está en uso — mismo criterio que
     *  cualquier navegador/gestor de descargas ("artifact.zip",
     *  "artifact (1).zip", "artifact (2).zip", ...). A diferencia de
     *  createFileChecked/AlreadyExists (que informa el conflicto para que
     *  el usuario decida), esta función es para un flujo sin intervención
     *  humana en el momento del conflicto — ver ActionsPanelController,
     *  descarga de artifacts: ahí no tiene sentido interrumpir una
     *  descarga en curso para preguntar un nombre nuevo. Solo resuelve el
     *  nombre; no crea el archivo. */
    fun uniqueFileIn(dir: File, baseName: String): File {
        val candidate = File(dir, baseName)
        if (!candidate.exists()) return candidate
        val extension = baseName.substringAfterLast('.', "")
        val stem = if (extension.isEmpty()) baseName else baseName.removeSuffix(".$extension")
        var index = 1
        while (true) {
            val name = if (extension.isEmpty()) "$stem ($index)" else "$stem ($index).$extension"
            val next = File(dir, name)
            if (!next.exists()) return next
            index++
        }
    }

    /** Suma recursivamente el tamaño en bytes de [target]. Si es archivo,
     *  su tamaño en disco; si es carpeta, la suma de todo lo que contiene.
     *  Usado por el diálogo de "Info" del explorador. */
    fun totalSizeBytes(target: File): Long {
        if (!target.isDirectory) return target.length()
        return target.listFiles()?.sumOf { totalSizeBytes(it) } ?: 0L
    }

    /** Formatea [bytes] a una unidad legible (B/KB/MB/GB), con un decimal a
     *  partir de KB. No usa fracciones de B (no tiene sentido mostrar
     *  "512.0 B"). Usado por el diálogo de "Info" del explorador. */
    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.1f GB".format(gb)
    }
}
