package com.codemobile.settings

import android.os.Bundle
import android.view.View
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.codemobile.R
import com.codemobile.keyboard.AccentPopupView
import com.codemobile.keyboard.ConfigPanelView
import com.codemobile.keyboard.CustomKeyboardView
import com.codemobile.keyboard.KeyboardSettingsStore
import com.codemobile.keyboard.PendingKeyboardSettings
import com.codemobile.keyboard.RealKeyboardSettingsSource
import com.codemobile.keyboard.RingGestureView

/**
 * Paso 7, §5: pantalla de configuración del teclado propio. A diferencia de
 * EditorActivity, esta Activity SÍ usa el IME real de sistema (ver
 * activity_settings.xml y el manifest: no lleva
 * windowSoftInputMode="adjustResize|stateAlwaysHidden" ni
 * showSoftInputOnFocus=false en ningún EditText) — el campo de texto real
 * para escribir una variante/símbolo nuevo vive dentro de ConfigPanelView y
 * necesita el teclado del sistema para que el usuario pueda tipear.
 *
 * El teclado propio que se muestra ACÁ (settingsKeyboard) no es para
 * escribir texto: se usa en configMode = true, donde tocar una tecla
 * editable abre configPanel en vez de insertar un carácter (ver doc de
 * CustomKeyboardView.configMode). target se deja en null a propósito: no
 * hay ningún CodeEditText/PlainEditTextTarget real que editar desde esta
 * pantalla.
 *
 * Paso 7 (botón "Aplicar", 3 estados: default de fábrica / actual guardado
 * / cambios en memoria sin guardar — pedido explícito): ni settingsKeyboard
 * ni configPanel escriben directo a KeyboardSettingsStore acá — ambos
 * comparten [pending] (ver PendingKeyboardSettings), un buffer en memoria.
 * "Aplicar" vuelca ese buffer entero al store real de una sola vez; salir
 * de esta Activity sin tocarlo descarta todo lo pendiente (pedido
 * explícito: "se pierden al salir sin aplicar"). El toggle de haptic es la
 * ÚNICA excepción: escribe directo al store real sin pasar por el buffer
 * (ver doc de PendingKeyboardSettings.isHapticEnabled/setHapticEnabled) —
 * no es un override de layout, no tiene sentido "pendiente".
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsKeyboard: CustomKeyboardView
    private lateinit var hapticToggle: Switch
    private lateinit var resetDefaultsButton: TextView
    private lateinit var backButton: TextView
    private lateinit var applyButton: TextView
    private lateinit var pending: PendingKeyboardSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        pending = PendingKeyboardSettings(RealKeyboardSettingsSource(this))
        bindViews()
        setupKeyboard()
        setupGeneralSettings()
        setupApplyButton()
        backButton.setOnClickListener { finish() }
    }

    private fun bindViews() {
        settingsKeyboard = findViewById(R.id.settingsKeyboard)
        hapticToggle = findViewById(R.id.hapticToggle)
        resetDefaultsButton = findViewById(R.id.resetDefaultsButton)
        backButton = findViewById(R.id.settingsBackButton)
        applyButton = findViewById(R.id.applyButton)

        // Mismo patrón de inyección que EditorActivity.bindViews(): los
        // overlays de gesto viven como hermanos de settingsKeyboard en el
        // FrameLayout raíz, nunca como hijos (ver comentario largo en
        // activity_editor.xml sobre por qué).
        settingsKeyboard.ringOverlay = findViewById<RingGestureView>(R.id.settingsRingOverlay)
        settingsKeyboard.accentPopup = findViewById<AccentPopupView>(R.id.settingsAccentPopup)
        settingsKeyboard.suggestionsBar = findViewById(R.id.settingsSuggestionsBar)
        settingsKeyboard.configPanel = findViewById<ConfigPanelView>(R.id.settingsConfigPanel)
    }

    private fun setupKeyboard() {
        // configMode y settingsSource deben quedar asignados ANTES de que
        // la vista arranque a construir sus teclas (ver doc de configMode/
        // settingsSource en CustomKeyboardView): se leen en tiempo de
        // construcción, no por tap. target queda null: nada real que
        // editar desde acá. configPanel comparte el MISMO buffer (ver doc
        // de clase) — si no, el panel y el teclado de atrás mostrarían
        // dos copias independientes del mismo override.
        settingsKeyboard.configMode = true
        settingsKeyboard.settingsSource = pending
        settingsKeyboard.configPanel.settingsSource = pending
        // Cada vez que se cierra un panel de edición habiendo tocado el
        // buffer (ver doc de onSettingsChanged en CustomKeyboardView),
        // refresca si el botón Aplicar debe mostrarse.
        settingsKeyboard.onSettingsChanged = { refreshApplyButtonVisibility() }
        settingsKeyboard.refreshFromSettings()
    }

    private fun setupGeneralSettings() {
        // Haptic: fuera del buffer a propósito (ver doc de clase) —
        // escribe directo al store real, se refleja de inmediato en
        // cualquier teclado (incluido el normal de EditorActivity), sin
        // esperar "Aplicar".
        hapticToggle.isChecked = KeyboardSettingsStore.isHapticEnabled(this)
        hapticToggle.setOnCheckedChangeListener { _, checked ->
            KeyboardSettingsStore.setHapticEnabled(this, checked)
        }

        resetDefaultsButton.setOnClickListener {
            // Reset ahora es PENDIENTE (ver PendingKeyboardSettings.
            // resetToDefaults): no toca SharedPreferences todavía, solo
            // vacía el buffer y marca el reset para que "Aplicar" lo
            // escriba de verdad. haptic_enabled queda intacto (fuera del
            // buffer por completo, ver arriba).
            pending.resetToDefaults()
            settingsKeyboard.refreshFromSettings()
            refreshApplyButtonVisibility()
        }
    }

    private fun setupApplyButton() {
        applyButton.setOnClickListener {
            pending.applyToStore()
            refreshApplyButtonVisibility()
        }
        refreshApplyButtonVisibility()
    }

    /** Se llama tras CUALQUIER cambio que pueda haber tocado el buffer:
     *  cerrar un panel de edición (ver onChanged pasado a
     *  configPanel.showLetterPanel/showRingPanel/showSymbolEdit, que ya
     *  llama refreshFromSettings() — acá se encadena también esta
     *  comprobación) y el botón de reset. refreshFromSettings() por sí
     *  solo NO decide la visibilidad del botón Aplicar: son dos cosas
     *  separadas (redibujar las teclas vs. mostrar/ocultar el botón), por
     *  eso esta función se llama aparte en cada punto que puede dejar
     *  cambios pendientes. */
    private fun refreshApplyButtonVisibility() {
        applyButton.visibility = if (pending.hasPendingChanges()) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        // Por si el usuario volvió de background con cambios pendientes
        // de antes (la Activity no se recreó, pending sigue en memoria):
        // refleja el estado real del botón sin depender de que algún
        // onChanged particular haya corrido.
        refreshApplyButtonVisibility()
    }
}
