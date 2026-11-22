package com.codemobile.git

import android.content.Context
import android.content.SharedPreferences

/**
 * Guarda el nombre y email de autoría de commits — lo que en Git de
 * escritorio sería "git config user.name/user.email" — para que
 * GitRepository.commit() los pase explícitos vía PersonIdent en vez de
 * dejar que JGit resuelva algo por su cuenta.
 *
 * Por qué hace falta: ver GitRepository.commit() y GitSystemConfig. JGit lee
 * normalmente user.name/user.email de ~/.gitconfig, pero esta app bloquea
 * a propósito cualquier config externa de ese tipo (GitSystemConfig aísla
 * el SystemReader para no depender de archivos del sistema que no
 * controlamos) — sin eso, JGit cae a un fallback basado en el usuario del
 * sistema operativo de Android, que terminaba siendo algo genérico como
 * "root" en los commits (bug real reportado por el usuario). Este store es
 * la única fuente de identidad de autoría que la app usa.
 *
 * SharedPreferences normal, no cifrado: a diferencia del PAT de
 * GitCredentialsStore, nombre y email no son secretos (son justamente lo
 * que queda visible en el historial de cualquier commit).
 */
object GitIdentityStore {

    private const val PREFS_FILE_NAME = "git_identity"
    private const val KEY_NAME = "author_name"
    private const val KEY_EMAIL = "author_email"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    fun hasIdentity(context: Context): Boolean = prefs(context).contains(KEY_NAME)

    fun saveIdentity(context: Context, name: String, email: String) {
        prefs(context).edit()
            .putString(KEY_NAME, name)
            .putString(KEY_EMAIL, email)
            .apply()
    }

    fun getName(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    fun getEmail(context: Context): String? = prefs(context).getString(KEY_EMAIL, null)
}
