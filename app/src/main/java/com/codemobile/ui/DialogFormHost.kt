package com.codemobile.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import com.codemobile.R
import com.codemobile.keyboard.KeyboardTarget
import com.codemobile.keyboard.PlainEditTextTarget

/**
 * Mecanismo genérico de "panel-formulario" (vista propia dentro de
 * dialogFormContainer en vez de un AlertDialog nativo, que quedaba tapado
 * por bottomControlsColumn — ver el historial de bugs ya corregidos sobre
 * esto en el código original). Usado hoy por: nuevo archivo, renombrar,
 * credenciales git, identidad git. Extraído sin cambiar ninguna regla de
 * showDialogForm/closeDialogForm original.
 *
 * No incluye showBackupStrategyDialog (ese panel no tiene EditText, usa
 * dialogFormContainer directamente sin pasar por este host) — queda en
 * TabsController, que sí necesita guardar/restaurar el mismo trío de
 * estado pre-diálogo; ver el comentario en esa clase.
 *
 * BUG encontrado #1 (reportado: el teclado propio y la tarjeta "se
 * colapsan" con cualquier toque al teclado mientras un diálogo está
 * abierto): dialogFormContainer es match_parent/elevation alta (ver su
 * comentario en activity_editor.xml) y su listener de "tocar afuera
 * cierra" cubría TODA esa área, incluyendo la franja donde se dibuja
 * bottomControlsColumn (customKeyboard/controlBar) — un hermano suyo en el
 * árbol de vistas, pero que se solapa visualmente con esa misma región de
 * pantalla. Cualquier toque dirigido al teclado caía entonces sobre
 * dialogFormContainer, no sobre el teclado, y se interpretaba como "tocó
 * afuera": cerraba el diálogo y restauraba el estado previo del teclado,
 * dando la sensación de que todo colapsaba.
 *
 * BUG encontrado #2, remanente del fix anterior (reportado: el diálogo ya
 * no se cierra al tocar el teclado, pero el teclado queda debajo y no
 * escribe nada): el fix de #1 hacía que el OnTouchListener de
 * dialogFormContainer devolviera false en esa franja esperando que el
 * evento "siguiera su curso" hacia bottomControlsColumn. Eso no funciona:
 * un OnTouchListener que devuelve false solo le dice a ESA MISMA vista que
 * no consumió el evento, pero dialogFormContainer ya es quien lo recibió
 * del dispatcher del sistema por estar arriba en el z-order y cubrir toda
 * la pantalla — el evento no salta mágicamente a una vista hermana de
 * menor elevación, se descarta ahí. El teclado nunca lo recibía.
 *
 * Fix real: en vez de intentar "dejar pasar" un evento que ya fue
 * capturado por dialogFormContainer, se recorta su altura real (ver
 * [show], layout_height se fija en código al alto medido de la pantalla
 * menos el alto de bottomControlsColumn) para que esa franja quede
 * directamente FUERA del árbol de dialogFormContainer. Así
 * bottomControlsColumn la recibe de forma nativa, sin ningún truco de
 * listener. [bottomControlsColumn] se usa para medir ese recorte (antes
 * solo para el chequeo de touch, ahora innecesario y eliminado).
 */
class DialogFormHost(
    private val dialogFormContainer: FrameLayout,
    private val layoutInflater: LayoutInflater,
    private val keyboardRouter: KeyboardRouter,
    private val bottomControlsColumn: View
) {

    private var preDialogKeyboardTarget: KeyboardTarget? = null
    private var preDialogControlBarVisibility: Int = View.GONE
    private var preDialogKeyboardVisibility: Int = View.GONE

    fun show(
        title: String,
        contentLayoutRes: Int,
        bind: (View) -> List<EditText>,
        firstField: (List<EditText>) -> EditText,
        onConfirm: (List<EditText>) -> Boolean
    ) {
        preDialogKeyboardTarget = keyboardRouter.currentTarget()
        preDialogControlBarVisibility = keyboardRouter.controlBarVisibility()
        preDialogKeyboardVisibility = keyboardRouter.keyboardVisibility()

        dialogFormContainer.removeAllViews()
        val card = layoutInflater.inflate(R.layout.view_dialog_form, dialogFormContainer, false)
        card.findViewById<TextView>(R.id.dialogFormTitle).text = title
        val body = card.findViewById<FrameLayout>(R.id.dialogFormBody)
        val content = layoutInflater.inflate(contentLayoutRes, body, false)
        body.addView(content)
        val fields = bind(content)

        for (field in fields) {
            field.showSoftInputOnFocus = false
            field.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) keyboardRouter.setTargetOnly(PlainEditTextTarget(field))
            }
            field.setOnClickListener { keyboardRouter.setTargetOnly(PlainEditTextTarget(field)) }
        }

        card.findViewById<TextView>(R.id.dialogFormCancel).setOnClickListener { close() }
        card.findViewById<TextView>(R.id.dialogFormConfirm).setOnClickListener {
            if (onConfirm(fields)) close()
        }

        // Ver comentario original: card.isClickable=true es necesario para
        // que tocar DENTRO de la tarjeta (título, espacios vacíos de una
        // grilla) no cuente como "fuera" para el listener del contenedor.
        card.isClickable = true
        // "Tocar afuera cierra": ya no hace falta excluir a mano la franja
        // de bottomControlsColumn acá (ver BUG #2 en el doc de clase) —
        // esa franja queda fuera del alto real de dialogFormContainer
        // (shrinkToExcludeBottomControls más abajo), así que cualquier
        // click que SÍ llega hasta este listener es, por construcción,
        // fuera de esa zona.
        dialogFormContainer.setOnClickListener { close() }
        dialogFormContainer.addView(card)
        dialogFormContainer.visibility = View.VISIBLE

        val target = firstField(fields)
        // BUG encontrado #3 (reportado: diálogo del explorador se abre
        // pero no se puede escribir — bottomControlsColumn/controlBar/
        // customKeyboard seguían en GONE ahí, a diferencia del editor):
        // routeTo() es quien recién pone controlBar/customKeyboard en
        // VISIBLE. shrinkToExcludeBottomControls() necesita leer
        // bottomControlsColumn.top DESPUÉS de ese cambio de visibilidad,
        // nunca antes — si se mide antes (como hacía esta función
        // previamente, en este mismo punto del código), bottomControlsColumn
        // todavía puede estar colapsada (altura 0, como ocurre siempre al
        // abrir un diálogo desde el explorador, donde controlBar/
        // customKeyboard arrancan en GONE) y el recorte calculado no
        // coincide con el tamaño real que el teclado tendrá una vez
        // mostrado. post{} además asegura que el layout pass de ese cambio
        // de visibilidad ya corrió antes de leer .top.
        keyboardRouter.routeTo(PlainEditTextTarget(target))
        dialogFormContainer.post { shrinkToExcludeBottomControls() }
        target.requestFocus()
    }

    /** Recorta el alto real de dialogFormContainer (layout_height en
     *  código, no solo clipping visual) para que termine justo donde
     *  arranca bottomControlsColumn en pantalla. Así esa franja queda
     *  directamente fuera del árbol de vistas de dialogFormContainer: el
     *  sistema le entrega el touch a bottomControlsColumn de forma nativa,
     *  sin que dialogFormContainer lo intercepte primero (ver BUG #2 en el
     *  doc de clase — por qué un OnTouchListener que "deja pasar" el
     *  evento no alcanza).
     *
     *  Se calcula contra bottomControlsColumn.top directamente, NO contra
     *  getLocationOnScreen de dialogFormContainer: este último puede estar
     *  recién saliendo de GONE (primer show() de la sesión) y no tener
     *  todavía una posición de pantalla válida tras su propio layout pass.
     *  bottomControlsColumn, en cambio, ya está en pantalla de antes (es
     *  parte permanente del editor) y, al ser hermano de layout_height
     *  match_parent dentro del mismo root, su .top ya es la coordenada Y
     *  relativa a ese root — exactamente el alto que necesita
     *  dialogFormContainer para terminar justo ahí. */
    private fun shrinkToExcludeBottomControls() {
        val parent = dialogFormContainer.parent as? View
        val parentHeight = parent?.height ?: 0
        val columnTop = bottomControlsColumn.top
        val newHeight = if (columnTop > 0) columnTop else parentHeight
        val params = dialogFormContainer.layoutParams
        params.height = newHeight
        dialogFormContainer.layoutParams = params
    }

    fun close() {
        dialogFormContainer.visibility = View.GONE
        dialogFormContainer.removeAllViews()
        dialogFormContainer.setOnClickListener(null)
        // Restaurar a match_parent: el próximo show() vuelve a medir desde
        // el alto completo, nunca parte de un recorte viejo que haya
        // quedado de una franja de teclado distinta (ej. controlBar oculto
        // vs visible cambia la altura de bottomControlsColumn).
        val params = dialogFormContainer.layoutParams
        params.height = FrameLayout.LayoutParams.MATCH_PARENT
        dialogFormContainer.layoutParams = params
        keyboardRouter.restoreState(
            preDialogKeyboardTarget,
            preDialogControlBarVisibility,
            preDialogKeyboardVisibility
        )
    }
}
