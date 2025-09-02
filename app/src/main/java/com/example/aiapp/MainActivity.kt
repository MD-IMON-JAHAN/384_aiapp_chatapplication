package com.example.aiapp

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.aiapp.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val messages = mutableListOf<Message>()
    private lateinit var adapter: ChatAdapter
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // Replace with your actual Gemini API key
    private val GEMINI_KEY = "xxxxxxxxxxxxxx"

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ChatAdapter(messages)
        binding.recyclerChat.layoutManager = LinearLayoutManager(this)
        binding.recyclerChat.adapter = adapter

        auth.signInAnonymously()
            .addOnSuccessListener {
                Toast.makeText(this, "Firebase Auth Success", Toast.LENGTH_SHORT).show()
                loadChats()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Firebase Auth Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }

        binding.btnSend.setOnClickListener {
            val userMsg = binding.etMessage.text.toString()
            if (userMsg.isNotBlank()) {
                addMessage(userMsg, "user")
                binding.etMessage.text.clear()
                callGemini(userMsg)
            }
        }
    }

    private fun loadChats() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).collection("chats")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .get()
            .addOnSuccessListener { querySnapshot ->
                messages.clear()
                for (doc in querySnapshot.documents) {
                    val msg = doc.toObject(Message::class.java) ?: continue
                    messages.add(msg)
                }
                adapter.notifyDataSetChanged()
                if (messages.isNotEmpty()) {
                    binding.recyclerChat.scrollToPosition(messages.size - 1)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load chats: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun addMessage(text: String, sender: String) {
        val msg = Message(text, sender)
        messages.add(msg)
        adapter.notifyItemInserted(messages.size - 1)
        binding.recyclerChat.scrollToPosition(messages.size - 1)

        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).collection("chats").add(msg)
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to save message: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun callGemini(userMsg: String) {
        // Construct the request body according to Gemini API
        val contentsArray = JSONArray()
        val contentObject = JSONObject()
        val partsArray = JSONArray()
        partsArray.put(JSONObject().put("text", userMsg))
        contentObject.put("parts", partsArray)
        contentObject.put("role", "user")
        contentsArray.put(contentObject)

        val json = JSONObject()
        json.put("contents", contentsArray)

        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        // Use the API key in the query parameter instead of the Authorization header
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$GEMINI_KEY")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Gemini Request Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val res = response.body?.string() ?: "{}"
                println("Gemini Response: $res") // Debugging

                val aiText = try {
                    val jsonObj = JSONObject(res)
                    // Check for errors first
                    if (jsonObj.has("error")) {
                        val error = jsonObj.getJSONObject("error")
                        "Error: ${error.getString("message")}"
                    } else {
                        // Extract text from the response
                        val candidates = jsonObj.getJSONArray("candidates")
                        if (candidates.length() > 0) {
                            val candidate = candidates.getJSONObject(0)
                            val content = candidate.getJSONObject("content")
                            val parts = content.getJSONArray("parts")
                            if (parts.length() > 0) {
                                parts.getJSONObject(0).getString("text")
                            } else {
                                "No text in response"
                            }
                        } else {
                            "No candidates returned"
                        }
                    }
                } catch (e: Exception) {
                    "Error parsing response: ${e.message}"
                }

                runOnUiThread { addMessage(aiText, "ai") }
            }
        })
    }
}