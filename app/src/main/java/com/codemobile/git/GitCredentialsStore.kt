package com.codemobile.git

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Guarda el Personal Access Token de GitHub cifrado en disco
 * (EncryptedSharedPreferences, respaldado por el Android Keystore), para no
 * pedirlo en cada comando git. Un solo token global: la app no distingue
 * "token para repo A" de "token para repo B" — igual que el comportamiento
 * típico de un usuario que usa la misma cuenta de GitHub para todo.
 *
 * No usuario/contraseña: GitHub ya no acepta contraseña por HTTPS, solo PAT
 * (o SSH, que no cubrimos en esta primera integración).
 */
object GitCredentialsStore {

    private const val PREFS_FILE_NAME = "git_credentials"
    private const val KEY_TOKEN = "github_pat"
    private const val KEY_USERNAME = "github_username"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun hasToken(context: Context): Boolean = prefs(context).contains(KEY_TOKEN)

    fun saveToken(context: Context, username: String, token: String) {
        prefs(context).edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun clearToken(context: Context) {
        prefs(context).edit()
            .remove(KEY_USERNAME)
            .remove(KEY_TOKEN)
            .apply()
    }

    fun getUsername(context: Context): String? = prefs(context).getString(KEY_USERNAME, null)

    fun getToken(context: Context): String? = prefs(context).getString(KEY_TOKEN, null)
}
