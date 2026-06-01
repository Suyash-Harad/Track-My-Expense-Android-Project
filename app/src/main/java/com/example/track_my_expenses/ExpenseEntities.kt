package com.example.track_my_expenses

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientName: String,
    val description: String?,
    val startDate: Long,
    var endDate: Long? = null,
    var isActive: Boolean = true
)

data class TripWithSummary(
    @Embedded val trip: Trip,
    val totalAmount: Double? = 0.0
)

@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tripId")]
)
data class Expense(
    @PrimaryKey(autoGenerate = true) val expenseId: Long = 0,
    val tripId: Long,
    val amount: Double,
    val category: String,
    val description: String,
    val peopleInvolved: String,
    val paymentType: String, // "CASH" or "ONLINE"
    var receiptStatus: String = "N",
    val receiptPath: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)