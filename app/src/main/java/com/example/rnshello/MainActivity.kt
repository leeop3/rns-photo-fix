package com.example.rnshello

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var spinnerDevices:     Spinner
    private lateinit var btnConnect:         Button
    private lateinit var tvMyAddress:        TextView
    private lateinit var btnTabChat:         Button
    private lateinit var btnTabAnnounces:    Button
    private lateinit var btnTabContacts:     Button
    private lateinit var panelChat:          LinearLayout
    private lateinit var panelAnnounces:     LinearLayout
    private lateinit var panelContacts:      LinearLayout
    private lateinit var scrollChat:         ScrollView
    private lateinit var chatContainer:      LinearLayout
    private lateinit var announcesContainer: LinearLayout
    private lateinit var contactsContainer:  LinearLayout
    private lateinit var etDestHash:         EditText
    private lateinit var etMessage:          EditText
    private lateinit var btnSend:            Button
    private lateinit var btnAnnounce:        Button
    private lateinit var btnSettings:        ImageButton
    private lateinit var btnAttach:          ImageButton

    // ── State ─────────────────────────────────────────────────────────────────

    private val handler           = Handler(Looper.getMainLooper())
    private var refreshRunnable:  Runnable? = null
    private var lastMessageCount  = 0
    private var lastAnnounceCount = 0
    private val scope             = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var myAddress         = ""

    // ── Service binding ───────────────────────────────────────────────────────

    private var rnsService: RnsService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            rnsService = (binder as RnsService.RnsBinder).getService()
            serviceBound = true

            // If RNS is already running (service survived rotation), restore UI
            rnsService?.let { svc ->
                if (svc.isRnsStarted) {
                    myAddress = svc.myAddress
                    tvMyAddress.text = "📋 My address: ${svc.myAddress}"
                    toast("RNS already running")
                    startPolling()
                }
            }

            // Set callbacks for async results
            rnsService?.onRnsStarted = { addr ->
                runOnUiThread {
                    myAddress = addr
                    tvMyAddress.text = "📋 My address: $addr"
                    btnConnect.isEnabled = true
                    toast("Ready! Tap address to show QR")
                    startPolling()
                }
            }
            rnsService?.onRnsError = { error ->
                runOnUiThread {
                    toast(error)
                    btnConnect.isEnabled = true
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            rnsService = null
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        if (!Python.isStarted()) Python.start(AndroidPlatform(this))

        setupTabs()
        setupAddressBar()
        setupSendButton()
        setupAnnounceButton()
        setupSettingsButton()
        setupAttachButton()
        requestPermissions()

        // Start and bind to the foreground service
        val serviceIntent = Intent(this, RnsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshRunnable?.let { handler.removeCallbacks(it) }
        scope.cancel()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        // NOTE: We do NOT stop the service here — it keeps running in background
        // so the radio stays alive for incoming link proofs
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews() {
        spinnerDevices     = findViewById(R.id.spinnerDevices)
        btnConnect         = findViewById(R.id.btnConnect)
        tvMyAddress        = findViewById(R.id.tvMyAddress)
        btnTabChat         = findViewById(R.id.btnTabChat)
        btnTabAnnounces    = findViewById(R.id.btnTabAnnounces)
        btnTabContacts     = findViewById(R.id.btnTabContacts)
        panelChat          = findViewById(R.id.panelChat)
        panelAnnounces     = findViewById(R.id.panelAnnounces)
        panelContacts      = findViewById(R.id.panelContacts)
        scrollChat         = findViewById(R.id.scrollChat)
        chatContainer      = findViewById(R.id.chatContainer)
        announcesContainer = findViewById(R.id.announcesContainer)
        contactsContainer  = findViewById(R.id.contactsContainer)
        etDestHash         = findViewById(R.id.etDestHash)
        etMessage          = findViewById(R.id.etMessage)
        btnSend            = findViewById(R.id.btnSend)
        btnAnnounce        = findViewById(R.id.btnAnnounce)
        btnSettings        = findViewById(R.id.btnSettings)
        btnAttach          = findViewById(R.id.btnAttach)
    }

    // ── UI setup ──────────────────────────────────────────────────────────────

    private fun setupTabs() {
        btnTabChat.setOnClickListener      { showTab("chat") }
        btnTabAnnounces.setOnClickListener { showTab("announces") }
        btnTabContacts.setOnClickListener  { showTab("contacts") }
    }

    private fun setupAddressBar() {
        tvMyAddress.setOnClickListener {
            if (myAddress.isNotEmpty()) showQrDialog(myAddress)
        }
        val addressDialog = {
            AlertDialog.Builder(this)
                .setTitle("Enter address")
                .setMessage("Type address manually or scan a QR code")
                .setPositiveButton("📷 Scan QR") { dialog, _ ->
                    dialog.dismiss()
                    etDestHash.post { launchQrScanner() }
                }
                .setNegativeButton("Type manually", null)
                .show()
        }
        etDestHash.setOnClickListener     { addressDialog() }
        etDestHash.setOnLongClickListener { addressDialog(); true }
    }

    private fun setupSendButton() {
        btnSend.setOnClickListener {
            val dest = etDestHash.text.toString().trim()
            val text = etMessage.text.toString().trim()
            if (dest.isEmpty()) { toast("Enter a destination address"); return@setOnClickListener }
            if (text.isEmpty()) { toast("Enter a message");             return@setOnClickListener }
            etMessage.setText("")
            scope.launch(Dispatchers.IO) {
                val result = RNSBridge.sendMessage(dest, text)
                withContext(Dispatchers.Main) { toast(result); refreshMessages() }
            }
        }
    }

    private fun setupAnnounceButton() {
        btnAnnounce.setOnClickListener {
            scope.launch(Dispatchers.IO) {
                val result = try { RNSBridge.announce() } catch (e: Exception) { "Error: ${e.message}" }
                withContext(Dispatchers.Main) { toast(result) }
            }
        }
    }

    // ── Tabs ──────────────────────────────────────────────────────────────────

    private fun showTab(tab: String) {
        val cyan = colorStateList("#00d4ff")
        val dark = colorStateList("#0f3460")

        listOf(btnTabChat, btnTabAnnounces, btnTabContacts).forEach {
            it.backgroundTintList = dark
            it.setTextColor(Color.WHITE)
        }
        panelChat.visibility      = View.GONE
        panelAnnounces.visibility = View.GONE
        panelContacts.visibility  = View.GONE

        when (tab) {
            "chat" -> {
                panelChat.visibility = View.VISIBLE
                btnTabChat.backgroundTintList = cyan
                btnTabChat.setTextColor(Color.parseColor("#1a1a2e"))
            }
            "announces" -> {
                panelAnnounces.visibility = View.VISIBLE
                btnTabAnnounces.backgroundTintList = cyan
                btnTabAnnounces.setTextColor(Color.parseColor("#1a1a2e"))
                refreshAnnounces()
            }
            "contacts" -> {
                panelContacts.visibility = View.VISIBLE
                btnTabContacts.backgroundTintList = cyan
                btnTabContacts.setTextColor(Color.parseColor("#1a1a2e"))
                refreshContacts()
            }
        }
    }

    // ── Bluetooth setup ───────────────────────────────────────────────────────

    private fun setupBluetooth() {
        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val ba = bm.adapter ?: run { toast("No Bluetooth!"); return }

        val paired = ba.bondedDevices?.toList() ?: emptyList()
        toast("Found ${paired.size} paired device(s)")

        spinnerDevices.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            paired.map { "${it.name} (${it.address})" }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        btnConnect.setOnClickListener {
            val idx = spinnerDevices.selectedItemPosition
            if (idx < 0 || idx >= paired.size) return@setOnClickListener
            val svc = rnsService ?: run { toast("Service not ready"); return@setOnClickListener }
            btnConnect.isEnabled = false
            toast("Connecting to ${paired[idx].address}...")
            svc.connectAndStart(paired[idx].address)
        }
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling() {
        refreshRunnable = object : Runnable {
            override fun run() {
                refreshMessages()
                refreshAnnounces()
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(refreshRunnable!!)
    }

    private fun refreshMessages() {
        val messages = try { RNSBridge.getMessages() } catch (_: Exception) { return }
        if (messages.size == lastMessageCount) return
        lastMessageCount = messages.size
        runOnUiThread {
            chatContainer.removeAllViews()
            messages.forEach { msg ->
                val hash = msg["from"] ?: ""
                val displayName = if (msg["direction"] == "out") "me"
                                  else RNSBridge.resolveName(hash, hash.take(8))
                addChatBubble(
                    from       = displayName,
                    text       = msg["text"] ?: "",
                    ts         = msg["ts"]   ?: "",
                    isOutgoing = msg["direction"] == "out"
                )
            }
            scrollChat.post { scrollChat.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun refreshAnnounces() {
        val announces = try { RNSBridge.getAnnounces() } catch (_: Exception) { return }
        if (announces.size == lastAnnounceCount) return
        lastAnnounceCount = announces.size
        runOnUiThread {
            announcesContainer.removeAllViews()
            announces.reversed().forEach { ann ->
                val hash    = ann["hash"] ?: ""
                val rnsName = ann["name"] ?: ""
                val displayName = RNSBridge.resolveName(hash, rnsName)
                addAnnounceCard(hash = hash, displayName = displayName, ts = ann["ts"] ?: "")
            }
        }
    }

    private fun refreshContacts() {
        val messages = try { RNSBridge.getMessages() } catch (_: Exception) { return }
        val seen = linkedSetOf<String>()
        messages.forEach { msg ->
            val hash = msg["from"] ?: ""
            if (hash.isNotEmpty() && hash != "me" && hash.length == 32) seen.add(hash)
        }
        runOnUiThread {
            contactsContainer.removeAllViews()
            if (seen.isEmpty()) {
                contactsContainer.addView(TextView(this).apply {
                    text = "No conversations yet.\nStart chatting and addresses will appear here."
                    setTextColor(Color.GRAY)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(32, 64, 32, 0)
                })
            } else {
                seen.forEach { hash -> addContactCard(hash) }
            }
        }
    }

    // ── Chat bubbles ──────────────────────────────────────────────────────────

    private fun addChatBubble(from: String, text: String, ts: String, isOutgoing: Boolean) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 4, 0, 4) }
            gravity = if (isOutgoing) Gravity.END else Gravity.START
        }

        if (!isOutgoing) {
            wrapper.addView(TextView(this).apply {
                this.text = from
                textSize  = 9f
                setTextColor(Color.parseColor("#00d4ff"))
                typeface  = Typeface.MONOSPACE
            })
        }

        val trimmed = text.trim().trimStart('\u0000')
        wrapper.addView(
            if (trimmed.startsWith("IMG_B64:") || trimmed.startsWith("IMG:")) buildImageBubble(trimmed, isOutgoing)
            else buildTextBubble(trimmed, isOutgoing)
        )

        wrapper.addView(TextView(this).apply {
            this.text = ts
            textSize  = 9f
            setTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = if (isOutgoing) Gravity.END else Gravity.START }
        })

        chatContainer.addView(wrapper)
    }

    private fun buildTextBubble(text: String, isOutgoing: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize  = 14f
            setTextColor(Color.WHITE)
            setPadding(16, 10, 16, 10)
            setBackgroundColor(
                if (isOutgoing) Color.parseColor("#0f3460")
                else            Color.parseColor("#1a3a1a")
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = if (isOutgoing) Gravity.END else Gravity.START }
        }

    private fun buildImageBubble(trimmed: String, isOutgoing: Boolean): View {
        return try {
            val b64 = when {
                trimmed.startsWith("IMG_B64:") -> trimmed.removePrefix("IMG_B64:")
                trimmed.startsWith("IMG:")     -> trimmed.removePrefix("IMG:")
                else -> trimmed
            }
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            val bmp   = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(
                    resources.displayMetrics.widthPixels * 2 / 3,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.gravity = if (isOutgoing) Gravity.END else Gravity.START }
                setPadding(4, 4, 4, 4)
            }
        } catch (e: Exception) {
            buildTextBubble("[Image decode error: ${e.message}]", isOutgoing)
        }
    }

    // ── Announce cards ────────────────────────────────────────────────────────

    private fun addAnnounceCard(hash: String, displayName: String, ts: String) {
        val cleanHash = hash.replace("<", "").replace(">", "")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0f3460"))
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 4, 0, 4) }
        }
        card.addView(TextView(this).apply {
            text = displayName.ifEmpty { cleanHash.take(16) }
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = cleanHash
            textSize = 10f
            setTextColor(Color.parseColor("#00d4ff"))
            typeface = Typeface.MONOSPACE
        })
        card.addView(TextView(this).apply {
            text = "Seen at $ts"
            textSize = 10f
            setTextColor(Color.GRAY)
        })
        card.setOnClickListener {
            etDestHash.setText(cleanHash)
            showTab("chat")
        }
        announcesContainer.addView(card)
    }

    // ── Contact cards ─────────────────────────────────────────────────────────

    private fun addContactCard(hash: String) {
        val cleanHash = hash.replace("<", "").replace(">", "")
        val nickname  = RNSBridge.resolveName(cleanHash, "")

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0f3460"))
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 4, 0, 4) }
            gravity = Gravity.CENTER_VERTICAL
        }

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = nickname.ifEmpty { cleanHash.take(16) }
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = cleanHash
                textSize = 10f
                setTextColor(Color.parseColor("#00d4ff"))
                typeface = Typeface.MONOSPACE
            })
        })

        card.addView(Button(this).apply {
            text = "Chat"
            textSize = 12f
            setTextColor(Color.WHITE)
            backgroundTintList = colorStateList("#00d4ff")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = 8 }
            setOnClickListener {
                etDestHash.setText(cleanHash)
                showTab("chat")
            }
        })

        card.setOnClickListener {
            etDestHash.setText(cleanHash)
            showTab("chat")
        }

        card.setOnLongClickListener {
            showContactCardDialog(cleanHash, nickname)
            true
        }

        contactsContainer.addView(card)
    }

    // ── Contact card dialog ───────────────────────────────────────────────────

    private fun showContactCardDialog(hash: String, currentNickname: String) {
        val cleanHash = hash.replace("<", "").replace(">", "")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        layout.addView(TextView(this).apply {
            text     = cleanHash
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00d4ff"))
            setPadding(0, 0, 0, 16)
        })

        layout.addView(TextView(this).apply {
            text     = "Nickname"
            textSize = 12f
            setTextColor(Color.GRAY)
        })

        val input = EditText(this).apply {
            setText(currentNickname)
            hint = "e.g. Alice's RNode"
            setTextColor(Color.parseColor("#1a1a2e"))
            setHintTextColor(Color.parseColor("#999999"))
            setSingleLine(true)
        }
        layout.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Contact card")
            .setView(layout)
            .setPositiveButton("💾 Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { toast("Enter a nickname"); return@setPositiveButton }
                scope.launch(Dispatchers.IO) {
                    RNSBridge.saveContact(cleanHash, name)
                    withContext(Dispatchers.Main) {
                        toast("Saved: $name")
                        lastMessageCount  = 0
                        lastAnnounceCount = 0
                        refreshMessages()
                        refreshAnnounces()
                        refreshContacts()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .also { builder ->
                if (currentNickname.isNotEmpty()) {
                    builder.setNeutralButton("🗑️ Remove") { _, _ ->
                        scope.launch(Dispatchers.IO) {
                            RNSBridge.deleteContact(cleanHash)
                            withContext(Dispatchers.Main) {
                                toast("Nickname removed")
                                lastMessageCount  = 0
                                lastAnnounceCount = 0
                                refreshMessages()
                                refreshAnnounces()
                                refreshContacts()
                            }
                        }
                    }
                }
            }
            .show()
    }

    // ── Image attach ─────────────────────────────────────────────────────────

    private fun setupAttachButton() {
        btnAttach.setOnClickListener {
            val dest = etDestHash.text.toString().trim()
            if (dest.isEmpty()) { toast("Enter a destination address first"); return@setOnClickListener }
            showImageSourceDialog()
        }
    }

    private fun showImageSourceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Send image")
            .setItems(arrayOf("📷 Take photo", "🖼️ Choose from gallery")) { _, which ->
                when (which) {
                    0 -> launchImageCamera()
                    1 -> launchImageGallery()
                }
            }
            .show()
    }

    private fun launchImageCamera() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, REQ_IMAGE_CAMERA)
        } else {
            toast("No camera app available")
        }
    }

    private fun launchImageGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        startActivityForResult(intent, REQ_IMAGE_GALLERY)
    }

    private fun compressToBase64(bmp: Bitmap): String {
        val maxDim = 320
        val scaled = if (bmp.width > maxDim || bmp.height > maxDim) {
            val ratio = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * ratio).toInt(),
                (bmp.height * ratio).toInt(),
                true
            )
        } else bmp

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.WEBP, 22, out)
        val bytes = out.toByteArray()
        val kb = bytes.size / 1024f
        android.util.Log.d("RNSHello", "Image compressed: ${kb.toInt()} KB (WebP q22 @ ${scaled.width}×${scaled.height})")
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun handleImageResult(bmp: Bitmap) {
        val dest = etDestHash.text.toString().trim()
        if (dest.isEmpty()) { toast("No destination set"); return }

        toast("Compressing image…")
        scope.launch(Dispatchers.IO) {
            val b64 = compressToBase64(bmp)
            val kb  = (b64.length * 3 / 4) / 1024
            withContext(Dispatchers.Main) {
                val preview = ImageView(this@MainActivity).apply {
                    val previewBytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                    setImageBitmap(BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size))
                    adjustViewBounds = true
                    setPadding(16, 16, 16, 8)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Send image? (~${kb} KB — WebP)")
                    .setMessage("Sending over LoRa may take 1–3 minutes. Keep both devices on.")
                    .setView(preview)
                    .setPositiveButton("📤 Send") { _, _ ->
                        scope.launch(Dispatchers.IO) {
                            val result = RNSBridge.sendImage(dest, b64)
                            withContext(Dispatchers.Main) {
                                toast(result)
                                if (result.startsWith("Sending")) {
                                    lastMessageCount = 0
                                    refreshMessages()
                                }
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    private fun setupSettingsButton() {
        btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun showSettingsDialog() {
        val cfg = try { RNSBridge.getRnodeConfig() } catch (_: Exception) { emptyMap() }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        fun addRow(label: String, value: String, hint: String): EditText {
            layout.addView(TextView(this).apply {
                text      = label
                textSize  = 12f
                setTextColor(Color.GRAY)
                setPadding(0, 12, 0, 2)
            })
            return EditText(this).apply {
                setText(value)
                this.hint = hint
                setTextColor(Color.parseColor("#1a1a2e"))
                setHintTextColor(Color.parseColor("#999999"))
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                layout.addView(this)
            }
        }

        val etFreq  = addRow("Frequency (Hz)",       cfg["frequency"] ?: "433025000", "e.g. 433025000")
        val etBw    = addRow("Bandwidth (Hz)",        cfg["bandwidth"]  ?: "125000",   "e.g. 125000")
        val etTx    = addRow("TX Power (0–17 dBm)",   cfg["txpower"]   ?: "17",        "0–17")
        val etSf    = addRow("Spreading Factor (6–12)", cfg["sf"]      ?: "7",         "6–12")
        val etCr    = addRow("Coding Rate (5–8)",     cfg["cr"]        ?: "6",         "5–8")

        layout.addView(TextView(this).apply {
            text     = "Changes apply on next Connect."
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 16, 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("⚙️ RNode Radio Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                try {
                    val freq = etFreq.text.toString().trim().toInt()
                    val bw   = etBw.text.toString().trim().toInt()
                    val tx   = etTx.text.toString().trim().toInt()
                    val sf   = etSf.text.toString().trim().toInt()
                    val cr   = etCr.text.toString().trim().toInt()
                    scope.launch(Dispatchers.IO) {
                        val result = RNSBridge.saveRnodeConfig(freq, bw, tx, sf, cr)
                        withContext(Dispatchers.Main) {
                            if (result == "OK") toast("Settings saved — reconnect to apply")
                            else toast(result)
                        }
                    }
                } catch (_: NumberFormatException) {
                    toast("All fields must be numbers")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── QR ────────────────────────────────────────────────────────────────────

    private fun showQrDialog(address: String) {
        val size = 600
        val bits = try {
            MultiFormatWriter().encode(address, BarcodeFormat.QR_CODE, size, size)
        } catch (e: Exception) { toast("QR error: ${e.message}"); return }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            for (x in 0 until size) for (y in 0 until size)
                setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
        }
        AlertDialog.Builder(this)
            .setTitle("My Address")
            .setMessage(address)
            .setView(ImageView(this).apply { setImageBitmap(bmp); setPadding(32, 32, 32, 32) })
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("address", address))
                toast("Address copied!")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun launchQrScanner() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        startActivityForResult(Intent(this, QrScanActivity::class.java), REQ_QR_SCAN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when {
            requestCode == REQ_QR_SCAN && resultCode == Activity.RESULT_OK -> {
                val scanned = data?.getStringExtra("SCAN_RESULT")?.trim() ?: return
                etDestHash.setText(scanned)
                showTab("chat")
                toast("Address scanned!")
            }
            requestCode == REQ_IMAGE_CAMERA && resultCode == Activity.RESULT_OK -> {
                val bmp = data?.extras?.get("data") as? Bitmap
                if (bmp != null) handleImageResult(bmp)
                else toast("Could not get image from camera")
            }
            requestCode == REQ_IMAGE_GALLERY && resultCode == Activity.RESULT_OK -> {
                val uri: Uri = data?.data ?: return
                try {
                    val stream = contentResolver.openInputStream(uri)
                    val bmp = BitmapFactory.decodeStream(stream)
                    stream?.close()
                    if (bmp != null) handleImageResult(bmp)
                    else toast("Could not decode image")
                } catch (e: Exception) {
                    toast("Gallery error: ${e.message}")
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestPermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
            }
            if (!hasPermission(Manifest.permission.CAMERA))
                add(Manifest.permission.CAMERA)
        }
        if (perms.isEmpty()) setupBluetooth()
        else ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_CAMERA      -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) launchQrScanner()
            REQ_PERMISSIONS -> if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) setupBluetooth()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun colorStateList(hex: String) =
        android.content.res.ColorStateList.valueOf(Color.parseColor(hex))

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val REQ_CAMERA       = 101
        const val REQ_QR_SCAN      = 102
        const val REQ_PERMISSIONS  = 1
        const val REQ_IMAGE_CAMERA = 103
        const val REQ_IMAGE_GALLERY= 104
    }
}
