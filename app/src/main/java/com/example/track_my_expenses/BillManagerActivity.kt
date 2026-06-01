package com.example.track_my_expenses

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Collections

class BillManagerActivity : AppCompatActivity() {

    private lateinit var billAdapter: BillAdapter
    private val billUris = mutableListOf<Uri>()

    private var tempImageUri: Uri? = null

    // -1  → adding a new image
    // ≥0  → replacing the image at that index (recapture)
    private var recapturePosition: Int = -1

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            takePhoto() // Now it's safe to call
        } else {
            Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_LONG).show()
        }
    }

    // ── Camera launcher ──────────────────────────────────────────────────────────
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempImageUri != null) {
            if (recapturePosition >= 0 && recapturePosition < billUris.size) {
                // Replace existing image
                billUris[recapturePosition] = tempImageUri!!
                billAdapter.notifyItemChanged(recapturePosition)
                recapturePosition = -1
            } else {
                // Append new image
                billUris.add(tempImageUri!!)
                // Inserted before the "+" tile; the tile itself shifts right automatically
                billAdapter.notifyItemInserted(billUris.size - 1)
            }
            updateSaveButtonLabel()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun takePhoto(replaceAt: Int = -1) {
        recapturePosition = replaceAt
        val photoFile = File(cacheDir, "bill_${System.currentTimeMillis()}.jpg")
        tempImageUri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile)
        tempImageUri?.let { cameraLauncher.launch(it) }
    }

    private fun updateSaveButtonLabel() {
        val count = billUris.size
        val label = if (count == 0) "Done (no images)" else "Done  ·  $count image${if (count > 1) "s" else ""}"
        findViewById<Button>(R.id.btnSavePdf).text = label
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        enableEdgeToEdge()
        setContentView(R.layout.activity_bill_manager)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Receive any images that were already attached before opening this screen
        val passedUris = intent.getParcelableArrayListExtra<Uri>("image_uris")
        passedUris?.let { billUris.addAll(it) }

        val rv = findViewById<RecyclerView>(R.id.rvBills)

        billAdapter = BillAdapter(
            billUris = billUris,
            onDeleteClick = { position ->
                billUris.removeAt(position)
                billAdapter.notifyItemRemoved(position)
                updateSaveButtonLabel()
            },
            onRecaptureClick = { position ->
                checkCameraPermissionAndLaunch(replaceAt = position)
            },
            onAddClick = {
                checkCameraPermissionAndLaunch()
            }
        )

        rv.adapter = billAdapter
        rv.layoutManager = GridLayoutManager(this, 3)

        // ── Drag-and-drop reorder (image tiles only) ─────────────────────────
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                // Block dragging onto the "+" tile
                if (target is BillAdapter.AddViewHolder) return false
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from < billUris.size && to < billUris.size) {
                    Collections.swap(billUris, from, to)
                    billAdapter.notifyItemMoved(from, to)
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            // Block dragging the "+" tile itself
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                if (viewHolder is BillAdapter.AddViewHolder) return 0
                return super.getMovementFlags(recyclerView, viewHolder)
            }
        })
        itemTouchHelper.attachToRecyclerView(rv)

        // If no images yet, open the camera straight away for a seamless UX
        if (billUris.isEmpty()) {
            checkCameraPermissionAndLaunch()
        }

        updateSaveButtonLabel()

        // ── Done button: return final URI list to TripDetailsActivity ────────
        findViewById<Button>(R.id.btnSavePdf).setOnClickListener {
            val resultIntent = Intent()
            resultIntent.putParcelableArrayListExtra("updated_uris", ArrayList(billUris))
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun checkCameraPermissionAndLaunch(replaceAt: Int = -1) {
        // Store the position in case we are replacing an old image
        recapturePosition = replaceAt

        when {
            // Permission is already granted
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                takePhoto(recapturePosition)
            }
            // Permission is not granted, request it
            else -> {
                requestCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }
    }

}