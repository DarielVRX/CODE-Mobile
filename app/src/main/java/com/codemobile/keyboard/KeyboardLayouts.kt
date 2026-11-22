package com.codemobile.keyboard

/**
 * Datos estáticos del teclado propio: filas QWERTY, variantes por hold
 * (acentos), y contenido de las pantallas de símbolos/numpad/anillos.
 *
 * Sin lógica de UI ni de gestos aquí — solo listas/constantes que reflejan
 * exactamente el mockup aprobado (ver plan_teclado_custom.md). CustomKeyboardView
 * y RingGestureView leen de aquí, no duplican estas listas.
 */
object KeyboardLayouts {

    /** Filas de letras en minúscula. La fila inferior especial es constante
     *  y vive aparte (ver BOTTOM_ROW), no se repite por pantalla. */
    val ROWS_LOWER: List<List<Char>> = listOf(
        "qwertyuiop".toList(),
        "asdfghjkl".toList(),
        "zxcvbnm".toList()
    )

    val ROWS_UPPER: List<List<Char>> = ROWS_LOWER.map { row -> row.map { it.uppercaseChar() } }

    /** letra base (minúscula) -> lista de variantes mostradas en el popup de
     *  hold, en el orden en que aparecen ahí (izquierda a derecha). La
     *  mayoría de las letras tiene una sola variante (acentos españoles);
     *  'a' y 'e' tienen dos porque además sirven de base para una letra
     *  griega de uso común en código científico. Letras griegas pensadas
     *  para matemática/código, no para idioma: λ, σ/Σ, δ/Δ, γ, ω/Ω, μ, π,
     *  θ, β, φ, α, ε. */
    val ACCENT_MAP: Map<Char, List<Char>> = mapOf(
        'n' to listOf('ñ'),
        'a' to listOf('á', 'α'),
        'e' to listOf('é', 'ε'),
        'i' to listOf('í'),
        'o' to listOf('ó'),
        'u' to listOf('ú'),
        'l' to listOf('λ'),
        's' to listOf('σ', 'Σ'),
        'd' to listOf('δ', 'Δ'),
        'g' to listOf('γ'),
        'w' to listOf('ω', 'Ω'),
        'm' to listOf('μ'),
        'p' to listOf('π'),
        't' to listOf('θ'),
        'b' to listOf('β'),
        'f' to listOf('φ')
    )

    /** Paso 7: acciones disponibles para el gesto de drag hacia abajo de una
     *  tecla del qwerty (mismo mecanismo que ACCENT_MAP pero vertical, y en
     *  vez de insertar un carácter despacha una acción con nombre — ver
     *  CustomKeyboardView.gestureKey()). [hint] es el texto corto que se
     *  muestra en la barra de sugerencias mientras se sostiene el gesto
     *  (NUNCA la combinación de escritorio tipo "Ctrl+S" — ver plan del
     *  Paso 7, §5.3). [repeatsOnHold]: si se mantiene el dedo sostenido más
     *  allá del umbral de "drag continuo" (mismo rol que NavMode.DRAG en
     *  RingGestureView) en vez de soltar, la acción se repite sola a
     *  intervalo fijo hasta el release — pensado para UNDO/REDO (tedioso de
     *  repetir gesto por gesto); false para cualquier otra acción (un solo
     *  disparo por gesto, como siempre). Vive en la ACCIÓN, no en la tecla:
     *  si el usuario reasigna qué letra dispara UNDO desde el menú de
     *  configuración, el repetido debe seguir a la acción, no quedarse
     *  pegado a Z — por eso gestureKey() SIEMPRE consulta esta propiedad
     *  contra la DragAction resuelta en runtime, nunca hardcodeado a una
     *  letra. ANCHOR_SELECTION (tecla E) es un caso especial aparte: no
     *  ejecuta nada de inmediato, arma el ancla de selección (ver doc en
     *  CodeEditText/ControlBarView). */
    enum class DragAction(val hint: String, val repeatsOnHold: Boolean = false) {
        SAVE("Save"),
        UNDO("Undo", repeatsOnHold = true),
        REDO("Redo", repeatsOnHold = true),
        SELECT_ALL("Select all"),
        FIND("Find"),
        CUT("Cut"),
        COPY("Copy"),
        PASTE("Paste"),
        DUPLICATE_LINE("Duplicate line"),
        DELETE_LINE("Delete line"),
        // Reemplaza al viejo INSERT_SLASH (insertaba "/" literal sin
        // criterio de lenguaje, bug reportado): ahora comenta/descomenta
        // línea de verdad, vía KeyboardTarget.toggleLineComment — ver su
        // doc y AutoIndent.lineCommentPrefixFor.
        TOGGLE_COMMENT("Comment/uncomment line"),
        ANCHOR_SELECTION("Selection anchor"),
        // Letra G (Paso 7, extensión): sin cuadro de búsqueda ni lista —
        // siempre opera sobre el identificador bajo el cursor o la
        // selección activa (ver GotoResolver.resolve). "Ir a definición"
        // vive en esta MISMA acción, no en un gesto separado: la decisión
        // de abrir un archivo importado vs. saltar a una función es
        // interna al resolver, no al teclado (pedido explícito: "no hace
        // falta ninguna tercera cosa").
        GOTO("Go to / Open"),
        // Sin entrada propia en DEFAULT_DRAG_ACTIONS a propósito (ver
        // HOLD_SWITCHES_TO más abajo): solo se alcanza sosteniendo el
        // drag-abajo de la letra que tenga asignado SAVE — no es una
        // acción que una letra pueda tener como su propio default ni
        // reasignación directa desde el menú de configuración.
        SAVE_AS("Save as")
    }

    /** A diferencia de repeatsOnHold (repite la MISMA acción en ticks),
     *  esto cambia QUÉ acción dispara el release tras sostener
     *  DRAG_ACTION_REPEAT_THRESHOLD_MS en la zona sin soltar — mismo
     *  umbral de 1s que usa el timer de repetición, pero sin repetición ni
     *  ticks: pasado el umbral, el hint de suggestionsBar cambia al de la
     *  acción de reemplazo (ver gestureKey()) y, si el release ocurre
     *  DESPUÉS de eso, se dispara esa acción en vez de la original; un
     *  release ANTES del umbral dispara la original sin cambios, como
     *  siempre.
     *
     *  Vive como Map aparte (no como campo del constructor del enum,
     *  ver intento descartado) porque Kotlin no permite que una entrada
     *  del enum referencie a OTRA entrada declarada más abajo en el mismo
     *  cuerpo — SAVE necesitaría referenciar SAVE_AS, que viene después.
     *  Pedido explícito: solo SAVE -> SAVE_AS por ahora; cualquier acción
     *  sin entrada acá simplemente no tiene este comportamiento. */
    val HOLD_SWITCHES_TO: Map<DragAction, DragAction> = mapOf(
        DragAction.SAVE to DragAction.SAVE_AS
    )

    /** Mapa POR DEFECTO letra (minúscula) -> acción del drag-abajo (ver
     *  DragAction). "Por defecto": el menú de configuración (§5 del plan)
     *  puede reasignar esto en runtime vía overrides guardados en
     *  SharedPreferences; esta constante es el valor de fábrica al que
     *  vuelve el botón de "restablecer" (§5.6). */
    val DEFAULT_DRAG_ACTIONS: Map<Char, DragAction> = mapOf(
        // Sosteniendo este mismo gesto más allá del umbral, S también
        // alcanza SAVE_AS ("Guardar como") — ver HOLD_SWITCHES_TO arriba.
        's' to DragAction.SAVE,
        'z' to DragAction.UNDO,
        'y' to DragAction.REDO,
        'a' to DragAction.SELECT_ALL,
        'f' to DragAction.FIND,
        'x' to DragAction.CUT,
        'c' to DragAction.COPY,
        'v' to DragAction.PASTE,
        'd' to DragAction.DUPLICATE_LINE,
        'k' to DragAction.DELETE_LINE,
        'q' to DragAction.TOGGLE_COMMENT,
        'e' to DragAction.ANCHOR_SELECTION,
        'g' to DragAction.GOTO
    )

    // ---- Pantalla de símbolos (tap en ?123): pantalla única ----
    // Originalmente 3 principales (centro, grandes) + 12 secundarios
    // (bordes, chicos). No duplican lo que ya vive en los anillos.
    val SYMBOLS_ROW_DIGITS: List<Char> = "1234567890".toList()
    // '/', '.' y '-' viven acá con un tap directo porque son demasiado
    // frecuentes en código/rutas/regex/nombres de archivo para quedar
    // escondidos en el popup numérico (hold sobre "?123"). El weight de
    // estas celdas en CustomKeyboardView.renderSymbolsContent() se calcula
    // a partir de SYMBOLS_MAIN.size (no es un valor fijo), así que agregar
    // o quitar elementos de esta lista no requiere tocar nada más ahí.
    val SYMBOLS_MAIN: List<Char> = listOf('\\', '#', '$', '/', '.', '-')
    val SYMBOLS_SIDE: List<Char> = listOf('`', '~', '¿', '¡', '€', '£', '¥', '—', '°', '±', '^', '@')

    // ---- Popup numérico / calculadora (hold en ?123): pantalla completa ----
    // Grilla 4 filas x 5 columnas. "ABC" vuelve a letras; a diferencia de los
    // anillos, esta pantalla queda abierta hasta que se toca "ABC".
    enum class NumpadKeyKind { DIGIT, OP, ABC, EQUALS, ERASE, SPACE, ENTER }
    data class NumpadKey(val label: String, val kind: NumpadKeyKind)

    val NUMPAD_GRID: List<List<NumpadKey>> = listOf(
        listOf(NumpadKey("1", NumpadKeyKind.DIGIT), NumpadKey("2", NumpadKeyKind.DIGIT), NumpadKey("3", NumpadKeyKind.DIGIT), NumpadKey("+", NumpadKeyKind.OP), NumpadKey("=", NumpadKeyKind.EQUALS)),
        listOf(NumpadKey("4", NumpadKeyKind.DIGIT), NumpadKey("5", NumpadKeyKind.DIGIT), NumpadKey("6", NumpadKeyKind.DIGIT), NumpadKey("-", NumpadKeyKind.OP), NumpadKey("⌫", NumpadKeyKind.ERASE)),
        // "␣" (U+2423, "OPEN BOX"): mismo glifo de espacio visible usado en
        // CustomKeyboardView.bottomRow(), en vez del texto "espacio".
        listOf(NumpadKey("7", NumpadKeyKind.DIGIT), NumpadKey("8", NumpadKeyKind.DIGIT), NumpadKey("9", NumpadKeyKind.DIGIT), NumpadKey("*", NumpadKeyKind.OP), NumpadKey("␣", NumpadKeyKind.SPACE)),
        listOf(NumpadKey("ABC", NumpadKeyKind.ABC), NumpadKey("0", NumpadKeyKind.DIGIT), NumpadKey(".", NumpadKeyKind.OP), NumpadKey("/", NumpadKeyKind.OP), NumpadKey("↵", NumpadKeyKind.ENTER))
    )

    // ---- Anillos (hold+drag+release) ----
    // Centro + corona interna (4 sectores) + corona externa (4 sectores),
    // salvo el anillo de navegación que usa 3+3 (←↑→).
    // startAngle/endAngle en convención matemática (0°=derecha, 90°=arriba,
    // 180°=izquierda): por defecto el semicírculo completo (180→0), como
    // hasta ahora. RING_BRACKETS y RING_PASTE usan un cuarto de círculo cada
    // uno (90→0 y 180→90 respectivamente) desde que el anillo de "(" se
    // dividió en dos mitades independientes (ver bottomRow()).
    data class RingSpec(
        val center: String,
        val inner: List<String>,
        val outer: List<String>,
        val startAngle: Double = 180.0,
        val endAngle: Double = 0.0,
        // Solo RING_NAV: labels alternativos de los mismos sectores de
        // [inner] para el modo "drag tras 1s" (continuo), que reemplazó al
        // viejo anillo externo — mismo índice que su contraparte de [inner].
        // null en los demás specs (sin matiz de modo, su sector no cambia de
        // label). Ver RingGestureView para el porqué de este campo.
        val innerContinuousLabels: List<String>? = null
    )

    // Mitad derecha del anterior semicírculo de brackets: cuarto de círculo
    // que nace en "(" y se abre hacia la derecha (90°→0°, es decir desde
    // arriba del botón hacia la derecha), sin invadir el espacio de "enter".
    val RING_BRACKETS = RingSpec(
        center = ")",
        inner = listOf("[", "]"),
        outer = listOf("{", "}"),
        startAngle = 90.0,
        endAngle = 0.0
    )

    // Mitad izquierda, originada en "enter": cuarto de círculo que se abre
    // hacia la izquierda (180°→90°). Copiar/Cortar/Pegar se movieron al
    // gesto de drag hacia abajo del qwerty (teclas C/X/V, ver Paso 7), así
    // que el sub-menú que vivía en este centro (copiar/pegar) ya no existe
    // — center vuelve a ser un carácter normal como cualquier otro sector.
    val RING_PASTE = RingSpec(
        center = ".",
        inner = listOf("!", "_"),
        outer = listOf("<", ">"),
        startAngle = 180.0,
        endAngle = 90.0
    )

    val RING_OPERATORS = RingSpec(
        center = ":",
        inner = listOf("'", ";", ",", "\""),
        outer = listOf("?", "&", "|", "%")
    )

    // Centro = "↓": el origen de este anillo es "␣" (que ya tiene su propio
    // tap normal = espacio, resuelto en ringOriginKey() antes siquiera de
    // que este anillo se abra), así que soltar en el centro no tiene
    // ninguna razón para insertar tab — es siempre un solo step down, sin
    // importar cuánto se haya sostenido (ver
    // CustomKeyboardView.applyRingResult()).
    // Sin corona externa: los mismos 3 sectores cumplen DOS roles según
    // cómo se llegó a ellos (ver RingGestureView.NavMode):
    //  - Modo FIJO (soltar antes de 1s de hold): el anillo queda dibujado en
    //    pantalla; cada tap en un sector hace un solo paso (label normal,
    //    [inner]) y el anillo permanece abierto para más taps.
    //  - Modo DRAG (sostener 1s sin soltar, luego arrastrar): el sector
    //    activo se comporta como el viejo anillo externo — paso continuo
    //    mientras el dedo se mantenga ahí (label con flecha doble,
    //    [innerContinuousLabels]).
    // outer queda vacío: ya no hay corona externa que dibujar.
    val RING_NAV = RingSpec(
        center = "↓",
        inner = listOf("←", "↑", "→"),
        outer = emptyList(),
        innerContinuousLabels = listOf("←←", "↑↑", "→→")
    )

    // ---- Resolución de overrides (menú de configuración, Paso 7 §5) ----
    // Las constantes de arriba (ACCENT_MAP, DEFAULT_DRAG_ACTIONS, RING_*,
    // SYMBOLS_*) quedan intactas como "valores de fábrica" — nunca se
    // mutan, son el objetivo de "restablecer valores por defecto". Todo el
    // teclado (CustomKeyboardView) debe leer SIEMPRE a través de las
    // funciones effective* de aquí abajo, nunca directo de las constantes,
    // para que un override se refleje sin tener que duplicar esa lectura
    // en cada punto de uso.
    //
    // Paso 7 (botón "Aplicar", 3 estados): estas funciones ya NO toman
    // Context directo — toman un KeyboardSettingsSource (ver ese archivo),
    // que puede ser el store real (RealKeyboardSettingsSource, el teclado
    // normal de EditorActivity) o un buffer en memoria
    // (PendingKeyboardSettings, SettingsActivity mientras el usuario edita
    // sin haber tocado "Aplicar" todavía). CustomKeyboardView decide cuál
    // inyectar (ver su campo settingsSource).

    /** Variantes de acento reales para [letter] (minúscula): el override
     *  guardado si existe (incluyendo lista vacía, "sin variantes a
     *  propósito"), o ACCENT_MAP[letter] (o lista vacía si tampoco hay
     *  entrada de fábrica) si no hay override. */
    fun effectiveAccentVariants(source: KeyboardSettingsSource, letter: Char): List<Char> =
        source.getAccentVariantsOverride(letter)
            ?: ACCENT_MAP[letter]
            ?: emptyList()

    /** DragAction real para [letter] (minúscula), o null si esa letra no
     *  tiene ninguna (de fábrica, o porque el override la quitó a
     *  propósito — hasDragActionOverride() distingue ambos casos, acá da
     *  igual el motivo). */
    fun effectiveDragAction(source: KeyboardSettingsSource, letter: Char): DragAction? {
        if (source.hasDragActionOverride(letter)) {
            return source.getDragActionOverride(letter)
        }
        return DEFAULT_DRAG_ACTIONS[letter]
    }

    /** RingSpec real de uno de los 3 anillos editables (BRACKETS/
     *  OPERATORS/PASTE — RING_NAV no pasa por acá, no es editable, se usa
     *  directo). Reconstruye el spec de fábrica reemplazando sector por
     *  sector solo los que tengan override — a diferencia de las
     *  variantes de acento (que se reemplazan como lista completa), un
     *  anillo mantiene su forma fija (siempre center + 2 inner + 2 outer,
     *  o ninguno para outer en RING_NAV, que ni pasa por acá), así que
     *  tiene sentido mezclar sector por sector en vez de todo-o-nada. */
    fun effectiveRingSpec(source: KeyboardSettingsSource, ring: KeyboardSettingsStore.EditableRing): RingSpec {
        val default = when (ring) {
            KeyboardSettingsStore.EditableRing.BRACKETS -> RING_BRACKETS
            KeyboardSettingsStore.EditableRing.OPERATORS -> RING_OPERATORS
            KeyboardSettingsStore.EditableRing.PASTE -> RING_PASTE
        }
        fun slot(name: String, fallback: String) =
            source.getRingSlotOverride(ring, name) ?: fallback
        return default.copy(
            center = slot("center", default.center),
            inner = default.inner.mapIndexed { i, v -> slot("inner$i", v) },
            outer = default.outer.mapIndexed { i, v -> slot("outer$i", v) }
        )
    }

    /** Carácter real de tap-base (fuera del gesto de anillo) de la tecla
     *  origen de [ring] — p.ej. "(" o "=" de fábrica. Solo tiene sentido
     *  para BRACKETS/OPERATORS: PASTE (tecla Enter) no tiene tap-base
     *  editable (ver doc en KeyboardSettingsStore/SettingsActivity), así
     *  que [fallback] siempre se devuelve tal cual para ese caso — quien
     *  llama ya sabe esto y no debería invocar esta función para PASTE. */
    fun effectiveRingOriginTap(source: KeyboardSettingsSource, ring: KeyboardSettingsStore.EditableRing, fallback: String): String =
        source.getRingOriginTapOverride(ring) ?: fallback

    /** Carácter real en la posición [index] de SYMBOLS_MAIN ([main]=true) o
     *  SYMBOLS_SIDE ([main]=false): el override si existe, o el de
     *  fábrica. */
    fun effectiveSymbol(source: KeyboardSettingsSource, main: Boolean, index: Int): Char {
        val defaults = if (main) SYMBOLS_MAIN else SYMBOLS_SIDE
        return source.getSymbolOverride(main, index) ?: defaults[index]
    }
}
