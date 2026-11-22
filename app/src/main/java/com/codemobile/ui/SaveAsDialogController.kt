package com.codemobile.ui

import android.content.Context
import android.widget.EditText
import android.widget.Toast
import com.codemobile.R

/**
 * Diálogo "Guardar como" (Paso 7: tecla S del drag-abajo, sostenida más
 * allá del umbral — ver KeyboardLayouts.DragAction.SAVE_AS/HOLD_SWITCHES_TO
 * y gestureKey()/ControlsController.onDragAction). Reusa
 * R.layout.dialog_new_folder (un solo campo de nombre) igual que el
 * diálogo de "Nueva carpeta"/"Renombrar" en ExplorerController: no hace
 * falta la grilla de tipos de NewFileDialogController, el tipo de archivo
 * ya está fijado por el archivo activo (se guarda con su extensión actual
 * salvo que el usuario la cambie a mano en el nombre).
 *
 * A diferencia de NewFileDialogController (crea un archivo VACÍO y abre una
 * pestaña nueva), esto reusa la pestaña activa y escribe su contenido
 * actual en la ruta nueva (ver TabsController.saveActiveFileAs) — el
 * archivo viejo, si existía, queda intacto en disco.
 */
class SaveAsDialogController(
    private val context: Context,
    private val dialogFormHost: DialogFormHost,
    private val tabs: TabsController
) {

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun show() {
        dialogFormHost.show(
            title = "Save as",
            contentLayoutRes = R.layout.dialog_new_folder,
            bind = { dialogView ->
                val nameEdit = dialogView.findViewById<EditText>(R.id.editFolderName)
                nameEdit.setText(tabs.activeFileName())
                nameEdit.setSelection(0, nameEdit.text.length)
                listOf(nameEdit)
            },
            firstField = { it[0] },
            onConfirm = { fields ->
                val name = fields[0].text.toString().trim()
                // Mismo criterio mínimo que FileRepository.invalidNameReason
                // (privada, no expuesta: createFile/saveAs no validan nada
                // por su cuenta, ver su doc — "/" o "\" acá crearía una
                // ruta anidada por accidente o se escaparía de currentDir).
                when {
                    name.isEmpty() -> {
                        toast("Name can't be empty")
                        false
                    }
                    name.contains('/') || name.contains('\\') -> {
                        toast("Name can't contain / or \\")
                        false
                    }
                    else -> {
                        val result = tabs.saveActiveFileAs(tabs.activeFileDir(), name)
                        if (result == null) {
                            toast("Couldn't save")
                            false
                        } else {
                            true
                        }
                    }
                }
            }
        )
    }
}
