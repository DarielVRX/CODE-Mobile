package com.codemobile.keyboard

/**
 * Lo mínimo que CustomKeyboardView necesita de "donde está escribiendo el
 * usuario" para operar: insertar texto, borrar (carácter o palabra) y mover
 * el cursor. CodeEditText ya implementa estos 4 métodos con su lógica
 * completa de código (indentación, resaltado, etc. — todo eso vive adentro
 * de esos métodos, esta interfaz no lo toca).
 *
 * Por qué existe: CustomKeyboardView.target no puede ser CodeEditText? a
 * secas, porque entonces el teclado propio solo podría escribir en el
 * editor de código. El campo de comandos git (EditText plano de una sola
 * línea, sin ninguna de las funciones de código) necesita el mismo
 * teclado, y mezclar el teclado de sistema ahí haría reaparecer el IME de
 * sistema al reanudar la app. En vez de generalizar CodeEditText o
 * duplicar toda su lógica, esta interfaz expone solo los 4 métodos que
 * CustomKeyboardView realmente llama (ver sus usos de `target?.`), y
 * PlainEditTextTarget (ver ese archivo) los implementa con operaciones de
 * texto genéricas de EditText, sin nada específico de código.
 */
interface KeyboardTarget {
    fun insertAtCursor(s: String)
    fun backspaceAtCursor()
    fun backspaceWordAtCursor()
    fun moveCursor(horizontal: Int = 0, vertical: Int = 0)

    /** Tecla "⇥" del teclado propio (reemplaza al viejo gesto de pinch sobre
     *  el gutter, que no distinguía selección activa): [increase]=true es un
     *  tap (indentar: con selección, el inicio de cada línea que la
     *  selección toca, aunque sea parcial; sin selección, inserta la unidad
     *  de indentación en el cursor). [increase]=false es un hold
     *  (desindentar: con selección, cada línea que toca; sin selección, la
     *  línea actual — nunca inserta nada). CodeEditText tiene la
     *  implementación real (multi-línea, usa AutoIndent.indentUnitFor según
     *  el Lang del archivo — espacios, no \t, ver su doc de clase);
     *  PlainEditTextTarget (sin concepto de "línea" relevante en un campo de
     *  un comando, y sin ningún Lang asociado) solo cubre el caso de tap
     *  simple insertando "\t" literal, a propósito — esa excepción es suya,
     *  no de esta interfaz. */
    fun tabKey(increase: Boolean)

    /** Texto a copiar de este target: el botón de "paste" del anillo
     *  también copia cuando el portapapeles está vacío (ver
     *  CustomKeyboardView.pasteOrCopy()). Nombre histórico ("allText") que
     *  ya no describe del todo el comportamiento: si hay una selección
     *  activa (ver CodeEditText.allText), devuelve solo esa porción. Sin
     *  selección, copia el contenido completo. PlainEditTextTarget (campo
     *  de una sola línea, sin modo de selección extendida propio) siempre
     *  devuelve el contenido entero.
     *
     *  Se mantiene (y su contrato de "todo si no hay selección") porque
     *  Ctrl+A (tecla A, Paso 7) también depende de este mismo target para
     *  "seleccionar todo" — NO es el método a usar para copiar/cortar, ver
     *  selectedTextOrNull() abajo. */
    fun allText(): String

    /** Texto seleccionado de este target, o null si no hay ninguna
     *  selección real activa (selectionEnd <= selectionStart) — a
     *  diferencia de allText(), SIN fallback a "todo el contenido". Paso 7:
     *  usado por Copiar (tecla C) y Cortar (tecla X) del drag-abajo del
     *  qwerty, que no deben actuar sobre nada si el usuario no seleccionó
     *  texto explícitamente. */
    fun selectedTextOrNull(): String?

    /** Tecla Q del drag-abajo (Paso 7, reemplaza a INSERT_SLASH): comenta o
     *  descomenta las líneas tocadas por la selección (o solo la línea del
     *  cursor sin selección) — mismo alcance de rango que tabKey (ver su
     *  doc). CodeEditText tiene la implementación real, dependiente del
     *  Lang del archivo (ver AutoIndent.lineCommentPrefixFor): no hace
     *  nada si el lenguaje no tiene comentario de línea (HTML).
     *  PlainEditTextTarget (sin Lang ni concepto de línea de código propio)
     *  no hace nada: un campo de un comando no tiene "comentarios". */
    fun toggleLineComment()
}
