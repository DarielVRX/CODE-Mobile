package com.codemobile.keyboard

import android.content.Context
import android.content.SharedPreferences

/**
 * Overrides del menú de configuración (Paso 7, §5) sobre los valores por
 * defecto de KeyboardLayouts: variantes de acento, drag actions, los 3
 * anillos editables (BRACKETS/OPERATORS/PASTE — NAV queda fuera de
 * alcance, ver SettingsActivity) y los símbolos de las pantallas
 * secundarias (SYMBOLS_MAIN/SYMBOLS_SIDE). Más la preferencia de haptic,
 * que no es un override de layout sino un on/off independiente.
 *
 * SharedPreferences normal (no cifrado, nada de esto es sensible), claves
 * planas por slot en vez de un blob serializado (JSON): cada operación de
 * edición del menú de configuración toca un único slot puntual (agregar/
 * quitar una variante, cambiar un sector de anillo), así que leer-modificar-
 * reescribir un blob completo por cada cambio sería más trabajo sin
 * ninguna ventaja aquí. "Sin override" se representa con la clave ausente
 * (getString con default null), nunca con un string vacío guardado a
 * propósito.
 *
 * Mismo patrón que GitIdentityStore: object simple, funciones que toman
 * Context, sin estado en memoria propio (siempre se lee directo de
 * SharedPreferences, que ya cachea en RAM internamente tras el primer
 * acceso).
 */
object KeyboardSettingsStore {

    private const val PREFS_FILE_NAME = "keyboard_settings"

    private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
    private const val PREFIX_DRAG_ACTION = "drag_action_"
    private const val PREFIX_ACCENT = "accent_"
    private const val PREFIX_RING = "ring_"
    private const val PREFIX_SYMBOL_MAIN = "symbol_main_"
    private const val PREFIX_SYMBOL_SIDE = "symbol_side_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    // ---------- Haptic ----------

    /** true si no se ha tocado nunca (vibración activa por defecto). */
    fun isHapticEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAPTIC_ENABLED, true)

    fun setHapticEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
    }

    // ---------- Drag actions (letra -> DragAction, Paso 7) ----------

    /** Override de qué DragAction dispara el drag-abajo de [letter]
     *  (minúscula). null = sin override, usar KeyboardLayouts.
     *  DEFAULT_DRAG_ACTIONS[letter] (que a su vez puede no tener entrada,
     *  en cuyo caso esa letra no tiene ninguna acción). Un override
     *  explícito a "ninguna" se guarda como el string vacío (distinto de
     *  ausente: ausente = "usa el default", vacío = "el usuario la quitó
     *  a propósito, no tiene ninguna aunque el default sí tuviera").
     */
    fun getDragActionOverride(context: Context, letter: Char): KeyboardLayouts.DragAction? {
        val raw = prefs(context).getString(PREFIX_DRAG_ACTION + letter, null) ?: return null
        if (raw.isEmpty()) return null
        return runCatching { KeyboardLayouts.DragAction.valueOf(raw) }.getOrNull()
    }

    /** true si [letter] tiene un override guardado (incluyendo "ninguna"),
     *  a diferencia de getDragActionOverride() que no distingue "sin
     *  override" de "override explícito a ninguna" (ambos devuelven null
     *  ahí porque a quien consume el valor para renderizar el teclado le
     *  da igual el motivo). hasDragActionOverride() SÍ necesita
     *  distinguirlo: el panel de configuración debe poder mostrar "esta
     *  letra no tiene acción, por decisión explícita" en vez de volver a
     *  ofrecer el [+] como si nunca se hubiera tocado. */
    fun hasDragActionOverride(context: Context, letter: Char): Boolean =
        prefs(context).contains(PREFIX_DRAG_ACTION + letter)

    fun setDragActionOverride(context: Context, letter: Char, action: KeyboardLayouts.DragAction?) {
        prefs(context).edit().putString(PREFIX_DRAG_ACTION + letter, action?.name ?: "").apply()
    }

    fun clearDragActionOverride(context: Context, letter: Char) {
        prefs(context).edit().remove(PREFIX_DRAG_ACTION + letter).apply()
    }

    // ---------- Variantes de acento (letra -> hasta 5 variantes) ----------

    /** Override de la lista de variantes de [letter] (hasta 5, ver
     *  MAX_ACCENT_VARIANTS). null = sin override, usar KeyboardLayouts.
     *  ACCENT_MAP[letter] (o lista vacía si esa letra no tiene entrada
     *  ahí). Cada variante vive en su propia clave indexada
     *  (accent_<letra>_0 .. _4) en vez de una sola string concatenada: así
     *  un carácter cualquiera (incluyendo los que se usarían como
     *  separador) puede ser una variante válida sin ambigüedad de parseo.
     *  Devuelve solo los índices presentes, en orden — un override puede
     *  tener huecos lógicos si se eliminó un slot intermedio, pero
     *  setAccentVariantOverride() siempre compacta (ver su doc), así que
     *  en la práctica esto nunca tiene huecos. */
    const val MAX_ACCENT_VARIANTS = 5

    fun getAccentVariantsOverride(context: Context, letter: Char): List<Char>? {
        // Centinela aparte del conteo de slots: necesario para distinguir
        // "sin override, usar el default" de "override explícito a CERO
        // variantes" (ver doc de setAccentVariantsOverride) — sin esto,
        // vaciar la última variante de una letra con override sería
        // indistinguible de nunca haber tocado esa letra.
        if (!prefs(context).contains(accentSetKey(letter))) return null
        val result = mutableListOf<Char>()
        for (i in 0 until MAX_ACCENT_VARIANTS) {
            val v = prefs(context).getString(accentIndexKey(letter, i), null) ?: continue
            if (v.isNotEmpty()) result.add(v[0])
        }
        return result
    }

    private fun accentIndexKey(letter: Char, index: Int) = "$PREFIX_ACCENT$letter$index"
    private fun accentSetKey(letter: Char) = "${PREFIX_ACCENT}${letter}_set"

    /** Reemplaza la lista completa de variantes override de [letter] por
     *  [variants] (máx 5, se truncan silenciosamente de más), compactando
     *  índices (sin huecos) y limpiando los índices sobrantes del valor
     *  anterior si la nueva lista es más corta. Pasar lista vacía es un
     *  override válido ("esta letra no tiene ninguna variante", distinto
     *  de "sin override, usar el default de 1-2 variantes de fábrica") —
     *  por eso siempre marca accentSetKey(), incluso con [variants] vacía. */
    fun setAccentVariantsOverride(context: Context, letter: Char, variants: List<Char>) {
        val editor = prefs(context).edit()
        val trimmed = variants.take(MAX_ACCENT_VARIANTS)
        editor.putBoolean(accentSetKey(letter), true)
        for (i in 0 until MAX_ACCENT_VARIANTS) {
            if (i < trimmed.size) {
                editor.putString(accentIndexKey(letter, i), trimmed[i].toString())
            } else {
                editor.remove(accentIndexKey(letter, i))
            }
        }
        editor.apply()
    }

    fun clearAccentVariantsOverride(context: Context, letter: Char) {
        val editor = prefs(context).edit()
        editor.remove(accentSetKey(letter))
        for (i in 0 until MAX_ACCENT_VARIANTS) {
            editor.remove(accentIndexKey(letter, i))
        }
        editor.apply()
    }

    // ---------- Anillos editables (BRACKETS, OPERATORS, PASTE) ----------

    /** Identifica cada anillo editable en las claves de SharedPreferences.
     *  RING_NAV no tiene entrada acá a propósito: nace de la tecla espacio,
     *  fuera de alcance del menú de configuración (ver su doc en
     *  KeyboardLayouts/SettingsActivity). */
    enum class EditableRing(val prefKey: String) {
        BRACKETS("brackets"),
        OPERATORS("operators"),
        PASTE("paste")
    }

    private fun ringSlotKey(ring: EditableRing, slot: String) = "$PREFIX_RING${ring.prefKey}_$slot"

    /** Override de un sector puntual del anillo [ring]: [slot] es uno de
     *  "center", "inner0", "inner1", "outer0", "outer1". null = sin
     *  override para ese sector puntual (se sigue usando el default de
     *  KeyboardLayouts para ese sector específico — los overrides de
     *  anillo son por sector, no por anillo completo, a diferencia de
     *  variantes de acento que sí se reemplazan como lista entera). String
     *  vacío guardado = el usuario vació ese sector a propósito (sector
     *  sin símbolo, no "usa el default"). */
    fun getRingSlotOverride(context: Context, ring: EditableRing, slot: String): String? =
        prefs(context).getString(ringSlotKey(ring, slot), null)

    fun setRingSlotOverride(context: Context, ring: EditableRing, slot: String, value: String) {
        prefs(context).edit().putString(ringSlotKey(ring, slot), value).apply()
    }

    fun clearRingSlotOverride(context: Context, ring: EditableRing, slot: String) {
        prefs(context).edit().remove(ringSlotKey(ring, slot)).apply()
    }

    /** Override del tap-base (fuera del gesto de anillo) de la tecla
     *  origen de [ring] — p.ej. "(" para BRACKETS, "=" para OPERATORS.
     *  PASTE (tecla "↵"/Enter) no tiene tap-base editable (ver doc en
     *  SettingsActivity: Enter mismo no es editable, solo su anillo), así
     *  que llamar esto con EditableRing.PASTE es responsabilidad de quien
     *  llama evitar — el store no lo impide activamente, pero el panel de
     *  configuración no debe ofrecer esta fila para PASTE. */
    fun getRingOriginTapOverride(context: Context, ring: EditableRing): String? =
        prefs(context).getString(ringSlotKey(ring, "tap"), null)

    fun setRingOriginTapOverride(context: Context, ring: EditableRing, value: String) {
        prefs(context).edit().putString(ringSlotKey(ring, "tap"), value).apply()
    }

    fun clearRingOriginTapOverride(context: Context, ring: EditableRing) {
        prefs(context).edit().remove(ringSlotKey(ring, "tap")).apply()
    }

    // ---------- Símbolos de pantallas secundarias ----------

    /** Override del símbolo en la posición [index] de SYMBOLS_MAIN o
     *  SYMBOLS_SIDE (según [main]). Edición directa de un solo carácter,
     *  sin variantes ni [+] (ver doc de SettingsActivity) — por eso es un
     *  solo valor por índice, no una lista como accent_. */
    fun getSymbolOverride(context: Context, main: Boolean, index: Int): Char? {
        val prefix = if (main) PREFIX_SYMBOL_MAIN else PREFIX_SYMBOL_SIDE
        val v = prefs(context).getString("$prefix$index", null) ?: return null
        return v.takeIf { it.isNotEmpty() }?.get(0)
    }

    fun setSymbolOverride(context: Context, main: Boolean, index: Int, value: Char) {
        val prefix = if (main) PREFIX_SYMBOL_MAIN else PREFIX_SYMBOL_SIDE
        prefs(context).edit().putString("$prefix$index", value.toString()).apply()
    }

    // ---------- Reset ----------

    /** Borra TODOS los overrides (drag actions, variantes, anillos,
     *  símbolos) pero preserva haptic_enabled (ver pedido explícito del
     *  plan: el toggle de vibración es independiente del botón de
     *  "restablecer valores por defecto"). */
    fun resetToDefaults(context: Context) {
        val p = prefs(context)
        val haptic = isHapticEnabled(context)
        p.edit().clear().putBoolean(KEY_HAPTIC_ENABLED, haptic).apply()
    }
}
