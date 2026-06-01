package com.example.track_my_expenses

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class ExpenseAdapter(
    private val onEditClick: (Expense) -> Unit
) : ListAdapter<Expense, ExpenseAdapter.ExpenseViewHolder>(ExpenseDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExpenseViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_expense, parent, false)
        // Pass the lambda into the ViewHolder here
        return ExpenseViewHolder(view, onEditClick)
    }

    override fun onBindViewHolder(holder: ExpenseViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ExpenseViewHolder(
        itemView: View,
        private val onEditClick: (Expense) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvDescription: TextView = itemView.findViewById(R.id.tvExpenseDescription)
        private val tvCategory: TextView = itemView.findViewById(R.id.tvExpenseCategory)
        private val tvAmount: TextView = itemView.findViewById(R.id.tvExpenseAmount)
        private val tvPaymentType: TextView = itemView.findViewById(R.id.tvPaymentType)
        private val btnEdit: ImageButton = itemView.findViewById(R.id.btnEditExpense) // New button

        fun bind(expense: Expense) {
            tvDescription.text = expense.description

            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val dateStr = sdf.format(Date(expense.timestamp))
            tvCategory.text = "${expense.category} • $dateStr"

            tvAmount.text = String.format("₹%.2f", expense.amount)
            tvPaymentType.text = expense.paymentType

            // Handle edit button click
            btnEdit.setOnClickListener { onEditClick(expense) }

            // Change color based on Payment Type
            if (expense.paymentType == "ONLINE") {
                tvPaymentType.setTextColor(ContextCompat.getColor(itemView.context, R.color.color_primary))
            } else {
                tvPaymentType.setTextColor(ContextCompat.getColor(itemView.context, R.color.warning))
            }
        }
    }

    class ExpenseDiffCallback : DiffUtil.ItemCallback<Expense>() {
        override fun areItemsTheSame(oldItem: Expense, newItem: Expense): Boolean =
            oldItem.expenseId == newItem.expenseId

        override fun areContentsTheSame(oldItem: Expense, newItem: Expense): Boolean =
            oldItem == newItem
    }
}