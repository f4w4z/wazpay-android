package com.zeny.wazpay

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(transaction: Transaction)

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAll(): kotlinx.coroutines.flow.Flow<List<Transaction>>

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}
