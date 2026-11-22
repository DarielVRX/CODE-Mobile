package com.codemobile.keyboard

/**
 * Paso 7 (botón "Aplicar", 3 estados: default de fábrica / actual guardado
 * / cambios en memoria sin guardar — pedido explícito): abstrae de dónde
 * se leen/escriben los overrides del menú de configuración, para que
 * KeyboardLayouts.effective*() y ConfigPanelView no dependan directo de
 * KeyboardSettingsStore (que SIEMPRE lee/escribe SharedPreferences, sin
 * ningún estado intermedio).
 *
 * Dos implementaciones:
 * - RealKeyboardSettingsSource: delega 1:1 a KeyboardSettingsStore — el
 *   teclado NORMAL (EditorActivity) sigue viendo el store real tal cual,
 *   sin ningún cambio de comportamiento.
 * - PendingKeyboardSettings: buffer en memoria — SettingsActivity lo usa
 *   mientras el usuario edita, para que nada se escriba a disco hasta que
 *   toque "Aplicar" (ver su doc de clase).
 *
 * Mismos nombres y contratos que KeyboardSettingsStore (sin [context]:
 * cada implementación ya sabe de dónde leer/escribir — Real lo recibe en
 * su propio constructor, Pending no lo necesita porque vive en memoria).
 */
interface KeyboardSettingsSource {
    fun isHapticEnabled(): Boolean
    fun setHapticEnabled(enabled: Boolean)

    fun getDragActionOverride(letter: Char): KeyboardLayouts.DragAction?
    fun hasDragActionOverride(letter: Char): Boolean
    fun setDragActionOverride(letter: Char, action: KeyboardLayouts.DragAction?)
    fun clearDragActionOverride(letter: Char)

    fun getAccentVariantsOverride(letter: Char): List<Char>?
    fun setAccentVariantsOverride(letter: Char, variants: List<Char>)
    fun clearAccentVariantsOverride(letter: Char)

    fun getRingSlotOverride(ring: KeyboardSettingsStore.EditableRing, slot: String): String?
    fun setRingSlotOverride(ring: KeyboardSettingsStore.EditableRing, slot: String, value: String)

    fun getRingOriginTapOverride(ring: KeyboardSettingsStore.EditableRing): String?
    fun setRingOriginTapOverride(ring: KeyboardSettingsStore.EditableRing, value: String)

    fun getSymbolOverride(main: Boolean, index: Int): Char?
    fun setSymbolOverride(main: Boolean, index: Int, value: Char)

    fun resetToDefaults()
}

/** Delega 1:1 a KeyboardSettingsStore (ver doc de archivo) — usada por el
 *  teclado normal de EditorActivity, nunca por SettingsActivity. */
class RealKeyboardSettingsSource(private val context: android.content.Context) : KeyboardSettingsSource {
    override fun isHapticEnabled() = KeyboardSettingsStore.isHapticEnabled(context)
    override fun setHapticEnabled(enabled: Boolean) = KeyboardSettingsStore.setHapticEnabled(context, enabled)

    override fun getDragActionOverride(letter: Char) = KeyboardSettingsStore.getDragActionOverride(context, letter)
    override fun hasDragActionOverride(letter: Char) = KeyboardSettingsStore.hasDragActionOverride(context, letter)
    override fun setDragActionOverride(letter: Char, action: KeyboardLayouts.DragAction?) =
        KeyboardSettingsStore.setDragActionOverride(context, letter, action)
    override fun clearDragActionOverride(letter: Char) = KeyboardSettingsStore.clearDragActionOverride(context, letter)

    override fun getAccentVariantsOverride(letter: Char) = KeyboardSettingsStore.getAccentVariantsOverride(context, letter)
    override fun setAccentVariantsOverride(letter: Char, variants: List<Char>) =
        KeyboardSettingsStore.setAccentVariantsOverride(context, letter, variants)
    override fun clearAccentVariantsOverride(letter: Char) = KeyboardSettingsStore.clearAccentVariantsOverride(context, letter)

    override fun getRingSlotOverride(ring: KeyboardSettingsStore.EditableRing, slot: String) =
        KeyboardSettingsStore.getRingSlotOverride(context, ring, slot)
    override fun setRingSlotOverride(ring: KeyboardSettingsStore.EditableRing, slot: String, value: String) =
        KeyboardSettingsStore.setRingSlotOverride(context, ring, slot, value)

    override fun getRingOriginTapOverride(ring: KeyboardSettingsStore.EditableRing) =
        KeyboardSettingsStore.getRingOriginTapOverride(context, ring)
    override fun setRingOriginTapOverride(ring: KeyboardSettingsStore.EditableRing, value: String) =
        KeyboardSettingsStore.setRingOriginTapOverride(context, ring, value)

    override fun getSymbolOverride(main: Boolean, index: Int) = KeyboardSettingsStore.getSymbolOverride(context, main, index)
    override fun setSymbolOverride(main: Boolean, index: Int, value: Char) =
        KeyboardSettingsStore.setSymbolOverride(context, main, index, value)

    override fun resetToDefaults() = KeyboardSettingsStore.resetToDefaults(context)
}
