package `is`.xyz.mpv

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MenuAdapter(private var items: List<MenuRow>) : RecyclerView.Adapter<MenuAdapter.ViewHolder>() {
    data class MenuRow(val title: String, val value: String, val action: () -> Unit)

    private var filteredItems = items

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.menuItemTitle)
        val value: TextView = view.findViewById(R.id.menuItemValue)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.menu_list_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = filteredItems[position]
        holder.title.text = item.title
        holder.value.text = item.value
        holder.itemView.setOnClickListener { item.action() }

        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                holder.itemView.setBackgroundColor(Color.WHITE)
                holder.title.setTextColor(Color.BLACK)
            } else {
                holder.itemView.setBackgroundColor(Color.TRANSPARENT)
                holder.title.setTextColor(Color.WHITE)
            }
        }
    }

    override fun getItemCount() = filteredItems.size

    fun filter(query: String) {
        filteredItems = if (query.isEmpty()) {
            items
        } else {
            items.filter { it.title.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }
}