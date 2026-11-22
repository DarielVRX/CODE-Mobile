package com.codemobile.ui

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.codemobile.R
import com.codemobile.files.FileRepository
import java.io.File

/** Un nodo visible del árbol aplanado (ver ExplorerController.
 *  buildVisibleNodes): [file] más su [depth] (0 = nivel raíz listado,
 *  1 = hijo de una carpeta expandida, etc.) para la indentación visual en
 *  item_file.xml. El árbol en sí (qué carpetas están expandidas, en qué
 *  orden aparecen) lo decide el controller — el adapter solo pinta la
 *  lista ya aplanada que le llega, igual que antes pintaba una lista de
 *  un solo nivel. */
data class ExplorerNode(val file: File, val depth: Int)

class FileExplorerAdapter(
    private var items: List<ExplorerNode>,
    private val onItemClick: (File) -> Unit,
    // Long-press: activa/alterna selección múltiple sobre el
    // ítem. Las acciones sobre la selección (renombrar/eliminar/copiar/
    // mover) viven en una sola barra contextual en EditorActivity, no por
    // fila — ver EditorActivity.updateSelectionUi.
    private val onItemLongClick: ((File) -> Unit)? = null,
    // Ancho visible real del panel (el viewport del HorizontalScrollView,
    // ver activity_editor.xml — NO el ancho del propio explorerRecycler,
    // que es wrap_content y crece libremente dentro de ese scroll). Se
    // consulta en cada bind (en vez de una sola vez) porque el panel es
    // redimensionable (ver ExplorerController.setupExplorerResizeHandle):
    // un valor cacheado quedaría desactualizado tras arrastrar el handle.
    // Lambda en vez de una referencia directa a la vista, para no atar
    // este adapter a un tipo de vista concreto.
    private val panelWidthPx: () -> Int = { 0 }
) : RecyclerView.Adapter<FileExplorerAdapter.ViewHolder>() {

    /** Conjunto de archivos seleccionados (modo multi-selección). Vacío =
     *  no hay modo selección activo; un tap simple entonces abre el ítem
     *  normalmente. No vacío = un tap simple suma/quita de la selección en
     *  vez de abrir (ver EditorActivity.onExplorerItemTapped). */
    private var selected: Set<File> = emptySet()

    /** Paso 13: carpeta "activa" del árbol — la que usan crear archivo/
     *  comandos git/búsqueda recursiva (ver ExplorerController.
     *  selectedDir). Distinta de [selected] (multi-selección para
     *  renombrar/eliminar/mover/copiar): puede haber como mucho UNA
     *  carpeta activa a la vez, y es independiente de cuáles carpetas
     *  están expandidas (ver doc de ExplorerController sobre qué pasa al
     *  contraer la carpeta activa — no cambia). Prioridad de color en
     *  onBindViewHolder: multi-selección > activa > repo git > transparente. */
    private var selectedDir: File? = null

    fun setSelection(newSelection: Set<File>) {
        selected = newSelection
        notifyDataSetChanged()
    }

    fun setSelectedDir(newSelectedDir: File?) {
        selectedDir = newSelectedDir
        notifyDataSetChanged()
    }

    fun update(newItems: List<ExplorerNode>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val node = items[position]
        val file = node.file
        holder.icon.text = if (file.isDirectory) "📁" else "📄"
        // Indentación por profundidad del árbol (ver doc de ExplorerNode):
        // cada nivel suma el mismo margen fijo al padding base del layout,
        // en vez de un layout_width distinto por nivel.
        val indentPx = holder.indentStepPx * node.depth
        holder.itemView.setPaddingRelative(
            holder.basePaddingStart + indentPx,
            holder.itemView.paddingTop,
            holder.itemView.paddingEnd,
            holder.itemView.paddingBottom
        )
        holder.name.text = displayName(file.name, holder, indentPx)
        // Área tocable hasta el borde derecho del panel (pedido: antes, con
        // itemView wrap_content — ver doc de clase de ExplorerNode/el XML —
        // la fila terminaba justo donde terminaba el texto, dejando una
        // franja no tocable hasta el borde). minWidth no afecta el ancho
        // REAL de la fila cuando esta ya es más ancha que el panel (nombre
        // largo sin truncar o indentación profunda) — minimumWidth solo
        // impone un piso, nunca un techo, así que el scroll horizontal
        // sigue funcionando igual en esos casos.
        holder.itemView.minimumWidth = panelWidthPx()
        // Ícono chico de "repo git" a la derecha de fileIcon (ver doc del
        // layout): solo para una carpeta que es raíz de repo.
        val isRepoRoot = FileRepository.isGitRepoRoot(file)
        holder.gitIcon.visibility = if (isRepoRoot) View.VISIBLE else View.GONE
        // El ripple táctil vive en android:foreground (ver item_file.xml),
        // así que background queda libre para el highlight de selección sin
        // perder el feedback táctil normal al tocar un ítem no seleccionado.
        // Prioridad: multi-selección > carpeta activa (ver doc de
        // selectedDir) > marca de repo git > transparente.
        holder.itemView.setBackgroundColor(
            when {
                selected.contains(file) ->
                    ContextCompat.getColor(holder.itemView.context, R.color.accent_selected)
                selectedDir != null && file == selectedDir ->
                    ContextCompat.getColor(holder.itemView.context, R.color.accent_active_dir)
                FileRepository.isGitRepoRoot(file) ->
                    ContextCompat.getColor(holder.itemView.context, R.color.git_repo_root)
                else -> android.graphics.Color.TRANSPARENT
            }
        )
        // Diseño propio para ocultos (ver FileRepository.list/isHiddenEntry:
        // ahora se listan siempre, antes se filtraban por completo).
        // Independiente del color de fondo de
        // arriba (selección/git siguen igual encima) — esto solo afecta el
        // ícono y el texto, para que un archivo/carpeta oculto se distinga
        // de un vistazo sin usar una fila o badge aparte: texto en
        // text_dim (en vez de text_primary) + cursiva, e ícono más
        // translúcido (alpha reducido en vez de un ícono distinto — el
        // mismo 📁/📄 ya identifica archivo vs carpeta, no hace falta un
        // tercer glifo solo para "oculto").
        val hidden = FileRepository.isHiddenEntry(file)
        holder.name.setTextColor(
            ContextCompat.getColor(
                holder.itemView.context,
                if (hidden) R.color.text_dim else R.color.text_primary
            )
        )
        holder.name.setTypeface(holder.name.typeface, if (hidden) android.graphics.Typeface.ITALIC else android.graphics.Typeface.NORMAL)
        holder.icon.alpha = if (hidden) 0.55f else 1f
        holder.itemView.setOnClickListener { onItemClick(file) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick?.invoke(file)
            // true: consume el evento para que no dispare también el click
            // normal inmediatamente después (comportamiento estándar de
            // long-press en Android).
            onItemLongClick != null
        }
    }

    override fun getItemCount(): Int = items.size

    /** Decide qué mostrar para [name] en una fila con indentación [indentPx]
     *  (reconsiderado: ya no se trunca siempre por una cantidad fija de
     *  caracteres). Dos casos:
     *
     *  1) El nombre completo ENTRA en el ancho visible del panel (medido de
     *     verdad con el Paint del propio TextView, no una aproximación por
     *     cantidad de caracteres) — se muestra tal cual, sin truncar. Esto
     *     es lo que hace que un nombre corto llene la fila hasta donde
     *     corresponda sin disparar scroll horizontal por sí solo: solo la
     *     indentación acumulada de ramas muy anidadas sigue pudiendo
     *     exceder el panel (ver HorizontalScrollView en activity_editor.xml).
     *  2) El nombre NO entra en ese ancho — en vez de truncar justo en el
     *     borde (lo que impediría ver el nombre completo aunque se hiciera
     *     scroll), se permite que la fila se extienda más allá del panel
     *     (disparando el scroll horizontal) hasta un límite generoso de
     *     MAX_CHARS_WHEN_TRUNCATED caracteres — da margen para leer/scrollear
     *     nombres moderadamente largos, y solo trunca con "…" los
     *     verdaderamente extremos (archivos generados por algún proceso
     *     automatizado, etc.).
     *
     *  panelWidthPx() <= 0 (no medido todavía, ver constructor) se trata
     *  como "no entra": cae al caso 2, igual que antes de este cambio. */
    private fun displayName(name: String, holder: ViewHolder, indentPx: Int): String {
        val availablePx = panelWidthPx() - indentPx - holder.chromeWidthPx
        val fitsPanel = availablePx > 0 &&
            holder.name.paint.measureText(name) <= availablePx
        if (fitsPanel) return name
        return if (name.length > MAX_CHARS_WHEN_TRUNCATED) {
            name.take(MAX_CHARS_WHEN_TRUNCATED - 1) + "…"
        } else {
            name
        }
    }

    companion object {
        private const val MAX_CHARS_WHEN_TRUNCATED = 40
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.fileIcon)
        val gitIcon: ImageView = view.findViewById(R.id.gitRepoIcon)
        val name: TextView = view.findViewById(R.id.fileName)

        // Paso 13: indentación por profundidad (ver onBindViewHolder).
        // basePaddingStart se lee UNA vez del paddingStart original del XML
        // (16dp, ver item_file.xml) en vez de hardcodearlo acá — si el XML
        // cambia ese valor, este código sigue sumándole el indent sin
        // desincronizarse. indentStepPx: 12dp por nivel, lo bastante para
        // distinguir profundidad sin desperdiciar ancho en un panel que ya
        // es angosto.
        val basePaddingStart: Int = view.paddingStart
        val indentStepPx: Int = (12 * view.resources.displayMetrics.density).toInt()

        /** Ancho fijo de todo lo que NO es el texto del nombre dentro de la
         *  fila (ver item_file.xml): paddingStart base + fileIcon (28dp) +
         *  marginStart de fileName (10dp) + paddingEnd (16dp). No incluye
         *  gitRepoIcon (16dp + 2dp margin, solo visible para raíces de repo)
         *  a propósito: para esas pocas filas el ancho disponible calculado
         *  queda un poco más generoso de lo real, un margen de error
         *  aceptable antes que complicar este cálculo con un chequeo extra
         *  por fila. */
        val chromeWidthPx: Int = basePaddingStart +
            (28 * view.resources.displayMetrics.density).toInt() +
            (10 * view.resources.displayMetrics.density).toInt() +
            view.paddingEnd
    }
}
