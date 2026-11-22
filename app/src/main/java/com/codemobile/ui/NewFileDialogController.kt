package com.codemobile.ui

import android.content.Context
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import com.codemobile.R
import com.codemobile.files.FileRepository
import com.codemobile.files.FileType
import com.codemobile.files.OpenFile
import java.io.File

/**
 * Diálogo "Nuevo archivo" (grilla de tipos + título + extensión) y creación
 * de carpetas/menú de creación del explorador. Extraído tal cual de
 * EditorActivity (showNewFileDialog/bindNewFileDialogContent/
 * createAndOpenFile/showNewFolderDialog/showCreateMenu), sin cambiar
 * ninguna regla.
 *
 * Es el dominio más "puro" de los 7: no guarda estado propio entre
 * llamadas (selectedType/chips/titleEdit/extEdit viven y mueren dentro de
 * cada invocación del diálogo). Depende de [currentDir] (lectura, nunca
 * escritura — este controller nunca cambia la carpeta actual del
 * explorador) y notifica la creación vía [onFileCreated]/[onFolderCreated]
 * en vez de referenciar directamente a TabsController/ExplorerController,
 * para no acoplarse a ninguno de los dos.
 */
class NewFileDialogController(
    private val context: Context,
    private val dialogFormHost: DialogFormHost,
    private val currentDir: () -> File,
    private val onFileCreated: (OpenFile) -> Unit
) {

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /** Con varios tipos de archivo soportados, un color elegido a mano por
     *  archivo resulta poco práctico para reconocer tipos a simple vista
     *  (ver colorIndexForExtension). Por eso se elige un TIPO de una grilla
     *  de chips; el tipo autocompleta (pero no fija) la extensión, y el
     *  color de pestaña queda determinado por esa extensión al crear el
     *  archivo (ver OpenFile.fromFile). */
    fun showNewFileDialog() {
        lateinit var titleEdit: EditText
        lateinit var extEdit: EditText

        dialogFormHost.show(
            title = context.getString(R.string.action_new_file),
            contentLayoutRes = R.layout.dialog_new_file,
            bind = { dialogView ->
                val (t, e) = bindNewFileDialogContent(dialogView)
                titleEdit = t
                extEdit = e
                listOf(t, e)
            },
            firstField = { titleEdit },
            onConfirm = {
                val title = titleEdit.text.toString().trim()
                val ext = extEdit.text.toString().trim().removePrefix(".")
                if (title.isEmpty()) {
                    false
                } else {
                    val fullName = if (ext.isNotEmpty()) "$title.$ext" else title
                    createAndOpenFile(fullName)
                }
            }
        )
    }

    /** Configura la grilla de tipos y devuelve (titleEdit, extEdit). */
    private fun bindNewFileDialogContent(dialogView: View): Pair<EditText, EditText> {
        val typeGrid = dialogView.findViewById<GridLayout>(R.id.typeGrid)
        val titleEdit = dialogView.findViewById<EditText>(R.id.editFileTitle)
        val extEdit = dialogView.findViewById<EditText>(R.id.editFileExtension)

        val types = FileType.entries.toList()
        var selectedType: FileType? = null

        val margin = (6 * context.resources.displayMetrics.density).toInt()
        val chips = mutableListOf<TextView>()

        fun applyChipStyle(chip: TextView, selected: Boolean) {
            chip.setBackgroundResource(if (selected) R.drawable.bg_pill_filled else R.drawable.bg_pill_outline)
            chip.setTextColor(
                context.resources.getColor(
                    if (selected) R.color.bg else R.color.text_primary,
                    context.theme
                )
            )
        }

        fun applyColumnsFromMeasuredWidth() {
            val availableWidth = typeGrid.width
            if (availableWidth <= 0) return
            val chipWidths = chips.map { it.measuredWidth + margin * 2 }
            if (chipWidths.any { it <= 0 }) return
            var columns = 1
            var widthSum = chipWidths.getOrElse(0) { 0 }
            for (i in 1 until chipWidths.size) {
                val next = widthSum + chipWidths[i]
                if (next > availableWidth) break
                widthSum = next
                columns++
            }
            columns = columns.coerceAtLeast(1)
            val rows = (chips.size + columns - 1) / columns
            typeGrid.columnCount = columns
            for (i in 0 until typeGrid.childCount) {
                val child = typeGrid.getChildAt(i)
                val lp = child.layoutParams as GridLayout.LayoutParams
                lp.rowSpec = GridLayout.spec(i / columns)
                lp.columnSpec = GridLayout.spec(i % columns)
                child.layoutParams = lp
            }
            typeGrid.rowCount = rows
        }
        typeGrid.columnCount = 1
        typeGrid.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                typeGrid.viewTreeObserver.removeOnGlobalLayoutListener(this)
                applyColumnsFromMeasuredWidth()
            }
        })

        fun selectType(type: FileType) {
            selectedType = type
            for ((idx, chip) in chips.withIndex()) {
                applyChipStyle(chip, types[idx] == type)
            }
            type.fixedExtension?.let { extEdit.setText(it) }
        }

        for (type in types) {
            val chip = TextView(context)
            chip.text = type.label
            chip.textSize = 13f
            chip.setPadding(
                (14 * context.resources.displayMetrics.density).toInt(),
                (6 * context.resources.displayMetrics.density).toInt(),
                (14 * context.resources.displayMetrics.density).toInt(),
                (6 * context.resources.displayMetrics.density).toInt()
            )
            val lp = GridLayout.LayoutParams()
            lp.setMargins(margin, margin, margin, margin)
            chip.layoutParams = lp
            applyChipStyle(chip, type == selectedType)
            chip.setOnClickListener { selectType(type) }
            chips.add(chip)
            typeGrid.addView(chip)
        }

        return Pair(titleEdit, extEdit)
    }

    /** Crea [name] dentro de currentDir() y lo abre en una pestaña nueva —
     *  pero solo si el nombre no está en uso. Devuelve true si se creó y
     *  abrió, false si no (nombre inválido, ya existe, o fallo de IO). */
    fun createAndOpenFile(name: String): Boolean {
        return when (val result = FileRepository.createFileChecked(currentDir(), name)) {
            is FileRepository.CreateFileResult.Success -> {
                onFileCreated(OpenFile.fromFile(result.file))
                true
            }
            is FileRepository.CreateFileResult.InvalidName -> {
                toast(result.reason)
                false
            }
            FileRepository.CreateFileResult.AlreadyExists -> {
                toast("A file with that name already exists")
                false
            }
            FileRepository.CreateFileResult.IoFailure -> {
                toast("Couldn't create the file")
                false
            }
        }
    }
}
