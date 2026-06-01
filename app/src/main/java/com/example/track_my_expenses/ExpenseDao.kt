package com.example.track_my_expenses

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface ExpenseDao {
    @Insert
    suspend fun insertTrip(trip: Trip): Long

    @Update
    suspend fun updateTrip(trip: Trip)

    @Query("SELECT MIN(timestamp) FROM expenses WHERE tripId = :tripId")
    suspend fun getFirstExpenseDate(tripId: Long): Long?

    @Query("SELECT * FROM trips ORDER BY isActive DESC, startDate DESC")
    fun getAllTrips(): LiveData<List<Trip>>

    @Query("""
    SELECT trips.*, SUM(expenses.amount) as totalAmount 
    FROM trips 
    LEFT JOIN expenses ON trips.id = expenses.tripId 
    GROUP BY trips.id 
    ORDER BY isActive DESC, startDate DESC
""")
    fun getAllTripsWithSummary(): LiveData<List<TripWithSummary>>

    @Insert
    suspend fun insertExpense(expense: Expense)

    @Query("SELECT * FROM expenses WHERE tripId = :tripId ORDER BY timestamp DESC")
    fun getExpensesForTrip(tripId: Long): LiveData<List<Expense>>

    @Query("UPDATE trips SET isActive = 0, endDate = :endTimestamp WHERE id = :tripId")
    suspend fun endTrip(tripId: Long, endTimestamp: Long)

    @Query("SELECT * FROM trips WHERE id = :tripId")
    fun getTripById(tripId: Long): LiveData<Trip>

    @Delete
    suspend fun deleteExpense(expense: Expense)

    @Query("SELECT SUM(amount) FROM expenses WHERE tripId = :tripId")
    fun getTotalExpensesForTrip(tripId: Long): LiveData<Double?>

    @Update
    suspend fun updateExpense(expense: Expense)
}