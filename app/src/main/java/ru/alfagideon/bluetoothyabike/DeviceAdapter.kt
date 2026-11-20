package ru.alfagideon.bluetoothyabike

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** Адаптер списка устройств. Тап по карточке — подключение. */
class DeviceAdapter(
    private val onClick: (ScanDevice) -> Unit,
    private val onLongClick: (ScanDevice) -> Unit = {},
) : RecyclerView.Adapter<DeviceAdapter.Holder>() {

    private val items = mutableListOf<ScanDevice>()

    fun submit(device: ScanDevice) {
        val index = items.indexOfFirst { it.address == device.address }
        if (index >= 0) {
            items[index] = device
            notifyItemChanged(index)
        } else {
            items.add(device)
            notifyItemInserted(items.size - 1)
            items.sortByDescending { it.rssi }
            notifyDataSetChanged()
        }
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun devices(): List<ScanDevice> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return Holder(view, onClick, onLongClick)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    class Holder(
        view: View,
        private val onClick: (ScanDevice) -> Unit,
        private val onLongClick: (ScanDevice) -> Unit,
    ) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.tvName)
        private val meta: TextView = view.findViewById(R.id.tvMeta)
        private val rssi: TextView = view.findViewById(R.id.tvRssi)

        fun bind(device: ScanDevice) {
            name.text = device.name ?: "(без имени)"
            rssi.text = "${device.rssi} dBm"
            val countdown = (device.serviceData.size + device.manufacturer.size + device.services.size)
            meta.text = buildString {
                append(device.address)
                if (!device.connectable) append(" · не для подключения")
                if (countdown > 0) append(" · adv×$countdown")
            }
            val color = if (device.name != null) R.color.text_primary else R.color.text_secondary
            name.setTextColor(ContextCompat.getColor(itemView.context, color))
            itemView.setOnClickListener { onClick(device) }
            itemView.setOnLongClickListener {
                onLongClick(device)
                true
            }
        }
    }
}
