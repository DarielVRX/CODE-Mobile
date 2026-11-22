package com.codemobile.files

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Backups automáticos, separados del guardado real: un autoguardado que
 * escribiera directo al archivo real no tendría "vuelta atrás" si el
 * usuario rompe algo sin querer, así que el autoguardado solo escribe a un
 * archivo .bak paralelo, y el archivo real solo se toca con el botón Save
 * explícito (ver EditorActivity.backupCurrentFile() / FileRepository.save()).
 *
 * El dónde vive ese .bak es una decisión POR REPOSITORIO Git (identificado
 * por su carpeta raíz, la que tiene ".git"), preguntada una sola vez por
 * repo la primera vez que hace falta (ver EditorActivity.
 * ensureBackupStrategyThenCheckRecovery), porque guardar el .bak junto al
 * archivo real "ensucia" un repo Git si no se declara en su .gitignore —
 * hay que evitar que un backup termine en el remoto. Fuera de un repo Git
 * no hay nada que preguntar: el .bak va junto al archivo sin más vuelta,
 * porque ahí no hay riesgo de filtrar nada a ningún lado.
 */
object BackupManager {

    enum class Strategy {
        /** Junto al archivo real, con una entrada en el .gitignore del repo
         *  para que Git lo ignore. */
        IN_REPO_GITIGNORE,
        /** Almacenamiento privado de la app: invisible para el usuario y
         *  para cualquier explorador de archivos, se borra solo si se
         *  desinstala la app. */
        APP_PRIVATE,
        /** Una única carpeta visible global (no una por repo dentro de cada
         *  proyecto), con subcarpetas por repo adentro. */
        VISIBLE_GLOBAL
    }

    private const val BACKUP_EXTENSION = "codeckbak"
    private const val PREFS_NAME = "codemobile_backup_prefs"
    private const val PREFS_KEY_PREFIX = "strategy_"

    /** Carpeta visible global fija: una sola ubicación compartida por todos
     *  los repos, no una carpeta de backups esparcida dentro de cada
     *  proyecto. Las subcarpetas por repo viven DENTRO de esta, nombradas
     *  por la carpeta raíz del repo (ver backupFileFor). */
    private fun visibleGlobalRoot(): File =
        File(Environment.getExternalStorageDirectory(), "CodeMobile/backups")

    /** Sube desde [file] (el archivo que se está editando) buscando una
     *  carpeta que contenga ".git" directamente adentro. A diferencia de
     *  FileRepository.isGitRepoRoot (que solo mira UN nivel, la carpeta que
     *  ya se sabe que es candidata), esta función camina hacia arriba desde
     *  cualquier profundidad — un archivo puede estar varias subcarpetas
     *  adentro de la raíz real del repo. Devuelve null si ningún ancestro
     *  es raíz de un repo (el archivo no vive dentro de ningún repo Git). */
    fun findRepoRoot(file: File): File? {
        var dir: File? = file.parentFile
        while (dir != null) {
            if (FileRepository.isGitRepoRoot(dir)) return dir
            dir = dir.parentFile
        }
        return null
    }

    /** Estrategia ya elegida para el repo cuya raíz es [repoRoot], o null si
     *  todavía no se preguntó (primera vez que se abre un archivo de ESE
     *  repo). Identificado por su path absoluto: estable mientras el repo
     *  no se mueva de carpeta. */
    fun savedStrategyFor(context: Context, repoRoot: File): Strategy? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(PREFS_KEY_PREFIX + repoRoot.absolutePath, null) ?: return null
        return Strategy.entries.find { it.name == name }
    }

    fun saveStrategyFor(context: Context, repoRoot: File, strategy: Strategy) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREFS_KEY_PREFIX + repoRoot.absolutePath, strategy.name).apply()
        if (strategy == Strategy.IN_REPO_GITIGNORE) {
            ensureGitignoreEntry(repoRoot)
        }
    }

    /** Agrega "*.codeckbak" al final del .gitignore de [repoRoot] si no está
     *  ya cubierto. Nunca reordena ni toca el resto del archivo — solo
     *  añade, con su propio comentario para que quede claro de dónde salió
     *  si el usuario la ve después. Si el .gitignore no existe, lo crea con
     *  únicamente esta entrada. No duplica: si ya hay una línea idéntica
     *  (con o sin el comentario), no vuelve a escribir nada. */
    private fun ensureGitignoreEntry(repoRoot: File) {
        val pattern = "*.$BACKUP_EXTENSION"
        val gitignore = File(repoRoot, ".gitignore")
        val existingLines = if (gitignore.exists()) gitignore.readLines() else emptyList()
        if (existingLines.any { it.trim() == pattern }) return

        val needsLeadingNewline = gitignore.exists() &&
            gitignore.length() > 0 &&
            !gitignore.readText().endsWith("\n")
        val block = buildString {
            if (needsLeadingNewline) append('\n')
            if (existingLines.isNotEmpty()) append('\n')
            append("# CodeMobile: automatic backups\n")
            append(pattern)
            append('\n')
        }
        gitignore.appendText(block)
    }

    /** Ruta del .bak correspondiente a [file], según la estrategia vigente
     *  para su repo (o junto al archivo si no está dentro de ningún repo
     *  Git — ver findRepoRoot). [repoRoot]/[strategy] se pasan ya
     *  resueltos (en vez de recalcularlos acá) porque el caller
     *  (EditorActivity) ya los necesita de todos modos para decidir si hay
     *  que mostrar el diálogo de elección. */
    fun backupFileFor(context: Context, file: File, repoRoot: File?, strategy: Strategy?): File {
        val backupName = "${file.name}.$BACKUP_EXTENSION"
        if (repoRoot == null) {
            // Sin repo Git de por medio: siempre junto al archivo, sin
            // preguntar nada (ver doc de clase).
            return File(file.parentFile, backupName)
        }
        return when (strategy) {
            Strategy.APP_PRIVATE -> {
                // getExternalFilesDir ya es privado de la app (no requiere
                // MANAGE_EXTERNAL_STORAGE, y Android lo borra solo al
                // desinstalar). Se replica la ruta relativa del archivo
                // dentro del repo para no colisionar nombres iguales en
                // subcarpetas distintas.
                val relative = file.relativeToOrSelf(repoRoot).path
                File(context.getExternalFilesDir("backups"), relative + ".$BACKUP_EXTENSION")
            }
            Strategy.VISIBLE_GLOBAL -> {
                val relative = file.relativeToOrSelf(repoRoot).path
                File(File(visibleGlobalRoot(), repoRoot.name), relative + ".$BACKUP_EXTENSION")
            }
            // IN_REPO_GITIGNORE: junto al archivo, con la entrada ya
            // asegurada en el .gitignore por saveStrategyFor al elegir esta
            // estrategia. strategy == null (todavía no se preguntó/eligió)
            // ya no debería llegar hasta acá: EditorActivity.
            // backupCurrentFile corta antes de llamar a writeBackup en ese
            // caso (ver su propio comentario — BUG encontrado y corregido:
            // escribir junto al archivo sin esperar la elección ensuciaba
            // el repo con un .codeckbak no declarado en el .gitignore). Si
            // de todos modos llega null acá (otro caller directo de
            // backupFileFor/writeBackup que no haga ese chequeo), junto al
            // archivo sigue siendo el fallback menos sorprendente.
            else -> File(file.parentFile, backupName)
        }
    }

    /** Escribe [content] al .bak de [file] (resuelto vía backupFileFor),
     *  creando las carpetas intermedias si hace falta (relevante para
     *  APP_PRIVATE/VISIBLE_GLOBAL, que replican la estructura de carpetas
     *  del repo en otro lado). */
    fun writeBackup(context: Context, file: File, repoRoot: File?, strategy: Strategy?, content: String) {
        val backup = backupFileFor(context, file, repoRoot, strategy)
        backup.parentFile?.mkdirs()
        backup.writeText(content)
    }

    /** Borra el .bak de [file] si existe: al guardar con Save, el archivo
     *  real ya queda al día y el backup deja de hacer falta. No falla si
     *  no existe o no se puede borrar: un backup que
     *  sobrevive no es un bug grave (el usuario puede toparse con un
     *  .codeckbak viejo, nada más), así que no amerita propagar un error
     *  que interrumpa el guardado real. */
    fun deleteBackup(context: Context, file: File, repoRoot: File?, strategy: Strategy?) {
        backupFileFor(context, file, repoRoot, strategy).delete()
    }

    /** Busca un .bak más nuevo que [file] en cualquiera de las 3 ubicaciones
     *  posibles (no solo la de la estrategia vigente): si el usuario
     *  cambió de estrategia entre sesiones, o si [strategy] todavía es
     *  null porque es la primera vez que se ve este repo, igual hay que
     *  poder encontrar un backup viejo dejado por una sesión anterior.
     *  Devuelve el contenido del backup más reciente entre los candidatos
     *  que sean efectivamente más nuevos que [file], o null si no hay
     *  ninguno (nada que recuperar). */
    fun findRecoverableBackup(context: Context, file: File, repoRoot: File?): String? {
        val candidates = if (repoRoot == null) {
            listOf(File(file.parentFile, "${file.name}.$BACKUP_EXTENSION"))
        } else {
            Strategy.entries.map { backupFileFor(context, file, repoRoot, it) }
        }
        val newest = candidates
            .filter { it.exists() && it.lastModified() > file.lastModified() }
            .maxByOrNull { it.lastModified() }
            ?: return null
        return newest.readText()
    }
}
