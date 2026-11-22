package com.codemobile.keyboard

import android.widget.EditText

/**
 * KeyboardTarget para un EditText plano (sin nada de código): el campo de
 * comandos git en la consola (ver EditorActivity.openGitConsole). A
 * diferencia de CodeEditText, acá no hay resaltado, indentación, ni
 * BracketAccumulator — solo inserción/borrado/navegación de texto
 * genéricas, usando directamente la API estándar de EditText.
 *
 * moveCursor no tiene modo de selección extendida (CodeEditText.selectModeActive):
 * ese modo es una función del editor de código sin sentido para un comando
 * de una sola línea, así que acá las flechas simplemente mueven el cursor.
 */
class PlainEditTextTarget(
    private val editText: EditText,
    // Tocar "↵" en el teclado propio nunca dispara setOnEditorActionListener
    // del EditText (eso solo lo activa el IME de sistema real al presionar
    // SU botón de acción, un mecanismo interno de Android no invocable
    // desde afuera sin reflexión) — sin este callback, "\n" se insertaría
    // como salto de línea literal en el campo y el comando git nunca se
    // enviaría. Si se provee y devuelve true (consumido), el "\n" NO se
    // inserta como texto — lo usa EditorActivity.routeKeyboardToGitConsole
    // para enviar el comando en vez de insertar una línea nueva, igual que
    // haría un Enter real de IME en un campo de una sola línea (singleLine,
    // ver consoleGitInput). Null (el default) conserva el comportamiento
    // normal de insertar "\n".
    private val onEnterPressed: (() -> Boolean)? = null
) : KeyboardTarget {

    override fun insertAtCursor(s: String) {
        if (s == "\n" && onEnterPressed?.invoke() == true) return
        val editable = editText.text ?: return
        val start = editText.selectionStart.coerceIn(0, editable.length)
        val end = editText.selectionEnd.coerceIn(0, editable.length)
        if (start != end) {
            editable.replace(minOf(start, end), maxOf(start, end), s)
        } else {
            editable.insert(start, s)
        }
    }

    override fun backspaceAtCursor() {
        val editable = editText.text ?: return
        val start = editText.selectionStart.coerceIn(0, editable.length)
        val end = editText.selectionEnd.coerceIn(0, editable.length)
        if (start != end) {
            editable.delete(minOf(start, end), maxOf(start, end))
        } else if (start > 0) {
            editable.delete(start - 1, start)
        }
    }

    override fun backspaceWordAtCursor() {
        val editable = editText.text ?: return
        val start = editText.selectionStart.coerceIn(0, editable.length)
        val end = editText.selectionEnd.coerceIn(0, editable.length)
        if (start != end) {
            editable.delete(minOf(start, end), maxOf(start, end))
            return
        }
        if (start <= 0) return
        var i = start
        while (i > 0 && !editable[i - 1].isLetterOrDigit()) i--
        if (i == start) {
            while (i > 0 && editable[i - 1].isLetterOrDigit()) i--
        }
        editable.delete(i, start)
    }

    /** Campo de una sola línea: no hay "línea" sobre la que indentar/
     *  desindentar como en CodeEditText. increase=true inserta un tab
     *  literal (igual que cualquier otra tecla, respeta selección activa
     *  reemplazándola, vía insertAtCursor). increase=false (hold) no hace
     *  nada — no hay un gesto equivalente de "quitar indentación" que tenga
     *  sentido en un comando de una sola línea. */
    override fun tabKey(increase: Boolean) {
        if (increase) insertAtCursor("\t")
    }

    override fun moveCursor(horizontal: Int, vertical: Int) {
        // Campo de una sola línea (singleLine en el XML): "vertical" no
        // tiene a dónde moverse, así que se ignora en vez de intentar
        // calcular líneas como hace CodeEditText.moveCursor con su Layout.
        if (vertical != 0) return
        val editable = editText.text ?: return
        val cursor = editText.selectionStart.coerceIn(0, editable.length)
        val newPos = (cursor + horizontal).coerceIn(0, editable.length)
        editText.setSelection(newPos)
    }

    /** Mismo criterio que CodeEditText.allText(): si hay una selección
     *  activa (selectionStart/selectionEnd, los mismos que ya usan
     *  insertAtCursor/backspaceAtCursor arriba — este EditText sí soporta
     *  selección nativa de Android aunque no tenga el modo extendido de
     *  CodeEditText.selectModeActive), se copia solo esa porción; sin
     *  selección, el campo completo. */
    override fun allText(): String {
        val full = editText.text?.toString() ?: return ""
        val start = editText.selectionStart.coerceIn(0, full.length)
        val end = editText.selectionEnd.coerceIn(0, full.length)
        return if (end > start) {
            full.substring(start, end)
        } else {
            full
        }
    }

    /** Ver doc de KeyboardTarget.selectedTextOrNull(): mismo cálculo que
     *  allText() arriba, pero sin su fallback al campo completo. */
    override fun selectedTextOrNull(): String? {
        val full = editText.text?.toString() ?: return null
        val start = editText.selectionStart.coerceIn(0, full.length)
        val end = editText.selectionEnd.coerceIn(0, full.length)
        return if (end > start) full.substring(start, end) else null
    }

    /** Campo de una sola línea, sin Lang asociado (ver doc de la interfaz):
     *  "comentar" no tiene sentido acá, no-op. */
    override fun toggleLineComment() {}
}
