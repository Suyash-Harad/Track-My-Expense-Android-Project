package com.example.track_my_expenses

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class BillAdapter(
    private val billUris: MutableList<Uri>,
    private val onDeleteClick: (Int) -> Unit,
    private val onRecaptureClick: (Int) -> Unit,
    private val onAddClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_IMAGE = 0
        const val TYPE_ADD   = 1
    }

    // ── View holders ─────────────────────────────────────────────────────────────

    class BillViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.ivBillThumbnail)
        val btnDelete: ImageView = view.findViewById(R.id.btnDelete)
    }

    /** Holds the "+" add-more tile. Declared as inner so Activity can reference the type. */
    class AddViewHolder(view: View) : RecyclerView.ViewHolder(view)

    // ── RecyclerView.Adapter overrides ────────────────────────────────────────────

    override fun getItemViewType(position: Int): Int =
        if (position == billUris.size) TYPE_ADD else TYPE_IMAGE

    /** One extra item at the end for the "+" tile. */
    override fun getItemCount(): Int = billUris.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_ADD) {
            AddViewHolder(inflater.inflate(R.layout.item_bill_add, parent, false))
        } else {
            BillViewHolder(inflater.inflate(R.layout.item_bill, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AddViewHolder) {
            holder.itemView.setOnClickListener { onAddClick() }
            return
        }

        holder as BillViewHolder
        val uri = billUris[position]

        Glide.with(holder.imageView.context)
            .load(uri)
            .centerCrop()
            .into(holder.imageView)

        // Quick-delete via the X button
        holder.btnDelete.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_ID.toInt()) onDeleteClick(pos)
        }

        // Long-press → Recapture or Delete dialog
        holder.itemView.setOnLongClickListener {
            val pos = holder.adapterPosition
            if (pos == RecyclerView.NO_ID.toInt()) return@setOnLongClickListener false

            AlertDialog.Builder(holder.itemView.context)
                .setTitle("Image Options")
                .setItems(arrayOf("📷  Recapture", "🗑  Delete")) { _, which ->
                    when (which) {
                        0 -> onRecaptureClick(pos)
                        1 -> onDeleteClick(pos)
                    }
                }
                .show()
            true
        }
    }
}