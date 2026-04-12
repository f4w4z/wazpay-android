package com.zeny.wazpay

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val recipient: String,
    val amount: String,
    val refId: String?,
    val status: String, // "SUCCESS", "FAILED", "PENDING"
    val timestamp: Long = System.currentTimeMillis()
)
