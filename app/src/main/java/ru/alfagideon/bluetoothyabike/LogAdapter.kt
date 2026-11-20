package ru.alfagideon.bluetoothyabike

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** Журнал: строки моноширинным шрифтом, цвет зависит от типа записи. */
class LogAdapter : RecyclerView.Adapter<LogAdapter.Holder>() {

    private val items = mutableListOf<LogEntry>()

    fun append(entry: LogEntry) {
        items.add(entry)
        notifyItemInserted(items.size - 1)
        if (items.size > MAX_LINES) {
            val removed = items.size - MAX_LINES
            repeat(removed) { items.removeAt(0) }
            notifyItemRangeRemoved(0, removed)
        }
    }

    fun clear() {
        val size = items.size
        items.clear()
        if (size > 0) notifyItemRangeRemoved(0, size)
    }

    fun entries(): List<LogEntry> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return Holder(view as TextView)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    class Holder(private val text: TextView) : RecyclerView.ViewHolder(text) {
        fun bind(entry: LogEntry) {
            text.text = entry.format()
            text.setTextColor(ContextCompat.getColor(itemView.context, entry.kind.colorRes))
        }
    }

    companion object {
        private const val MAX_LINES = 4000
    }
}
