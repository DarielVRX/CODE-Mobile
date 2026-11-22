package com.codemobile.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.codemobile.R
import com.codemobile.files.OpenFile

private val PALETTE = intArrayOf(
    R.color.tab_01, R.color.tab_02, R.color.tab_03, R.color.tab_04,
    R.color.tab_05, R.color.tab_06, R.color.tab_07, R.color.tab_08,
    R.color.tab_09, R.color.tab_10, R.color.tab_11, R.color.tab_12,
    R.color.tab_13, R.color.tab_14, R.color.tab_15, R.color.tab_16
)

fun paletteColorRes(index: Int): Int = PALETTE[index.coerceIn(0, PALETTE.size - 1)]
fun paletteSize(): Int = PALETTE.size

// FileType y colorIndexForExtension viven en com.codemobile.files.OpenFile.kt
// (junto al modelo de datos, que es quien más las necesita) — se re-exportan
// acá solo por compatibilidad de import en este archivo, ver el import de
// com.codemobile.files.colorIndexForExtension más abajo si hiciera falta.

/**
 * Lista de pestañas abiertas. La activa se ensancha y muestra el nombre
 * horizontal; las demás quedan angostas mostrando el mismo nombre, pero
 * rotado 270° (de abajo hacia arriba) para entrar en el ancho angosto. El
 * color de fondo (paletteColorRes, fijo por extensión) es lo que identifica
 * el tipo de archivo — ninguna pestaña muestra la extensión como texto.
 */
class TabBarAdapter(
    private val files: MutableList<OpenFile>,
    private val onTabClick: (Int) -> Unit,
    private val onAddClick: () -> Unit,
    // Botón de cerrar, solo visible en la pestaña activa (ver item_tab.xml
    // y onBindViewHolder) — llamado con la posición de la pestaña a cerrar.
    private val onTabClose: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var activeIndex: Int = 0

    private val TYPE_TAB = 0
    private val TYPE_ADD = 1

    fun setActiveIndex(index: Int) {
        activeIndex = index
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = files.size + 1

    override fun getItemViewType(position: Int): Int =
        if (position == files.size) TYPE_ADD else TYPE_TAB

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_ADD) {
            val tv = TextView(parent.context)
            val size = (38 * parent.resources.displayMetrics.density).toInt()
            tv.layoutParams = LinearLayout.LayoutParams(size, size)
            tv.text = "+"
            tv.textSize = 16f
            tv.gravity = android.view.Gravity.CENTER
            tv.setTextColor(parent.resources.getColor(R.color.text_dim, parent.context.theme))
            AddViewHolder(tv)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
            TabViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AddViewHolder) {
            holder.view.setOnClickListener { onAddClick() }
            return
        }
        val tabHolder = holder as TabViewHolder
        val file = files[position]
        val context = tabHolder.root.context
        val isActive = position == activeIndex

        val bg = tabHolder.root.background.mutate() as GradientDrawable
        bg.setColor(context.resources.getColor(paletteColorRes(file.colorIndex), context.theme))

        // Ya no se muestra la extensión como texto (el color de fondo ya la
        // identifica, ver colorIndexForExtension): ambas vistas de nombre
        // muestran displayName, solo cambia la orientación/visibilidad según
        // isActive. tabNameVertical (inactiva) va rotado 270° (de abajo
        // hacia arriba) para entrar en el ancho angosto de la pestaña sin
        // ensancharla — mismo criterio de rotación que un lomo de libro o
        // una pestaña de carpeta física.
        tabHolder.nameVertical.text = file.displayName
        tabHolder.nameVertical.rotation = -90f
        tabHolder.nameVertical.visibility = if (isActive) View.GONE else View.VISIBLE
        tabHolder.name.text = file.displayName
        tabHolder.name.visibility = if (isActive) View.VISIBLE else View.GONE
        tabHolder.close.visibility = if (isActive) View.VISIBLE else View.GONE
        tabHolder.root.alpha = if (isActive) 1f else 0.55f

        tabHolder.root.setOnClickListener { onTabClick(position) }
        // bindingAdapterPosition (no `position`, capturado por valor en este
        // lambda) porque position puede quedar desactualizado si la lista
        // cambia entre el bind y el click real (p.ej. se cerró otra pestaña
        // antes de tocar esta) — mismo motivo por el que RecyclerView expone
        // ese getter en vez de recomendar capturar la posición del bind.
        tabHolder.close.setOnClickListener {
            val pos = tabHolder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onTabClose(pos)
        }
    }

    class TabViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: LinearLayout = view.findViewById(R.id.tabRoot)
        val nameVertical: TextView = view.findViewById(R.id.tabNameVertical)
        val name: TextView = view.findViewById(R.id.tabName)
        val close: TextView = view.findViewById(R.id.tabClose)
    }

    class AddViewHolder(val view: View) : RecyclerView.ViewHolder(view)
}
