package com.codemobile.ui

import android.view.View
import com.codemobile.editor.CodeEditText
import com.codemobile.editor.SuggestionsBarView
import com.codemobile.keyboard.CustomKeyboardView
import com.codemobile.keyboard.KeyboardTarget

/**
 * Centraliza "quién tiene el foco" del teclado propio (CustomKeyboardView)
 * y la visibilidad de controlBar/customKeyboard, que hasta ahora varios
 * dominios de EditorActivity (pestañas, explorador, consola/git, buscador
 * de código, diálogos genéricos) tocaban directamente cada uno por su
 * cuenta, repitiendo la misma secuencia (asignar target, forzar visible,
 * resincronizar margen).
 *
 * Extraído tal cual del comportamiento ya existente en EditorActivity: no
 * cambia ninguna regla, solo le da un único punto de entrada. [syncMargin]
 * es la única dependencia hacia afuera (vive en ControlsController/el
 * cálculo de altura de bottomControlsColumn) y se recibe como lambda para
 * no acoplar este router a esa clase.
 *
 * BUG encontrado (reportado: "el bracket accumulator no está escribiendo,
 * muestra correctamente los close bracket pero al tocarlo no los
 * inserta"): antes del refactor, controlBar.target y customKeyboard.target
 * se asignaban JUNTOS a mano en cada punto de EditorActivity que cambiaba
 * el foco (ver nota histórica en TabsController) — pendingCloserCell
 * (ControlBarView) depende de su propio campo `target: CodeEditText?`
 * para resolver insertPendingCloser(), independiente del `target:
 * KeyboardTarget?` de customKeyboard. Al centralizar el ruteo acá, solo se
 * siguió asignando customKeyboard.target; controlBar.target nunca más se
 * tocó en ningún punto del código actual, así que quedaba siempre null
 * (pendingCloserCell mostraba el cierre correcto, calculado por
 * BracketAccumulator sobre el archivo real, pero target?.
 * insertPendingCloser() no hacía nada al ser null). Ahora los 3 métodos
 * que asignan customKeyboard.target sincronizan también controlBar.target
 * con el mismo valor, acotado a CodeEditText (as?): cuando el foco va a un
 * PlainEditTextTarget (diálogos, consola, buscador), controlBar.target
 * queda en null a propósito — insertar un bracket pendiente del ARCHIVO no
 * tiene sentido fuera del editor de código.
 */
class KeyboardRouter(
    private val controlBar: ControlBarView,
    private val customKeyboard: CustomKeyboardView,
    private val suggestionsBar: SuggestionsBarView,
    private val syncMargin: () -> Unit
) {

    /** Punto único para mandar el teclado propio a [target], asegurando que
     *  controlBar/customKeyboard queden visibles y el margen de
     *  contentColumn se resincronice — mismo criterio que ya aplicaban
     *  routeKeyboardToSearchBar/routeKeyboardToExplorerSearch/
     *  routeKeyboardToGitConsole antes de este refactor. */
    fun routeTo(target: KeyboardTarget?) {
        customKeyboard.target = target
        controlBar.target = target as? CodeEditText
        controlBar.visibility = View.VISIBLE
        if (customKeyboard.visibility != View.VISIBLE) {
            customKeyboard.visibility = View.VISIBLE
            controlBar.setKeyboardHidden(false)
        }
        syncMargin()
    }

    /** Variante sin tocar visibilidad: usada por showDialogForm/diálogos
     *  genéricos, que asignan el target de los campos del formulario a
     *  medida que ganan foco, sin forzar nada de visibilidad en cada tap
     *  (eso ya se hizo una vez al abrir el formulario). */
    fun setTargetOnly(target: KeyboardTarget?) {
        customKeyboard.target = target
        controlBar.target = target as? CodeEditText
    }

    fun currentTarget(): KeyboardTarget? = customKeyboard.target

    fun isKeyboardVisible(): Boolean = customKeyboard.visibility == View.VISIBLE

    fun controlBarVisibility(): Int = controlBar.visibility

    fun keyboardVisibility(): Int = customKeyboard.visibility

    /** Restaura target/visibilidad de controlBar/customKeyboard a valores
     *  guardados previamente (ver DialogFormHost.show/close) sin pasar por
     *  routeTo, que fuerza visible — closeDialogForm necesita poder volver
     *  a un estado donde ambos estaban ocultos (p. ej. en modo explorador). */
    fun restoreState(target: KeyboardTarget?, controlBarVisibility: Int, keyboardVisibility: Int) {
        customKeyboard.target = target
        controlBar.target = target as? CodeEditText
        controlBar.visibility = controlBarVisibility
        customKeyboard.visibility = keyboardVisibility
        syncMargin()
    }

    /** Ocultar el teclado propio debe ocultar también suggestionsBar: antes
     *  solo se llamaba suggestionsBar.hide() (vacía los chips, pero por
     *  diseño deja visibility=VISIBLE con su alto reservado — ver doc de
     *  clase de SuggestionsBarView, a propósito para no colapsar su altura
     *  y evitar el bucle de scroll que documenta ahí), así que la barra
     *  quedaba "flotando" vacía en pantalla sin el teclado. GONE acá no
     *  reintroduce ese bug: measure/alto fijo solo importa mientras la
     *  vista participa del layout siendo VISIBLE; en GONE no se mide en
     *  absoluto, y showKeyboard() la vuelve a VISIBLE más abajo, donde
     *  expectedHeightPx() se aplica igual que siempre. */
    fun hideKeyboard() {
        customKeyboard.visibility = View.GONE
        suggestionsBar.hide()
        suggestionsBar.visibility = View.GONE
    }

    fun showKeyboard() {
        customKeyboard.visibility = View.VISIBLE
        suggestionsBar.visibility = View.VISIBLE
    }
}
