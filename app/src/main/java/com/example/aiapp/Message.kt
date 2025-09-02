package com.example.aiapp

data class Message(
    val text: String = "",
    val sender: String = "",
    val timestamp: Long = System.currentTimeMillis()
)