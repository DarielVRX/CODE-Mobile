package com.codemobile.git

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File

/**
 * JGit lee automáticamente ~/.gitconfig y /etc/gitconfig del entorno donde
 * corre (vía SystemReader.openUserConfig/openSystemConfig). En Android no
 * debería existir ninguno de los dos, pero esta app nunca ofreció un flujo
 * de configuración global de Git (ni firma de commits, ni config de
 * usuario fuera de GitCredentialsStore), así que no debe depender en
 * absoluto de que algo externo exista o tenga cierto formato.
 *
 * Un ~/.gitconfig externo con gpg.format=ssh (de otra herramienta, nada
 * que ver con esta app) puede hacer que CommitCommand lance una excepción
 * no relacionada al commit en sí, incluso pidiendo explícitamente no firmar
 * (setSign(false) no evita que JGit lea esa config igual). La solución es
 * reemplazar el SystemReader global para que nunca toque esos archivos:
 * openUserConfig/openSystemConfig/openJGitConfig devuelven una config vacía
 * en vez de leer del disco, sin cambiar ningún otro comportamiento
 * (hostname, variables de entorno, reloj, etc. siguen delegando al reader
 * real).
 */
object GitSystemConfig {

    private var installed = false

    /** Instala el SystemReader aislado una sola vez (idempotente). Se llama
     *  antes de cualquier operación de GitRepository — ver su bloque init. */
    @Synchronized
    fun ensureInstalled() {
        if (installed) return
        SystemReader.setInstance(IsolatedSystemReader(SystemReader.getInstance()))
        installed = true
    }

    private class IsolatedSystemReader(private val delegate: SystemReader) : SystemReader() {
        override fun getHostname(): String = delegate.hostname
        override fun getenv(variable: String?): String? = delegate.getenv(variable)
        override fun getProperty(key: String?): String? = delegate.getProperty(key)
        override fun getCurrentTime(): Long = delegate.currentTime
        override fun getTimezone(whenMs: Long): Int = delegate.getTimezone(whenMs)

        // Rutas que a propósito no existen: FileBasedConfig sobre un
        // archivo inexistente se comporta como una config vacía (load() no
        // encuentra nada que leer), sin tocar disco real del dispositivo.
        override fun openUserConfig(parent: Config?, fs: FS?): FileBasedConfig =
            FileBasedConfig(parent, File("/codemobile_no_user_gitconfig"), fs)

        override fun openSystemConfig(parent: Config?, fs: FS?): FileBasedConfig =
            FileBasedConfig(parent, File("/codemobile_no_system_gitconfig"), fs)

        override fun openJGitConfig(parent: Config?, fs: FS?): FileBasedConfig =
            FileBasedConfig(parent, File("/codemobile_no_jgit_gitconfig"), fs)
    }
}
