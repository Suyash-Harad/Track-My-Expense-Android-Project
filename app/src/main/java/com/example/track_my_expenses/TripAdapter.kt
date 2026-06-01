package com.example.track_my_expenses

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.*

class TripAdapter(
    private val onTripClick: (Trip) -> Unit,
    private val onEditClick: (Trip) -> Unit
) : ListAdapter<TripWithSummary, TripAdapter.TripViewHolder>(TripDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TripViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trip, parent, false)
        return TripViewHolder(view)
    }

    override fun onBindViewHolder(holder: TripViewHolder, position: Int) {
        holder.bind(getItem(position), onTripClick, onEditClick)
    }

    class TripViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val clientName = itemView.findViewById<android.widget.TextView>(R.id.tvClientName)
        private val description = itemView.findViewById<android.widget.TextView>(R.id.tvDescription)
        private val tripDates = itemView.findViewById<android.widget.TextView>(R.id.tvTripDates)
        private val totalAmount = itemView.findViewById<android.widget.TextView>(R.id.tvTotalAmount)
        private val statusChip = itemView.findViewById<com.google.android.material.chip.Chip>(R.id.chipStatus)
        private val btnEdit = itemView.findViewById<MaterialButton>(R.id.btnEditTrip)

        fun bind(summary: TripWithSummary, onClick: (Trip) -> Unit, onEdit: (Trip) -> Unit) {
            val trip = summary.trip

            clientName.text = trip.clientName
            description.text = trip.description ?: "No description"

            // Format Amount: ₹0.00
            val amountValue = summary.totalAmount ?: 0.0
            totalAmount.text = "Total: ₹${"%.2f".format(amountValue)}"

            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val start = sdf.format(Date(trip.startDate))
            tripDates.text = if (trip.isActive) "$start - Present" else "$start - ${sdf.format(Date(trip.endDate ?: 0))}"

            // Chip UI
            if (trip.isActive) {
                statusChip.text = "In Progress"
                statusChip.setChipBackgroundColorResource(R.color.warning_light)
                statusChip.setTextColor(itemView.context.getColor(R.color.warning))
            } else {
                statusChip.text = "Completed"
                statusChip.setChipBackgroundColorResource(R.color.success_light)
                statusChip.setTextColor(itemView.context.getColor(R.color.success))
            }

            btnEdit.setOnClickListener { onEdit(trip) }
            itemView.setOnClickListener { onClick(trip) }
        }
    }

    class TripDiffCallback : DiffUtil.ItemCallback<TripWithSummary>() {
        override fun areItemsTheSame(old: TripWithSummary, new: TripWithSummary) = old.trip.id == new.trip.id
        override fun areContentsTheSame(old: TripWithSummary, new: TripWithSummary) = old == new
    }
}