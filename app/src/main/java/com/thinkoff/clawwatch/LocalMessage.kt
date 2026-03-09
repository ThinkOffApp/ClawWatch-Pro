package com.thinkoff.clawwatch

data class LocalMessage(
    val author: String,
    val body: String,
    val timestamp: String,
    val isUser: Boolean
)
