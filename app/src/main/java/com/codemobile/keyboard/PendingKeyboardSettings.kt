package com.codemobile.keyboard

/**
 * Paso 7 (botón "Aplicar", 3 estados: default de fábrica / actual guardado
 * en KeyboardSettingsStore / cambios en memoria sin guardar — pedido
 * explícito del plan): buffer en memoria que SettingsActivity usa en vez
 * del store real mientras el usuario edita.
 *
 * Arranca copiando el estado REAL actual (letra por letra, anillo por
 * anillo, símbolo por símbolo) desde [source] en el constructor — así
 * "sin tocar nada todavía" se ve exactamente igual a lo que ya está
 * guardado, no al default de fábrica. Cada set* de ahí en más solo muta
 * este buffer; nada llega a SharedPreferences hasta [applyToStore].
 *
 * Pedido explícito: "se pierden al salir sin aplicar" — por eso este
 * buffer vive en memoria pura (un Map normal), sin ningún mecanismo de
 * persistencia propio. Si el usuario sale de SettingsActivity sin tocar
 * "Aplicar", esta instancia simplemente se descarta con la Activity.
 *
 * [hasPendingChanges] compara el buffer contra el estado real ACTUAL del
 * store (no contra la foto tomada al construirse): si otra fuente cambiara
 * el store mientras este buffer vive (no debería pasar en la práctica,
 * SettingsActivity es la única pantalla que escribe overrides), la
 * comparación sigue siendo call a KeyboardSettingsStore, nunca compara
 * contra la copia inicial cacheada aparte.
 */
class PendingKeyboardSettings(private val realSource: RealKeyboardSettingsSource) : KeyboardSettingsSource {

    // true desde que resetToDefaults() se llama en ESTE buffer hasta el
    // próximo applyToStore() (que lo vuelve a false después de limpiar el
    // store real) — ver doc de resetToDefaults()/applyToStore() más abajo
    // sobre por qué un buffer vacío por sí solo no basta para distinguir
    // "nunca se tocó nada" de "se pidió explícitamente borrar todo".
    private var resetPending = false

    // ---------- Haptic ----------
    // NO es un override de layout (no entra en "Aplicar"/"restablecer" del
    // menú): el toggle de haptic ya escribe directo al store real en
    // SettingsActivity (ver su setupGeneralSettings), nunca pasa por este
    // buffer. isHapticEnabled/setHapticEnabled delegan directo al store
    // real solo para que esta clase cumpla la interfaz completa — nunca se
    // llaman en la práctica desde SettingsActivity (que usa
    // KeyboardSettingsStore directo para el toggle).
    override fun isHapticEnabled(): Boolean = realSource.isHapticEnabled()
    override fun setHapticEnabled(enabled: Boolean) = realSource.setHapticEnabled(enabled)

    // ---------- Drag actions ----------
    // null explícito en el mapa = override a "ninguna acción" (equivalente
    // a KeyboardSettingsStore guardando string vacío); AUSENCIA de la
    // clave = "sin tocar en esta sesión, sigue copiado de source".
    private val dragActionOverrides = mutableMapOf<Char, KeyboardLayouts.DragAction?>()
    private val dragActionTouched = mutableSetOf<Char>()

    override fun getDragActionOverride(letter: Char): KeyboardLayouts.DragAction? = dragActionOverrides[letter]

    override fun hasDragActionOverride(letter: Char): Boolean = dragActionTouched.contains(letter)

    override fun setDragActionOverride(letter: Char, action: KeyboardLayouts.DragAction?) {
        dragActionOverrides[letter] = action
        dragActionTouched.add(letter)
    }

    override fun clearDragActionOverride(letter: Char) {
        dragActionOverrides.remove(letter)
        dragActionTouched.remove(letter)
    }

    // ---------- Variantes de acento ----------
    // Mismo criterio de "ausencia de la clave = sin tocar" — a diferencia
    // del store real, no necesita un centinela aparte (accentSetKey): un
    // Map normal ya distingue de forma nativa "clave ausente" de "clave
    // presente con lista vacía".
    private val accentVariantOverrides = mutableMapOf<Char, List<Char>>()

    override fun getAccentVariantsOverride(letter: Char): List<Char>? = accentVariantOverrides[letter]

    override fun setAccentVariantsOverride(letter: Char, variants: List<Char>) {
        accentVariantOverrides[letter] = variants.take(KeyboardSettingsStore.MAX_ACCENT_VARIANTS)
    }

    override fun clearAccentVariantsOverride(letter: Char) {
        accentVariantOverrides.remove(letter)
    }

    // ---------- Anillos editables ----------
    private val ringSlotOverrides = mutableMapOf<Pair<KeyboardSettingsStore.EditableRing, String>, String>()
    private val ringTapOverrides = mutableMapOf<KeyboardSettingsStore.EditableRing, String>()

    override fun getRingSlotOverride(ring: KeyboardSettingsStore.EditableRing, slot: String): String? =
        ringSlotOverrides[ring to slot]

    override fun setRingSlotOverride(ring: KeyboardSettingsStore.EditableRing, slot: String, value: String) {
        ringSlotOverrides[ring to slot] = value
    }

    override fun getRingOriginTapOverride(ring: KeyboardSettingsStore.EditableRing): String? = ringTapOverrides[ring]

    override fun setRingOriginTapOverride(ring: KeyboardSettingsStore.EditableRing, value: String) {
        ringTapOverrides[ring] = value
    }

    // ---------- Símbolos de pantallas secundarias ----------
    private val symbolMainOverrides = mutableMapOf<Int, Char>()
    private val symbolSideOverrides = mutableMapOf<Int, Char>()

    override fun getSymbolOverride(main: Boolean, index: Int): Char? =
        if (main) symbolMainOverrides[index] else symbolSideOverrides[index]

    override fun setSymbolOverride(main: Boolean, index: Int, value: Char) {
        if (main) symbolMainOverrides[index] = value else symbolSideOverrides[index] = value
    }

    // Copia el estado REAL actual de [realSource] a este buffer (ver doc
    // de clase). DEBE ir después de la declaración de todos los mapas de
    // arriba (dragActionOverrides, accentVariantOverrides,
    // ringSlotOverrides, ringTapOverrides, symbolMainOverrides,
    // symbolSideOverrides): Kotlin ejecuta los inicializadores de
    // propiedades y los bloques init en el orden TEXTUAL en que aparecen
    // en el cuerpo de la clase — un init{} más arriba que use una
    // propiedad declarada más abajo fallaría (la propiedad todavía no
    // existiría en ese punto de la construcción).
    init {
        for (letter in 'a'..'z') {
            if (realSource.hasDragActionOverride(letter)) {
                dragActionOverrides[letter] = realSource.getDragActionOverride(letter)
                dragActionTouched.add(letter)
            }
            realSource.getAccentVariantsOverride(letter)?.let { accentVariantOverrides[letter] = it }
        }
        for (ring in KeyboardSettingsStore.EditableRing.values()) {
            for (slot in listOf("center", "inner0", "inner1", "outer0", "outer1")) {
                realSource.getRingSlotOverride(ring, slot)?.let { ringSlotOverrides[ring to slot] = it }
            }
            realSource.getRingOriginTapOverride(ring)?.let { ringTapOverrides[ring] = it }
        }
        for (i in 0 until KeyboardLayouts.SYMBOLS_MAIN.size) {
            realSource.getSymbolOverride(main = true, index = i)?.let { symbolMainOverrides[i] = it }
        }
        for (i in 0 until KeyboardLayouts.SYMBOLS_SIDE.size) {
            realSource.getSymbolOverride(main = false, index = i)?.let { symbolSideOverrides[i] = it }
        }
    }

    // ---------- Reset ----------

    /** Botón "Restablecer valores por defecto" (ver SettingsActivity):
     *  vacía TODO el buffer (vuelve a mostrar los valores de fábrica, sin
     *  ningún override) — pero NO toca SharedPreferences todavía: sigue
     *  siendo un cambio pendiente, el usuario debe tocar "Aplicar" después
     *  para que el reset real se escriba a disco. Mismo criterio que el
     *  resto del buffer: haptic_enabled queda totalmente afuera (ver
     *  isHapticEnabled/setHapticEnabled arriba), este reset no lo toca. */
    override fun resetToDefaults() {
        dragActionOverrides.clear()
        dragActionTouched.clear()
        accentVariantOverrides.clear()
        ringSlotOverrides.clear()
        ringTapOverrides.clear()
        symbolMainOverrides.clear()
        symbolSideOverrides.clear()
        resetPending = true
    }

    /** true si el buffer difiere del estado REAL actual del store (ver doc
     *  de clase) — el botón "Aplicar" de SettingsActivity solo se muestra
     *  mientras esto da true. Compara cada slot tocado contra lo que el
     *  store real tiene ahora mismo, slot por slot — no hay un flag
     *  "dirty" cacheado aparte: cualquier set* que termine escribiendo
     *  exactamente el mismo valor que ya había (p.ej. abrir un panel y
     *  cerrarlo sin cambiar nada) no cuenta como pendiente. */
    fun hasPendingChanges(): Boolean {
        if (resetPending) return true
        for (letter in 'a'..'z') {
            if (realSource.hasDragActionOverride(letter) != hasDragActionOverride(letter)) return true
            if (hasDragActionOverride(letter) && realSource.getDragActionOverride(letter) != getDragActionOverride(letter)) return true
            if (realSource.getAccentVariantsOverride(letter) != getAccentVariantsOverride(letter)) return true
        }
        for (ring in KeyboardSettingsStore.EditableRing.values()) {
            for (slot in listOf("center", "inner0", "inner1", "outer0", "outer1")) {
                if (realSource.getRingSlotOverride(ring, slot) != getRingSlotOverride(ring, slot)) return true
            }
            if (realSource.getRingOriginTapOverride(ring) != getRingOriginTapOverride(ring)) return true
        }
        for (i in 0 until KeyboardLayouts.SYMBOLS_MAIN.size) {
            if (realSource.getSymbolOverride(main = true, index = i) != getSymbolOverride(main = true, index = i)) return true
        }
        for (i in 0 until KeyboardLayouts.SYMBOLS_SIDE.size) {
            if (realSource.getSymbolOverride(main = false, index = i) != getSymbolOverride(main = false, index = i)) return true
        }
        return false
    }

    /** Botón "Aplicar": vuelca TODO el buffer al store real de una sola
     *  vez — ver pedido explícito ("un solo botón global"). No hace un
     *  resetToDefaults() del store antes de volcar: en vez de eso,
     *  resetToDefaults() de ESTA clase ya dejó el buffer vacío si el
     *  usuario restableció, y acá simplemente se refleja ese buffer
     *  (vacío o no) 1:1 contra el store — un slot que el buffer no tiene
     *  (porque nunca se tocó NI se restableció) se deja tal cual está en
     *  el store real, sin tocarlo, para no pisar un override de una letra
     *  que el usuario nunca llegó a abrir en esta sesión — SALVO que haya
     *  un reset pendiente (ver resetToDefaults()): ahí sí se limpia TODO
     *  el store real primero (igual que KeyboardSettingsStore.
     *  resetToDefaults de verdad), porque un buffer vacío después de un
     *  reset explícito significa "todos los slots vuelven a fábrica", no
     *  "nada que aplicar". */
    fun applyToStore() {
        if (resetPending) {
            realSource.resetToDefaults()
            resetPending = false
        }
        for (letter in 'a'..'z') {
            if (dragActionTouched.contains(letter)) {
                realSource.setDragActionOverride(letter, dragActionOverrides[letter])
            }
            accentVariantOverrides[letter]?.let { realSource.setAccentVariantsOverride(letter, it) }
        }
        for (ring in KeyboardSettingsStore.EditableRing.values()) {
            for (slot in listOf("center", "inner0", "inner1", "outer0", "outer1")) {
                ringSlotOverrides[ring to slot]?.let { realSource.setRingSlotOverride(ring, slot, it) }
            }
            ringTapOverrides[ring]?.let { realSource.setRingOriginTapOverride(ring, it) }
        }
        symbolMainOverrides.forEach { (index, value) -> realSource.setSymbolOverride(main = true, index = index, value = value) }
        symbolSideOverrides.forEach { (index, value) -> realSource.setSymbolOverride(main = false, index = index, value = value) }
    }
}
