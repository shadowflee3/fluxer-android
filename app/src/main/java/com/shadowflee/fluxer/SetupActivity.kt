package com.shadowflee.fluxer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var errorText: TextView
    private lateinit var connectButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        urlInput = findViewById(R.id.urlInput)
        errorText = findViewById(R.id.errorText)
        connectButton = findViewById(R.id.connectButton)

        // Pre-fill with any previously saved URL (editing flow)
        val existing = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_SERVER_URL, "") ?: ""
        if (existing.isNotEmpty()) {
            urlInput.setText(existing)
            urlInput.setSelection(existing.length)
        }

        connectButton.setOnClickListener { attemptSave() }

        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptSave()
                true
            } else false
        }

        // Modern back navigation — set result CANCELED so MainActivity knows setup was dismissed
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                setResult(RESULT_CANCELED)
                finish()
            }
        })
    }

    private fun attemptSave() {
        val raw = urlInput.text?.toString()?.trim() ?: ""
        val error = validate(raw)
        if (error != null) {
            errorText.text = error
            return
        }
        // Validation passed — persist and return success
        getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(MainActivity.KEY_SERVER_URL, raw)
            .apply()

        setResult(RESULT_OK)
        finish()
    }

    private fun validate(url: String): String? {
        if (url.isBlank()) return "Please enter a server URL."
        if (url.length > 2048) return "URL is too long."
        return try {
            val uri = Uri.parse(url)
            when {
                uri.scheme != "http" && uri.scheme != "https" ->
                    "URL must start with http:// or https://"
                uri.host.isNullOrBlank() ->
                    "URL must include a hostname."
                else -> null
            }
        } catch (_: Exception) {
            "Invalid URL."
        }
    }

}

