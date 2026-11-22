package com.codemobile.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Compensa a mano los insets de sistema (status bar/notch arriba, franja de
 * gestos/nav bar abajo) ya que setDecorFitsSystemWindows(false) se mantiene
 * para que rootLayout pueda dibujarse detrás de las barras de sistema sin
 * que el sistema reserve ese espacio por su cuenta. Extraído tal cual de
 * EditorActivity.setupKeyboardInsetHandling, sin cambiar ninguna regla.
 *
 * [hasOpenTab] (hacia TabsController) e [syncBottomControlsMargin] (hacia
 * ControlsController): el margen de contentColumn solo necesita
 * recalcularse si hay una pestaña activa (customKeyboard, lo que ese
 * margen reserva espacio para, solo está visible entonces — Paso 13: antes
 * era isEditorModeVisible/"modo editor", que ya no existe como concepto),
 * y ese cálculo en sí vive en ese otro controller (altura real de
 * bottomControlsColumn).
 */
class SystemInsetsController(
    private val rootView: View,
    private val contentColumn: View,
    private val bottomControlsColumn: View,
    private val bottomControlsRow: View,
    private val hasOpenTab: () -> Boolean,
    private val syncBottomControlsMargin: () -> Unit,
    private val reapplyLandscapeRowLayoutIfVisible: () -> Unit
) {

    private var systemBarsBottomInsetPx: Int = 0

    fun bottomInsetPx(): Int = systemBarsBottomInsetPx

    fun setup() {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            contentColumn.setPadding(
                contentColumn.paddingLeft,
                systemBarInsets.top,
                contentColumn.paddingRight,
                0
            )
            bottomControlsColumn.setPadding(
                bottomControlsColumn.paddingLeft,
                bottomControlsColumn.paddingTop,
                bottomControlsColumn.paddingRight,
                systemBarInsets.bottom
            )
            // bottomControlsRow es el equivalente de bottomControlsColumn en
            // landscape (ver ControlsController.applyBottomControlsOrientation):
            // antes no recibía este padding, por lo que la franja de
            // gestos/nav bar quedaba tapando sus controles.
            bottomControlsRow.setPadding(
                bottomControlsRow.paddingLeft,
                bottomControlsRow.paddingTop,
                bottomControlsRow.paddingRight,
                systemBarInsets.bottom
            )
            // setPadding() por sí solo no dispara un remeasure síncrono de
            // bottomControlsRow: su alto quedó fijado como un valor EXACTO
            // en applyBottomControlsOrientation (ver forceMeasureAndLayout
            // ahí), así que sin este re-forzado el padding quedaba asignado
            // pero el contenido interno (suggestionsSlot/keyboardSlot/
            // controlsSlot) seguía ocupando el mismo espacio de antes,
            // tapado por la franja de gestos.
            reapplyLandscapeRowLayoutIfVisible()
            if (systemBarsBottomInsetPx != systemBarInsets.bottom) {
                systemBarsBottomInsetPx = systemBarInsets.bottom
                // Si hay una pestaña activa, el margen de contentColumn
                // tiene que recalcularse con el nuevo inset (puede cambiar
                // al rotar o si el sistema cambia el modo de navegación).
                if (hasOpenTab()) {
                    syncBottomControlsMargin()
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }
}
