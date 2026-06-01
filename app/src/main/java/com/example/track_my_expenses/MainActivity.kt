package com.example.track_my_expenses

//Learning Git

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.track_my_expenses.databinding.ActivityMainBinding
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private lateinit var tripAdapter: TripAdapter
    private lateinit var binding: ActivityMainBinding

    private var loadingDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        database = AppDatabase.getDatabase(this)
        setupRecyclerView()
        observeTrips()

        binding.fabAddTrip.setOnClickListener {
            showTripDialog() // Opens empty dialog for new trip
        }
    }

    private fun setupRecyclerView() {
        // Now passing two lambdas: one for clicking the item, one for the edit button
        tripAdapter = TripAdapter(
            onTripClick = { trip ->
                // TODO: Open Trip Details/Expenses screen
                val intent = Intent(this, TripDetailsActivity::class.java).apply {
                    putExtra("TRIP_ID", trip.id)
                }
                startActivity(intent)
            },
            onEditClick = { trip ->
                showTripDialog(trip) // Pass existing trip to pre-fill the dialog
            }
        )
        binding.rvTrips.apply {
            adapter = tripAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
    }

    private fun observeTrips() {
        showLoading("Fetching your trips...")

        database.expenseDao().getAllTripsWithSummary().observe(this) { summaries ->
            hideLoading()

            if (summaries.isNullOrEmpty()) {
                binding.emptyState.visibility = View.VISIBLE
                binding.rvTrips.visibility = View.GONE
            } else {
                binding.emptyState.visibility = View.GONE
                binding.rvTrips.visibility = View.VISIBLE
                tripAdapter.submitList(summaries)
            }
        }
    }

    private fun showTripDialog(existingTrip: Trip? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_trip, null)
        val etClientName = dialogView.findViewById<TextInputEditText>(R.id.etClientName)
        val etDescription = dialogView.findViewById<TextInputEditText>(R.id.etDescription)
        val etStartDate = dialogView.findViewById<TextInputEditText>(R.id.etStartDate)
        val tilEndDate = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilEndDate)
        val etEndDate = dialogView.findViewById<TextInputEditText>(R.id.etEndDate)

        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        var currentStart = existingTrip?.startDate ?: System.currentTimeMillis()
        var currentEnd = existingTrip?.endDate

        // UI Logic: Only show End Date if the trip is finished
        if (existingTrip != null && !existingTrip.isActive) {
            tilEndDate.visibility = View.VISIBLE
            etEndDate.setText(currentEnd?.let { sdf.format(Date(it)) })
        } else {
            tilEndDate.visibility = View.GONE
        }

        // Pre-fill existing data
        etClientName.setText(existingTrip?.clientName)
        etDescription.setText(existingTrip?.description)
        etStartDate.setText(sdf.format(Date(currentStart)))

        etStartDate.setOnClickListener {
            showDatePicker("Edit Start Date", currentStart) { selection ->
                currentStart = selection
                etStartDate.setText(sdf.format(Date(selection)))
            }
        }

        etEndDate.setOnClickListener {
            showDatePicker("Edit End Date", currentEnd ?: System.currentTimeMillis()) { selection ->
                currentEnd = selection
                etEndDate.setText(sdf.format(Date(selection)))
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existingTrip == null) "New Trip" else "Edit Trip")
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.window?.let { window ->
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val name = etClientName.text.toString().trim()
                val desc = etDescription.text.toString().trim()

                if (name.isEmpty() || desc.isEmpty()) {
                    // Show standard errors...
                    return@setOnClickListener
                }

                // Run Validation Check
                lifecycleScope.launch {
                    showLoading("Validating dates...")

                    val firstExpenseDate = existingTrip?.let {
                        database.expenseDao().getFirstExpenseDate(it.id)
                    }

                    hideLoading()

                    if (firstExpenseDate != null && currentStart > firstExpenseDate) {
                        // Start date is AFTER the first expense - ERROR!
                        val dateStr = sdf.format(Date(firstExpenseDate))
                        etStartDate.error = "Trip must start on or before first expense ($dateStr)"
                    } else {
                        val updatedTrip = (existingTrip ?: Trip(clientName = "", description = "", startDate = 0)).copy(
                            clientName = name,
                            description = desc,
                            startDate = currentStart,
                            endDate = currentEnd
                        )
                        saveOrUpdateTrip(updatedTrip, existingTrip != null)
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    // Helper function to show Material Date Picker
    private fun showDatePicker(title: String, selection: Long, onDateSelected: (Long) -> Unit) {
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(title)
            .setSelection(selection)
            .build()

        datePicker.addOnPositiveButtonClickListener { onDateSelected(it) }
        datePicker.show(supportFragmentManager, "DATE_PICKER")
    }

    private fun showLoading(message: String) {
        if (loadingDialog == null) {
            loadingDialog = Dialog(this).apply {
                setCancelable(false)
                val view = LayoutInflater.from(this@MainActivity).inflate(R.layout.loading_dialog, null)
                setContentView(view)
                window?.setBackgroundDrawableResource(android.R.color.transparent)
                window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }

        // Update text even if dialog is already showing
        loadingDialog?.findViewById<TextView>(R.id.tvLoadingMessage)?.text = message

        if (loadingDialog?.isShowing == false) {
            loadingDialog?.show()
        }
    }

    private fun hideLoading() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private fun saveOrUpdateTrip(trip: Trip, isEdit: Boolean) {
        // Determine the message based on transaction type
        val message = if (isEdit) "Updating trip details..." else "Creating your trip..."
        showLoading(message)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (isEdit) {
                    database.expenseDao().updateTrip(trip)
                } else {
                    database.expenseDao().insertTrip(trip)
                }

                // Small delay so the user can actually see the success state
                kotlinx.coroutines.delay(400)

                withContext(Dispatchers.Main) {
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideLoading()
                    // You could show a MaterialAlertDialog here for the error
                }
            }
        }
    }

}