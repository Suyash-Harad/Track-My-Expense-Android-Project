package com.example.track_my_expenses

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.track_my_expenses.databinding.ActivityTripDetailsBinding
import com.example.track_my_expenses.databinding.DialogAddExpenseBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TripDetailsActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private lateinit var binding: ActivityTripDetailsBinding
    private lateinit var expenseAdapter: ExpenseAdapter

    private var tripId: Long = 0
    private var loadingDialog: Dialog? = null
    private var activeDialogBinding: DialogAddExpenseBinding? = null

    // Holds URIs returned from BillManagerActivity
    private val capturedImageUris = mutableListOf<Uri>()

    // ── QR Scanner ──────────────────────────────────────────────────────────────
    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data         = result.data ?: return@registerForActivityResult
            val rawUri       = data.getStringExtra("upi_uri") ?: return@registerForActivityResult
            // resolvedAmount = QR amount if it had one, otherwise the user's own typed amount
            // This is what was actually sent to GPay, so we show the same value in the dialog.
            val resolvedAmount = data.getStringExtra("resolved_amount") ?: ""

            activeDialogBinding?.let { binding ->
                val uri = Uri.parse(rawUri)
                binding.etAmount.post {
                    // Use resolvedAmount so the field always reflects what was paid
                    if (resolvedAmount.isNotBlank()) {
                        binding.etAmount.setText(resolvedAmount)
                    }
                    // Payee name from QR
                    val payeeName = uri.getQueryParameter("pn") ?: ""
                    if (payeeName.isNotBlank()) {
                        binding.etExpenseDesc.setText(payeeName)
                    }
                    binding.togglePaymentGroup.check(R.id.btnOnline)
                    binding.etAmount.clearFocus()
                }
            }
        }
    }

    // ── Bill Manager launcher ────────────────────────────────────────────────────
    // All image capture/management is done inside BillManagerActivity.
    // We only receive the final list of URIs back here.
    private val billManagerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val updatedUris = result.data?.getParcelableArrayListExtra<Uri>("updated_uris")
            updatedUris?.let {
                capturedImageUris.clear()
                capturedImageUris.addAll(it)
                val count = capturedImageUris.size
                activeDialogBinding?.btnAttachBills?.text =
                    if (count == 0) "Attach Bills" else "Bills Attached ($count)"
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        enableEdgeToEdge()

        binding = ActivityTripDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        database = AppDatabase.getDatabase(this)
        tripId = intent.getLongExtra("TRIP_ID", -1)

        if (tripId == -1L) {
            finish()
            return
        }

        setupToolbar()
        setupRecyclerView()
        observeTripData()

        binding.fabAddExpense.setOnClickListener {
            showAddExpenseDialog()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun observeTripData() {
        showLoading("Loading expenses...")
        database.expenseDao().getTripById(tripId).observe(this) { trip ->
            trip?.let {
                binding.tvHeaderClientName.text = it.clientName
                binding.tvHeaderDescription.text = it.description
                if (!it.isActive) {
                    binding.fabAddExpense.visibility = View.GONE
                }
            }
        }

        database.expenseDao().getExpensesForTrip(tripId).observe(this) { expenses ->
            hideLoading()
            expenseAdapter.submitList(expenses)

            val isEmpty = expenses.isNullOrEmpty()
            binding.rvExpenses.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.tvEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE

            val total = expenses?.sumOf { it.amount } ?: 0.0
            binding.tvTotalTripAmount.text = String.format("₹%.2f", total)
        }
    }

    private fun setupRecyclerView() {
        expenseAdapter = ExpenseAdapter(onEditClick = { expense ->
            showAddExpenseDialog(expense)
        })
        binding.rvExpenses.apply {
            adapter = expenseAdapter
            layoutManager = LinearLayoutManager(this@TripDetailsActivity)
        }
    }

    private fun launchQRScanner(dialogBinding: DialogAddExpenseBinding) {
        activeDialogBinding = dialogBinding
        // Validate: warn if amount field is empty, but still allow scanning
        // (some QR codes already have the amount baked in)
        val currentAmount = dialogBinding.etAmount.text.toString().trim()
        if (currentAmount.isBlank()) {
            android.widget.Toast.makeText(
                this,
                "Tip: Enter the amount first so it gets sent directly to the payment app",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        val intent = Intent(this, CustomScannerActivity::class.java).apply {
            // Pass whatever the user has typed (blank is fine — scanner handles it)
            putExtra("user_amount", currentAmount)
        }
        scanLauncher.launch(intent)
    }

    private fun showAddExpenseDialog(existingExpense: Expense? = null) {
        val dialogBinding = DialogAddExpenseBinding.inflate(layoutInflater)
        activeDialogBinding = dialogBinding

        // Reset captured URIs when opening a fresh dialog
        if (existingExpense == null) capturedImageUris.clear()

        // Pre-fill if editing
        existingExpense?.let {
            dialogBinding.etAmount.setText(it.amount.toString())
            dialogBinding.etExpenseDesc.setText(it.description)
            dialogBinding.etPeopleInvolved.setText(it.peopleInvolved)
            dialogBinding.actvCategory.setText(it.category, false)
            if (it.paymentType == "ONLINE") {
                dialogBinding.togglePaymentGroup.check(R.id.btnOnline)
                dialogBinding.btnScanAndPay.visibility = View.GONE
            } else {
                dialogBinding.togglePaymentGroup.check(R.id.btnCash)
            }
        }

        // Category dropdown
        val categories = arrayOf("Food", "Transport", "Stay", "Fuel", "Shopping", "Misc")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, categories)
        dialogBinding.actvCategory.setAdapter(adapter)

        // Payment toggle
        dialogBinding.togglePaymentGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                dialogBinding.btnScanAndPay.visibility =
                    if (checkedId == R.id.btnOnline) View.VISIBLE else View.GONE
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existingExpense == null) "Add Expense" else "Edit Expense")
            .setView(dialogBinding.root)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnDismissListener { activeDialogBinding = null }

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )

            // ── Attach Bills button: opens BillManagerActivity ───────────────
            dialogBinding.btnAttachBills.setOnClickListener {
                val intent = Intent(this, BillManagerActivity::class.java)
                intent.putParcelableArrayListExtra("image_uris", ArrayList(capturedImageUris))
                billManagerLauncher.launch(intent)
            }

            // ── Scan & Pay ───────────────────────────────────────────────────
            dialogBinding.btnScanAndPay.setOnClickListener {
                launchQRScanner(dialogBinding)
            }

            // ── Save ─────────────────────────────────────────────────────────
            saveButton.setOnClickListener {
                val amountStr = dialogBinding.etAmount.text.toString()
                val description = dialogBinding.etExpenseDesc.text.toString()
                val category = dialogBinding.actvCategory.text.toString()
                val people = dialogBinding.etPeopleInvolved.text.toString().trim()
                val paymentType =
                    if (dialogBinding.togglePaymentGroup.checkedButtonId == R.id.btnOnline) "ONLINE" else "CASH"

                if (amountStr.isEmpty() || description.isEmpty() || category.isEmpty()) {
                    if (amountStr.isEmpty()) dialogBinding.etAmount.error = "Required"
                    if (description.isEmpty()) dialogBinding.etExpenseDesc.error = "Required"
                    if (category.isEmpty()) {
                        Toast.makeText(this, "Please select a category", Toast.LENGTH_SHORT).show()
                    }
                    return@setOnClickListener
                }

                val expenseToSave = if (existingExpense == null) {
                    Expense(
                        tripId = tripId,
                        amount = amountStr.toDouble(),
                        category = category,
                        description = description,
                        peopleInvolved = people,
                        paymentType = paymentType
                    )
                } else {
                    existingExpense.copy(
                        amount = amountStr.toDouble(),
                        category = category,
                        description = description,
                        peopleInvolved = people,
                        paymentType = paymentType
                    )
                }

                // Inside showAddExpenseDialog -> saveButton.setOnClickListener
                if (capturedImageUris.isNotEmpty()) {
                    val tripName = binding.tvHeaderClientName.text.toString()
                    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault()).format(java.util.Date())

                    // Default format: Date_Category_Amount
                    val defaultFileName = "${timestamp}_${category}_${amountStr}"

                    // Instead of calling generatePdf directly, start the naming flow:
                    showFileNameDialog(capturedImageUris.toList(), tripName, defaultFileName)

                    // Clear the list after passing it to the dialog flow
                    capturedImageUris.clear()
                }

                saveOrUpdateExpense(expenseToSave, isEdit = existingExpense != null)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveOrUpdateExpense(expense: Expense, isEdit: Boolean) {
        showLoading(if (isEdit) "Updating..." else "Saving...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (isEdit) database.expenseDao().updateExpense(expense)
                else database.expenseDao().insertExpense(expense)
                withContext(Dispatchers.Main) { hideLoading() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { hideLoading() }
            }
        }
    }

    private fun generatePdf(uris: List<Uri>, tripName: String, fileName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val pdfDocument = PdfDocument()
            var hasContent = false

            // 1. PDF Generation Loop
            for ((index, uri) in uris.withIndex()) {
                var currentPage: PdfDocument.Page? = null
                try {
                    val source = ImageDecoder.createSource(contentResolver, uri)
                    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setMutableRequired(true)
                    }

                    val pageWidth = 595
                    val pageHeight = 842
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()

                    currentPage = pdfDocument.startPage(pageInfo)
                    val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, pageWidth, pageHeight, true)
                    currentPage.canvas.drawBitmap(scaledBitmap, 0f, 0f, null)

                    pdfDocument.finishPage(currentPage)
                    currentPage = null
                    scaledBitmap.recycle()
                    bitmap.recycle()
                    hasContent = true
                } catch (e: Exception) {
                    Log.e("PDF", "Failed to process image: ${e.message}")
                    currentPage?.let { pdfDocument.finishPage(it) }
                }
            }

            // 2. Save to Public Downloads using MediaStore
            if (hasContent) {
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.pdf")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/TrackMyExpenses/${tripId}-${tripName}")
                }

                val uri = contentResolver.insert(android.provider.MediaStore.Files.getContentUri("external"), contentValues)

                try {
                    uri?.let {
                        contentResolver.openOutputStream(it)?.use { outputStream ->
                            pdfDocument.writeTo(outputStream)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@TripDetailsActivity, "File saved successfully!", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PDF", "Failed to save: ${e.message}")
                } finally {
                    pdfDocument.close()
                }
            }
        }
    }

    private fun showFileNameDialog(uris: List<Uri>, tripName: String, defaultName: String) {
        val input = com.google.android.material.textfield.TextInputEditText(this)
        input.setText(defaultName)

        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(60, 20, 60, 0)
        input.layoutParams = params
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("Save PDF as")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val finalName = input.text.toString().trim()
                if (finalName.isNotEmpty()) {
                    checkAndGeneratePdf(uris, tripName, finalName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAndGeneratePdf(uris: List<Uri>, tripName: String, fileName: String) {
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val folder = java.io.File(baseDir, "TrackMyExpenses/$tripName/Expenses")
        if (!folder.exists()) folder.mkdirs()

        val file = java.io.File(folder, "$fileName.pdf")

        if (file.exists()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("File Exists")
                .setMessage("A file named '$fileName.pdf' already exists. Do you want to overwrite it?")
                .setPositiveButton("Overwrite") { _, _ ->
                    generatePdf(uris, tripName, fileName)
                }
                .setNegativeButton("Rename") { _, _ ->
                    showFileNameDialog(uris, tripName, fileName) // Re-open naming dialog
                }
                .show()
        } else {
            generatePdf(uris, tripName, fileName)
        }
    }

    private fun showLoading(message: String) {
        if (loadingDialog == null) {
            loadingDialog = Dialog(this).apply {
                setCancelable(false)
                val view = LayoutInflater.from(this@TripDetailsActivity).inflate(R.layout.loading_dialog, null)
                setContentView(view)
                window?.setBackgroundDrawableResource(android.R.color.transparent)
                window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
        loadingDialog?.findViewById<TextView>(R.id.tvLoadingMessage)?.text = message
        if (loadingDialog?.isShowing == false) loadingDialog?.show()
    }

    private fun hideLoading() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        window.decorView.clearFocus()
        hideKeyboard()
    }
}