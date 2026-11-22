package com.codemobile.editor

/**
 * Acumulador de cierres pendientes para brackets ((, [, {) y comillas ("  '),
 * con dos capas (LEFT y RIGHT-heredado), reemplazando el viejo esquema de
 * conteo numérico puro (globalPending) que no reconocía cierres "a la
 * derecha" del cursor:
 *
 * 1) LEFT: la pila real evaluada SOLO sobre text.substring(0, cursor). Cada
 *    nivel de la pila guarda el CIERRE que espera (no el abridor que lo
 *    creó), para que una apertura nueva siempre sea un nivel fresco e
 *    independiente, sin heredar la ruptura de un nivel viejo del mismo tipo
 *    (bug corregido: antes la pila guardaba el abridor y, aunque cada nivel
 *    ya era un objeto propio, el filtrado final colapsaba por tipo de
 *    cierre sin distinguir instancias — ver historial). De esa pila salen
 *    dos cosas: los tipos PENDIENTES (niveles abiertos, no rotos) y los
 *    tipos ROTOS (niveles que ya quedaron resueltos fuera de orden por un
 *    cierre anterior al cursor). Un tipo roto por LEFT nunca se sugiere.
 *
 * 2) RIGHT-heredado: para cada NIVEL (no tipo) que LEFT marca pendiente (no
 *    roto), se continúa la MISMA pila — exactamente donde LEFT la dejó, no
 *    desde cero, y con las MISMAS instancias de nivel (sin clonar) — sobre
 *    el resto del documento. Al final, se mira por IDENTIDAD si cada nivel
 *    heredado en particular sigue presente en la pila resultante: si ya no
 *    está (se resolvió, limpio o rompiéndolo), ese nivel no se sugiere; si
 *    sigue ahí, sí.
 *
 *    Por qué por NIVEL y no por TIPO (bug corregido): con "(|()" (cursor
 *    tras el primer '('), a la derecha el '(' nuevo abre un nivel propio y
 *    el ')' que sigue cierra ESE nivel nuevo (match directo con el tope),
 *    no el heredado — el heredado sigue pendiente, debería sugerirse. Un
 *    registro "se resolvió un ')' en algún punto" (por tipo de carácter,
 *    sin distinguir qué nivel) marcaría el tipo ')' como resuelto y
 *    escondería la sugerencia aunque el nivel heredado específico siga
 *    abierto. Comparando por identidad de objeto se evita esa confusión:
 *    el orden importa (ver "(|)(" vs "(|()" — en el primero el ')' sí
 *    resuelve directo al heredado, en el segundo no).
 *
 * La celda de la barra de controles usa [visiblePending], que combina
 * ambas capas.
 */
object BracketAccumulator {

    private val OPEN_TO_CLOSE = mapOf('(' to ')', '[' to ']', '{' to '}')
    private val CLOSE_TO_OPEN = OPEN_TO_CLOSE.entries.associate { (k, v) -> v to k }
    private val OPENERS = OPEN_TO_CLOSE.keys
    private val CLOSERS = CLOSE_TO_OPEN.keys
    private val QUOTES = setOf('"', '\'')

    /** Un nivel de la pila combinada: el CIERRE que este nivel espera (no el
     *  abridor — ver nota de clase) y si ya quedó roto (fuera de orden)
     *  dentro del tramo analizado. Es una instancia mutable concreta (no un
     *  value/data class): su IDENTIDAD importa para RIGHT-heredado (ver
     *  nota de clase) — dos niveles con el mismo [closer] son
     *  intencionalmente objetos distintos y nunca se confunden entre sí. */
    private class Level(val closer: Char, var broken: Boolean)

    /** Avanza la pila combinada (brackets + comillas, un cierre fuera de
     *  orden rompe todo lo que estaba encima) sobre [text], PARTIENDO de
     *  [startStack] en vez de una pila vacía — así se puede heredar el
     *  estado de LEFT y continuar hacia la derecha sin recalcular desde
     *  cero. [startStack] se usa TAL CUAL (mismas instancias, sin clonar):
     *  RIGHT-heredado necesita poder preguntar, al final, "¿este objeto
     *  Level específico sigue en la pila resultante?" (ver nota de clase) —
     *  clonar rompería esa identidad. Por eso [runStack] NO debe llamarse
     *  dos veces con el mismo [startStack] esperando que quede intacto para
     *  un segundo uso independiente: la mutación de broken es compartida
     *  (ver visiblePending, que para LEFT usa startStack=emptyList() y para
     *  RIGHT pasa la pila LEFT ya calculada, sin volver a tocarla después).
     *
     *  Cada nivel guarda el CIERRE que espera, no el abridor que lo creó:
     *  una apertura nueva siempre agrega un nivel fresco e independiente,
     *  sin heredar la ruptura de un nivel viejo del mismo tipo de cierre
     *  (ver ejemplo en nota de clase de Level/doc de archivo). */
    private fun runStack(text: CharSequence, startStack: List<Level>): List<Level> {
        val stack = ArrayList<Level>(startStack)
        for (index in text.indices) {
            val ch = text[index]
            when {
                ch in OPENERS -> stack.add(Level(OPEN_TO_CLOSE[ch]!!, broken = false))
                ch in CLOSERS -> popOrBreak(stack, ch)
                ch in QUOTES -> {
                    // comilla sin match hacia abajo: nueva apertura (simétrica)
                    if (!popOrBreak(stack, ch)) stack.add(Level(ch, broken = false))
                }
                // cualquier otro carácter: no afecta la pila
            }
        }
        return stack
    }

    /** Un cierre (bracket o comilla) [ch] contra la pila [stack]: si hay un
     *  nivel sano que lo espera, lo saca (rompiendo todo lo que estaba por
     *  encima si no era el tope — cierre fuera de orden) y devuelve true;
     *  si no hay ningún nivel sano que lo espere, no toca la pila y devuelve
     *  false (CLOSERS: cierre basura, se ignora; QUOTES: el caller abre un
     *  nivel nuevo, ver [runStack]/[populateRestartIndex] — misma lógica de
     *  matching que antes vivía duplicada en cada rama del when de
     *  runStack). */
    private fun popOrBreak(stack: ArrayList<Level>, ch: Char): Boolean {
        if (stack.isNotEmpty() && stack.last().closer == ch && !stack.last().broken) {
            stack.removeAt(stack.size - 1)
            return true
        }
        var matchIdx = -1
        for (i in stack.indices.reversed()) {
            if (stack[i].closer == ch && !stack[i].broken) {
                matchIdx = i
                break
            }
        }
        if (matchIdx != -1) {
            for (i in matchIdx + 1 until stack.size) stack[i].broken = true
            stack.removeAt(matchIdx)
            return true
        }
        return false
    }

    /** Pila LEFT: resultado de [runStack] sobre text.substring(0, cursor)
     *  partiendo de una pila vacía. Instancias nuevas en cada llamada (no
     *  hay estado compartido entre invocaciones distintas).
     *
     *  Con [restartIndex] + [cursorLine] (ambos opcionales, van juntos): en
     *  vez de arrancar siempre desde el carácter 0 del archivo, arranca
     *  desde el punto de reinicio registrado más cercano al cursor (ver doc
     *  de clase de BracketRestartIndex) — mismo resultado exacto, mucho
     *  menos texto recorrido en archivos extensos con el cursor lejos del
     *  inicio. [cursorLine] (número de línea 0-based del cursor) lo pasa el
     *  caller porque CodeEditText ya lo mantiene actualizado de forma
     *  incremental (ver lastKnownCursorLine) — recalcularlo acá contando
     *  '\n' desde el carácter 0 sería, otra vez, un recorrido O(cursor), el
     *  mismo costo que esta función busca evitar. Sin [restartIndex] (null,
     *  el default), se comporta exactamente como antes.
     *
     *  Además, al recorrer el tramo [restartLine, cursor) registra en
     *  [restartIndex] cualquier NUEVO punto de reinicio que encuentre en el
     *  camino (línea donde la pila vuelve a dar vacía) — sin esto, el punto
     *  conocido más cercano quedaría fijo en el último calculado por
     *  populateRestartIndex() al abrir el archivo, y la distancia a
     *  recorrer crecería sin límite a medida que el usuario escribe código
     *  nuevo más allá de ese punto. */
    private fun leftStack(
        text: CharSequence,
        cursor: Int,
        restartIndex: BracketRestartIndex? = null,
        cursorLine: Int = -1
    ): List<Level> {
        val safeCursor = cursor.coerceIn(0, text.length)
        if (restartIndex == null || cursorLine < 0) {
            return runStack(text.subSequence(0, safeCursor), emptyList())
        }
        val restartLine = restartIndex.restartPointAtOrBefore(cursorLine) ?: 0
        val restartOffset = offsetOfLineStart(text, restartLine)
        return runStackTrackingRestarts(text, restartOffset, safeCursor, restartLine, restartIndex)
    }

    /** Offset (0-based) donde empieza la línea [lineIndex] (0-based) dentro
     *  de [text] — mismo criterio que CodeEditText.lineBoundsOf, reimplementado
     *  acá en vez de depender de esa clase (BracketAccumulator no conoce
     *  CodeEditText, y este cálculo es puro texto, sin estado de vista). */
    private fun offsetOfLineStart(text: CharSequence, lineIndex: Int): Int {
        if (lineIndex <= 0) return 0
        var start = 0
        var currentLine = 0
        while (currentLine < lineIndex) {
            var nextBreak = -1
            for (i in start until text.length) {
                if (text[i] == '\n') { nextBreak = i; break }
            }
            if (nextBreak == -1) return text.length
            start = nextBreak + 1
            currentLine++
        }
        return start
    }

    /** Igual que [runStack] sobre text.subSequence(from, to) partiendo de
     *  pila vacía, pero además registra en [restartIndex] cada línea donde
     *  la pila vuelve a dar vacía durante el recorrido (ver doc de clase de
     *  BracketRestartIndex). [startLine] es el número de línea (0-based)
     *  correspondiente a [from], para contar líneas de forma incremental
     *  sin volver a escanear desde el principio del archivo. Único lugar
     *  que recorre carácter por carácter contando líneas Y manteniendo la
     *  pila a la vez — tanto [populateRestartIndex] (recorrido completo, al
     *  abrir archivo) como [leftStack] con índice (recorrido parcial, en
     *  cada tecla) delegan acá para no duplicar esta lógica. */
    private fun runStackTrackingRestarts(
        text: CharSequence,
        from: Int,
        to: Int,
        startLine: Int,
        restartIndex: BracketRestartIndex
    ): List<Level> {
        val stack = ArrayList<Level>()
        var line = startLine
        for (index in from until to) {
            val ch = text[index]
            when {
                ch in OPENERS -> stack.add(Level(OPEN_TO_CLOSE[ch]!!, broken = false))
                ch in CLOSERS -> popOrBreak(stack, ch)
                ch in QUOTES -> {
                    if (!popOrBreak(stack, ch)) stack.add(Level(ch, broken = false))
                }
                ch == '\n' -> {
                    // La pila da vacía AL TERMINAR la línea `line` (acá, en
                    // su '\n') — eso garantiza pila vacía también al EMPEZAR
                    // la línea siguiente, que es el punto real por el que
                    // leftStack arranca (ver offsetOfLineStart). Se registra
                    // line + 1, no line: registrar `line` sería válido pero
                    // redundante (leftStack reprocesaría esa línea entera de
                    // nuevo sin necesidad, ya sabiendo que termina vacía).
                    if (stack.isEmpty()) restartIndex.markRestartPoint(line + 1)
                    line++
                }
            }
        }
        return stack
    }

    /** Recorre [text] desde el carácter 0 (SIEMPRE desde el inicio absoluto,
     *  nunca desde un punto de reinicio) registrando en [restartIndex] cada
     *  línea donde la pila combinada vuelve a quedar vacía — usado una sola
     *  vez al abrir un archivo (ver CodeEditText.highlightAll) para poblar
     *  el índice desde cero. Es tan caro como el leftStack de antes de esta
     *  optimización, pero se paga una sola vez, igual que
     *  Autocomplete.buildFullIndex. */
    fun populateRestartIndex(text: CharSequence, restartIndex: BracketRestartIndex) {
        restartIndex.clear()
        runStackTrackingRestarts(text, 0, text.length, 0, restartIndex)
    }

    /** Tipos de cierre que, mirando solo [text] hasta [cursor] (LEFT, nunca
     *  a la derecha), quedaron marcados rotos en la pila combinada — ya se
     *  resolvieron fuera de orden por algún cierre anterior al cursor. Un
     *  tipo roto por LEFT nunca se sugiere. */
    fun brokenTypes(text: CharSequence, cursor: Int): Set<Char> =
        leftStack(text, cursor).filter { it.broken }.map { it.closer }.toSet()

    /** Lo que la celda dedicada de la barra de controles debe mostrar y
     *  permitir insertar en [cursor]: para cada nivel que LEFT deja
     *  pendiente (abierto, no roto), se hereda esa misma pila y se continúa
     *  sobre el resto del documento (RIGHT); si ningún cierre futuro
     *  resuelve ese tipo, se sugiere. Los tipos que LEFT ya marca rotos
     *  nunca se sugieren. Se devuelve con el nivel MÁS profundo/reciente
     *  primero (tope de la pila) — la celda solo muestra el primero de la
     *  lista (ver notifyPendingCloser/firstOrNull), y es el nivel donde el
     *  cursor está escribiendo ahora mismo el que interesa ver, no el más
     *  externo/antiguo. */
    fun visiblePending(
        text: CharSequence,
        cursor: Int,
        restartIndex: BracketRestartIndex? = null,
        cursorLine: Int = -1
    ): List<Char> {
        val safeCursor = cursor.coerceIn(0, text.length)
        val stack = leftStack(text, safeCursor, restartIndex, cursorLine)
        val pendingLevels = stack.filter { !it.broken }.asReversed()

        // RIGHT-heredado: continúa con las MISMAS instancias (identidad),
        // nunca clonadas — así se puede preguntar al final si cada nivel
        // heredado en particular sigue presente, en vez de si "algún
        // cierre de ese tipo" apareció en el camino (ver doc de clase).
        val finalStack = runStack(text.subSequence(safeCursor, text.length), stack)
        val stillPending = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Level, Boolean>())
        stillPending.addAll(finalStack)

        val seenTypes = HashSet<Char>()
        val result = ArrayList<Char>()
        for (level in pendingLevels) {
            if (level in stillPending && seenTypes.add(level.closer)) {
                result.add(level.closer)
            }
        }
        return result
    }

    /** Cuántos niveles pendientes (sanos, no rotos) de [closer] hay en LEFT
     *  en este momento — a diferencia de [visiblePending], que colapsa por
     *  tipo (.distinct()) para decidir QUÉ sugerir, esto es solo para que
     *  la celda muestre un conteo real: "(((" con cursor al final son 3
     *  ')' pendientes, no 1, aunque [visiblePending] devuelva un solo ')'
     *  en su lista (correcto para esa decisión: solo hay un TIPO de cierre
     *  pendiente ahí). No afecta qué se sugiere ni la lógica de
     *  ruptura/anidamiento, solo el número que ve la UI. */
    fun pendingCount(
        text: CharSequence,
        cursor: Int,
        closer: Char,
        restartIndex: BracketRestartIndex? = null,
        cursorLine: Int = -1
    ): Int {
        val safeCursor = cursor.coerceIn(0, text.length)
        return leftStack(text, safeCursor, restartIndex, cursorLine).count { !it.broken && it.closer == closer }
    }
}

/**
 * Índice de "puntos de reinicio" para acelerar [BracketAccumulator.leftStack]
 * en archivos extensos (bug de rendimiento: antes, cada tecla recalculaba la
 * pila LEFT desde el carácter 0 del archivo hasta el cursor — con el cursor
 * cerca del final de un archivo de miles de líneas, eso es miles de líneas
 * recorridas EN CADA TECLA, aunque la mayoría no cambió).
 *
 * Idea: un "punto de reinicio" es cualquier línea donde, al terminar esa
 * línea, la pila de brackets/comillas ya queda VACÍA (nada pendiente) — en
 * código con llaves (JS/Kotlin/SQL/HTML) eso suele coincidir con el '}' que
 * cierra una función/clase; en Python (indentación, sin llaves) suele
 * coincidir en la práctica con el fin de una sentencia a columna 0, aunque
 * acá no se verifica nada de eso explícitamente — un punto de reinicio es,
 * por definición, allí donde la pila da vacía, sea cual sea la razón
 * sintáctica. Desde ese punto, leftStack puede arrancar con pila vacía en
 * vez de desde el carácter 0, sin perder nada de precisión: el resultado es
 * idéntico a recalcular desde el inicio absoluto, porque en ese punto el
 * estado acumulado real YA ERA vacío.
 *
 * [restartPoints] guarda línea (0-based, fin de esa línea) → Unit (un Set
 * sería lo mismo semánticamente, pero TreeMap permite floorKey() para
 * encontrar el punto de reinicio más cercano hacia arriba del cursor en
 * O(log n) en vez de recorrer todos).
 *
 * Mantenimiento: ver [invalidateFrom] — al editar una línea, todo punto de
 * reinicio cacheado en o después de esa línea se descarta (el estado de la
 * pila ahí pudo cambiar), y se recalcula perezosamente la próxima vez que
 * [BracketAccumulator.leftStack] lo necesite. Nunca queda un dato incorrecto: en el peor
 * caso (archivo sin ningún punto de reinicio útil cerca del cursor), el
 * costo es el mismo que sin este índice, nunca peor.
 */
class BracketRestartIndex {

    private val restartPoints = java.util.TreeMap<Int, Unit>()

    /** Registra que, al terminar la línea [lineIndex] (0-based), la pila de
     *  brackets/comillas queda vacía — llamado desde CodeEditText cada vez
     *  que recalcula el estado hasta una línea y encuentra que ahí da cero
     *  (ver su integración). */
    fun markRestartPoint(lineIndex: Int) {
        restartPoints[lineIndex] = Unit
    }

    /** Línea de reinicio válida más cercana, en o antes de [beforeOrAtLine]
     *  (0-based) — o null si no hay ninguna registrada ahí o antes, caso en
     *  el cual el caller debe recalcular desde el inicio absoluto del
     *  archivo (mismo comportamiento que sin este índice, nunca peor). */
    fun restartPointAtOrBefore(beforeOrAtLine: Int): Int? =
        restartPoints.floorKey(beforeOrAtLine)

    /** Descarta todo punto de reinicio registrado en o después de [lineIndex]
     *  — llamado al editar esa línea, porque el estado de la pila en esos
     *  puntos pudo cambiar (la edición puede haber agregado/quitado un
     *  bracket o comilla). Los de ANTES de [lineIndex] siguen siendo válidos
     *  sin tocar: el estado acumulado hasta ahí no depende de nada posterior. */
    fun invalidateFrom(lineIndex: Int) {
        restartPoints.tailMap(lineIndex, true).clear()
    }

    /** Descarta todo el índice — usado al abrir un archivo nuevo (mismo
     *  momento que Autocomplete.buildFullIndex, ver CodeEditText.highlightAll).
     *  A diferencia de Autocomplete, este índice NUNCA renumera claves tras
     *  insertar/eliminar líneas: una edición que agrega o quita líneas
     *  siempre invalida (ver invalidateFrom) todo desde el punto de la
     *  edición en adelante, porque también pudo cambiar cuántos brackets
     *  hay de ahí en más — así que no hay ninguna clave vieja que valga la
     *  pena preservar corriéndola de posición; simplemente se recalculan
     *  perezosamente, de a una, la próxima vez que haga falta. */
    fun clear() {
        restartPoints.clear()
    }
}
