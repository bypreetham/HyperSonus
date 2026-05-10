package com.example.first

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.example.first.engine.AudioEngineFactory
import com.example.first.engine.IAudioEngine
import com.example.first.engine.NativeHiResEngine
import com.example.first.engine.MediaPlayerEngine
import android.os.Binder
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

interface MusicServiceListener {
    fun onSongChanged(song: Song)
    fun onPlaybackStateChanged(isPlaying: Boolean)
    fun onPermissionRequired(permission: String)
}

class MusicService : Service() {

    private val TAG = "MusicService"
    private var currentEngineType: AudioEngineFactory.EngineType = AudioEngineFactory.EngineType.NORMAL
    private var isCurrentlyBitPerfect: Boolean = false
    
    // V9: Unified Source of Truth for Playback State
    private var targetPlaybackState: Boolean = false
    
    private var audioEngine: IAudioEngine? = null
    private var nextAudioEngine: IAudioEngine? = null
    private var nextSong: Song? = null
    private var equalizer: android.media.audiofx.Equalizer? = null
    private val binder = MusicBinder()
    
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "pref_bit_perfect" || key == "pref_exclusive_mode") {
             if (currentEngineType == com.example.first.engine.AudioEngineFactory.EngineType.HI_RES && currentSong != null) {
                  Log.d(TAG, "Native tuning pref changed ($key), reloading engine...")
                  val pos = audioEngine?.getCurrentPosition() ?: 0
                  // Reload current song with same position
                  playSong(currentSong!!, pos)
              }
         }
     }
     
    private val listeners = mutableListOf<MusicServiceListener>()
  
    private var playlist: List<Song> = emptyList()
    private var queue: MutableList<Song> = mutableListOf()
    private var currentIndex: Int = -1
    private var currentSong: Song? = null
    private var playbackSpeed: Float = 1.0f

    // Sleep Timer
    private var sleepTimer: CountDownTimer? = null

    // Audio Focus
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
  
    // MediaSession & Notifications
    private lateinit var mediaSession: MediaSessionCompat
    private val CHANNEL_ID = "music_channel"
    private val ERROR_CHANNEL_ID = "error_channel"
    private val NOTIFICATION_ID = 1
    private val ERR_NOTIFICATION_ID = 2
  
    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val wlStatus = if (wakeLock?.isHeld == true) "ACQUIRED" else "RELEASED"
            Log.d(TAG, "Heartbeat: Service is alive (WL: $wlStatus, Engine: $currentEngineType, Playing: ${isPlaying()})")
            heartbeatHandler.postDelayed(this, 5000) // Increased frequency for debugging
        }
    }
  
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> Log.d(TAG, "Device Event: SCREEN_OFF")
                Intent.ACTION_SCREEN_ON -> Log.d(TAG, "Device Event: SCREEN_ON")
            }
        }
    }
    private var isFallbackActive = false

    companion object {
        const val ACTION_PLAY = "com.example.first.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.first.ACTION_PAUSE"
        const val ACTION_PREV = "com.example.first.ACTION_PREV"
        const val ACTION_NEXT = "com.example.first.ACTION_NEXT"
        const val ACTION_STOP = "com.example.first.ACTION_STOP"
        const val ACTION_COPY_ERROR = "com.example.first.ACTION_COPY_ERROR"
        const val ACTION_SET_BS2B = "com.example.first.ACTION_SET_BS2B"
        const val ACTION_SET_BS2B_LEVEL = "com.example.first.ACTION_SET_BS2B_LEVEL"
        
        internal var sessionToken: MediaSessionCompat.Token? = null
        fun getSessionToken(): MediaSessionCompat.Token? = sessionToken
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeSong()
                    resumeOnFocusGain = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pauseSong()
                resumeOnFocusGain = false // Permanent loss
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying()) {
                    pauseSong(abandonFocus = false) // Keep focus request to receive GAIN
                    resumeOnFocusGain = true
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Ignore notifications (Uninterrupted Music)
            }
        }
    }

    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent?.action) {
                // Check if user enabled "Pause on Disconnect"
                val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val shouldPause = prefs.getBoolean("pause_on_disconnect", true)
                
                if (shouldPause && isPlaying()) {
                    android.util.Log.d(TAG, "Audio becoming noisy and setting is ON, pausing playback")
                    pauseSong()
                }
            }
        }
    }

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.first.ACTION_SET_PREAMP" -> {
                    val db = intent.getFloatExtra("db", 0f)
                    Log.d(TAG, "[HyperHiRes] Pre-Amp Broadcast RECEIVED: ${db}dB")
                    setPreAmp(db)
                }
                "com.example.first.ACTION_SET_RESONANCE" -> {
                    val enabled = intent.getBooleanExtra("enabled", false)
                    Log.d(TAG, "[HyperHiRes] Resonance Broadcast RECEIVED: enabled=$enabled")
                    setResonanceEnabled(enabled)
                }
                "com.example.first.ACTION_SET_SOXR" -> {
                    val enabled = intent.getBooleanExtra("enabled", true)
                    Log.d(TAG, "[HyperHiRes] Soxr Broadcast RECEIVED: enabled=$enabled")
                    audioEngine?.setResampleEngine(enabled)
                }
                "com.example.first.ACTION_SET_BS2B" -> {
                    val enabled = intent.getBooleanExtra("enabled", false)
                    Log.d(TAG, "[HyperHiRes] BS2B Broadcast RECEIVED: enabled=$enabled")
                    (audioEngine as? com.example.first.engine.NativeHiResEngine)?.updateBs2bState()
                }
                "com.example.first.ACTION_SET_BS2B_LEVEL" -> {
                    val level = intent.getIntExtra("level", 0)
                    Log.d(TAG, "[HyperHiRes] BS2B Level Broadcast RECEIVED: 0x${Integer.toHexString(level)}")
                    (audioEngine as? com.example.first.engine.NativeHiResEngine)?.setDspParameter(
                        com.example.first.engine.NativeHiResEngine.DSP_PARAM_BS2B_LEVEL,
                        level.toFloat()
                    )
                }
            }
        }
    }

    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
            Log.d(TAG, "Audio devices added, probing capabilities...")
            probeActiveDevice()
            
            val deviceClass = com.example.first.engine.DeviceHelper.getCurrentDeviceClass(this@MusicService)
            val newEngine = com.example.first.engine.AudioEngineFactory.getEngineForClassification(deviceClass)
            
            if (newEngine != currentEngineType) {
                Log.d(TAG, "Device Add: Auto-routing to $newEngine for class ${deviceClass.id}")
                switchToEngine(newEngine)
                
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(this@MusicService, "Auto-Routing: Device Class ${deviceClass.id}", Toast.LENGTH_SHORT).show()
                }
            } else if (currentEngineType == com.example.first.engine.AudioEngineFactory.EngineType.HI_RES && newEngine == currentEngineType) {
                val dacInfo = com.example.first.engine.DacHelper.getCurrentDacInfo(this@MusicService)
                Log.d(TAG, "Routing Native Engine within HI_RES to device: ${dacInfo.name}")
                audioEngine?.setDeviceId(dacInfo.id)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
            Log.d(TAG, "Audio device removed")
            
            val deviceClass = com.example.first.engine.DeviceHelper.getCurrentDeviceClass(this@MusicService)
            val newEngine = com.example.first.engine.AudioEngineFactory.getEngineForClassification(deviceClass)
            
            if (newEngine != currentEngineType) {
                Log.d(TAG, "Device Remove: Auto-routing to $newEngine for class ${deviceClass.id}")
                
                // V11: Capture Position BEFORE any state changes or pauses
                val currentPos = getCurrentPosition()
                Log.d(TAG, "onAudioDevicesRemoved: Atomic capture of position: $currentPos")

                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val shouldPause = prefs.getBoolean("pause_on_disconnect", true)
                
                // V9: Update Source of Truth first
                if (shouldPause) {
                    targetPlaybackState = false
                    Log.d(TAG, "onAudioDevicesRemoved: Pause on Disconnect is ON, target state set to FALSE")
                } else {
                    Log.d(TAG, "onAudioDevicesRemoved: Pause on Disconnect is OFF, preserving target state: $targetPlaybackState")
                }

                // V8: Pause IMMEDIATELY for absolute silence
                Log.d(TAG, "onAudioDevicesRemoved: Pausing current engine before shift")
                audioEngine?.pause()
                
                switchToEngine(newType = newEngine, isDisconnect = true, overridePos = currentPos)
                
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(this@MusicService, "Auto-Routing back to Device Class ${deviceClass.id}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun probeActiveDevice() {
        val dacInfo = com.example.first.engine.DacHelper.getCurrentDacInfo(this)
        if (dacInfo.type == com.example.first.engine.DacHelper.DacType.UNKNOWN) return

        // If Bluetooth is connected, check for permission to show Hi-Res info
        if (dacInfo.type == com.example.first.engine.DacHelper.DacType.BLUETOOTH) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Bluetooth permission missing, notifying listeners")
                    notifyPermissionRequired(android.Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
        }

        // Probing can take a few seconds, do it in a background thread
        Thread {
            try {
                // Ensure native engine is initialized
                if (audioEngine == null) {
                    audioEngine = com.example.first.engine.AudioEngineFactory.createEngine(this, com.example.first.engine.AudioEngineFactory.EngineType.HI_RES)
                }
                
                Log.d(TAG, "Probing sample rates for device: ${dacInfo.name} (ID: ${dacInfo.id})")
                val supportedRates = audioEngine?.probeSampleRates(dacInfo.id) ?: intArrayOf()
                Log.d(TAG, "Probed rates: ${supportedRates.joinToString(", ")}")
                
                com.example.first.engine.DacHelper.setProbedRates(dacInfo.id, supportedRates)
            } catch (e: Exception) {
                Log.e(TAG, "Probing failed: ${e.message}")
            }
        }.start()
    }

    private fun notifyPermissionRequired(permission: String) {
        listeners.forEach { it.onPermissionRequired(permission) }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        // 🛡️ Global crash reporting to bypass logcat filters
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "CRITICAL: FATAL EXCEPTION in thread ${thread.name}: ${throwable.message}", throwable)
            oldHandler?.uncaughtException(thread, throwable)
        }
        
        // Initialize WakeLock
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperAudio::PlaybackWakeLock")
            wakeLock?.setReferenceCounted(false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WakeLock", e)
        }

        com.example.first.engine.DacHelper.initBluetoothListener(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "MusicService").apply {
            setCallback(mediaSessionCallback)
            isActive = true
            MusicService.sessionToken = this.sessionToken
        }

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        playbackSpeed = prefs.getFloat("playback_speed", 1.0f)

        val deviceClass = com.example.first.engine.DeviceHelper.getCurrentDeviceClass(this)
        currentEngineType = AudioEngineFactory.getEngineForClassification(deviceClass)
        audioEngine = AudioEngineFactory.createEngine(this, currentEngineType)

        val preAmpProgress = prefs.getInt("pref_preamp_progress", 220)
        setPreAmp((preAmpProgress - 200) / 10f)

        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        val filter = IntentFilter().apply {
            addAction("com.example.first.ACTION_SET_PREAMP")
            addAction("com.example.first.ACTION_SET_RESONANCE")
            addAction("com.example.first.ACTION_SET_SOXR")
            addAction("com.example.first.ACTION_SET_BS2B")
            addAction("com.example.first.ACTION_SET_BS2B_LEVEL")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(settingsReceiver, filter)
        }

        val resonanceEnabled = prefs.getBoolean("pref_resonance_enabled", false)
        setResonanceEnabled(resonanceEnabled)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }

        probeActiveDevice()

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, screenFilter)

        heartbeatHandler.post(heartbeatRunnable)
    }
  
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            null -> { /* cold OS restart */ }
            ACTION_PLAY  -> resumeSong()
            ACTION_PAUSE -> pauseSong()
            ACTION_NEXT  -> playNext()
            ACTION_PREV  -> playPrevious()
            ACTION_STOP  -> stopService()
        }
        // START_STICKY: ensures the service is restarted if it's ever killed by the OS.
        // Critical for background stability on aggressive power managers.
        return START_STICKY
    }
  
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Music Playback"
            val descriptionText = "Controls for music playback"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val errName = "Engine Errors"
                val errDesc = "Notifications for audio engine errors"
                val errImportance = NotificationManager.IMPORTANCE_HIGH
                val errChannel = NotificationChannel(ERROR_CHANNEL_ID, errName, errImportance).apply {
                    description = errDesc
                }
                notificationManager.createNotificationChannel(errChannel)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val battName = "Optimization Warnings"
                val battDesc = "Warnings about battery settings affecting playback"
                val battImportance = NotificationManager.IMPORTANCE_HIGH
                val battChannel = NotificationChannel("batt_channel", battName, battImportance).apply {
                    description = battDesc
                }
                val notificationManager: NotificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(battChannel)
            }
        }
    }
  
    private var currentStatus: String? = null

    private fun showNotification(status: String? = null) {
        if (status != null) currentStatus = status
        
        val song = currentSong ?: return
        val isPlaying = isPlaying()
  
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
  
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pause",
                getServicePendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Play",
                getServicePendingIntent(ACTION_PLAY)
            )
        }
  
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSubText(currentStatus)
            .setContentIntent(pendingIntent)
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_previous, "Previous", getServicePendingIntent(ACTION_PREV))
            .addAction(playPauseAction)
            .addAction(android.R.drawable.ic_media_next, "Next", getServicePendingIntent(ACTION_NEXT))
            .build()
  
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
  
    private fun getServicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicNotificationReceiver::class.java).apply {
            this.action = action
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(this, 0, intent, flags)
    }

    private fun showErrorNotification(error: String) {
        val copyIntent = Intent(this, MusicNotificationReceiver::class.java).apply {
            action = ACTION_COPY_ERROR
            putExtra("error_text", error)
        }
        val copyPendingIntent = PendingIntent.getBroadcast(
            this, 1, copyIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Audio Engine Error")
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_edit, "Copy Details", copyPendingIntent)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ERR_NOTIFICATION_ID, notification)
    }

    private fun stopService() {
        pauseSong()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun addListener(listener: MusicServiceListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            
            val result = audioManager.requestAudioFocus(focusRequest!!)
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    fun removeListener(listener: MusicServiceListener) {
        listeners.remove(listener)
    }

    private fun notifySongChanged(song: Song) {
        listeners.forEach { it.onSongChanged(song) }
    }

    private fun notifyPlaybackStateChanged(isPlaying: Boolean) {
        listeners.forEach { it.onPlaybackStateChanged(isPlaying) }
        updateMediaSessionState()
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int) {
        this.playlist = songs
        this.currentIndex = startIndex
        if (startIndex >= 0 && startIndex < songs.size) {
            playSong(songs[startIndex])
        }
    }

    fun addToQueue(song: Song) {
        Log.d(TAG, "Adding song to queue: ${song.title}")
        queue.add(song)
    }

    private fun switchToEngine(newType: com.example.first.engine.AudioEngineFactory.EngineType, isDisconnect: Boolean = false, overridePos: Int = -1) {
        Log.d(TAG, "Switching engine to $newType (isDisconnect=$isDisconnect)")
        
        // V11: Use override position if provided (important for Oboe disconnects)
        val currentPos = if (overridePos >= 0) overridePos else getCurrentPosition()
        Log.d(TAG, "switchToEngine: Final capture of position before release: $currentPos (wasPlaying=$targetPlaybackState)")
        
        audioEngine?.release()
        nextAudioEngine?.release()
        nextAudioEngine = null
        nextSong = null
        
        currentEngineType = newType
        audioEngine = com.example.first.engine.AudioEngineFactory.createEngine(this, newType)
        
        currentSong?.let { song ->
            // Re-prepare the new engine with the current song
            if (audioEngine?.setDataSource(this, song.uri) == true) {
                audioEngine?.setOnPreparedListener {
                    audioEngine?.seekTo(currentPos)
                    
                    // V11: Small 150ms delay for Speakers to allow OS AudioServer to stabilize Routing Class 0
                    val routingDelay = if (currentEngineType == com.example.first.engine.AudioEngineFactory.EngineType.NORMAL) 150L else 0L
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        // V9: Unified sync
                        if (targetPlaybackState) {
                            Log.d(TAG, "switchToEngine: Hardware Play (target=TRUE)")
                            audioEngine?.play()
                        } else {
                            Log.d(TAG, "switchToEngine: Hardware Pause (target=FALSE)")
                            audioEngine?.pause()
                        }
                        notifyPlaybackStateChanged(targetPlaybackState)
                    }, routingDelay)

                    initEqualizer(audioEngine?.getAudioSessionId() ?: 0)
                    applyPlaybackSpeed()
                    updateMediaSessionState()
                }
                audioEngine?.setOnCompletionListener {
                    if (nextAudioEngine != null) handleGaplessTransition() else playNext()
                }
                audioEngine?.setOnErrorListener { what, extra ->
                    Log.e(TAG, "Engine error during switch: $what, $extra")
                }
            }
        }
    }

    fun playSong(song: Song, startPosition: Int = 0) {
        Log.d(TAG, "Playing song: ${song.title} from pos: $startPosition")
        acquireWakeLock()
        Log.i(TAG, "[HyperHiRes] Requesting playback via Engine: $currentEngineType")
        
        // Request focus IMMEDIATELY before even loading the song
        if (!requestAudioFocus()) {
            Log.e(TAG, "Could not acquire audio focus. Playback may not work correctly.")
        }

        this.currentSong = song
        
        // Update index if song is in playlist
        val index = playlist.indexOfFirst { it.id == song.id }
        if (index != -1) {
            currentIndex = index
        }

        try {
            audioEngine?.release()
            audioEngine = com.example.first.engine.AudioEngineFactory.createEngine(this, currentEngineType)

            audioEngine?.apply {
                if (this is com.example.first.engine.NativeHiResEngine) {
                    this.setOnBitPerfectListener { isPerfect, rate ->
                        isCurrentlyBitPerfect = isPerfect
                        val status = "${rate}Hz ${if (isPerfect) "⚡" else ""}"
                        Log.d(TAG, "Bit-Perfect callback: $status")
                        showNotification(status)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                             Toast.makeText(applicationContext, "Playback: $status", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // Report "PLAYING" state to MediaSession immediately! 
                // Don't wait for prepared as OS might suspend us in the meantime.
                updateMediaSessionState(overrideState = PlaybackStateCompat.STATE_PLAYING)

                // SET LISTENERS BEFORE setDataSource!
                setOnPreparedListener { 
                    Log.d(TAG, "[DEBUG] onPrepared triggered")
                    if (requestAudioFocus()) {
                        Log.d(TAG, "Audio focus acquired")
                        if (startPosition > 0) seekTo(startPosition)
                        play()
                        initEqualizer(getAudioSessionId())
                        applyPlaybackSpeed()
                        notifySongChanged(song)
                        updateMediaSessionMetadata(song)
                        notifyPlaybackStateChanged(true)
                        showNotification()
                        prepareNextMediaPlayer()
                    }
                }
                setOnCompletionListener { playNext() }
                setOnErrorListener { what, extra ->
                    Log.e(TAG, "Engine error: $what/$extra")
                    val message = when (what) {
                        -4 -> "MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK"
                        -3 -> "MEDIA_ERROR_TIMED_OUT"
                        -2 -> "MEDIA_ERROR_IO"
                        -1 -> "MEDIA_ERROR_UNKNOWN"
                        1 -> "MEDIA_ERROR_SERVER_DIED"
                        else -> "MEDIA_ERROR_$what"
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        showErrorNotification("Playback Error: $message (extra: $extra)")
                    }
                }
                setDataSource(this@MusicService, song.uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play song: ${e.message}", e)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                showErrorNotification("Failed to play: ${e.message}")
            }
        }
    }

    fun resumeSong() {
        Log.d(TAG, "Resuming song")
        audioEngine?.resume()
        notifyPlaybackStateChanged(true)
        showNotification()
    }

    fun pauseSong(abandonFocus: Boolean = true) {
        Log.d(TAG, "Pausing song")
        audioEngine?.pause()
        if (abandonFocus) abandonAudioFocus()
        notifyPlaybackStateChanged(false)
        showNotification()
    }

    fun playNext() {
        Log.d(TAG, "Playing next song")
        if (currentIndex < playlist.size - 1) {
            playSong(playlist[currentIndex + 1])
        } else {
            Log.d(TAG, "Reached end of playlist")
        }
    }

    fun playPrevious() {
        Log.d(TAG, "Playing previous song")
        if (currentIndex > 0) {
            playSong(playlist[currentIndex - 1])
        }
    }

    fun isPlaying(): Boolean = audioEngine?.isPlaying() ?: false

    fun getCurrentPosition(): Int = audioEngine?.getCurrentPosition() ?: 0

    fun getDuration(): Int = audioEngine?.getDuration() ?: 0

    private fun initEqualizer(sessionId: Int) {
        // Equalizer initialization code here
    }

    private fun applyPlaybackSpeed() {
        audioEngine?.setPlaybackSpeed(playbackSpeed)
    }

    private fun setPreAmp(db: Float) {
        // Pre-amp setting code here
    }

    private fun setResonanceEnabled(enabled: Boolean) {
        // Resonance setting code here
    }

    private fun prepareNextMediaPlayer() {
        // Gapless playback preparation code here
    }

    private fun handleGaplessTransition() {
        // Gapless transition code here
    }

    private fun updateMediaSessionState(overrideState: Int = -1) {
        // Media session state update code here
    }

    private fun updateMediaSessionMetadata(song: Song) {
        // Media session metadata update code here
    }

    private fun acquireWakeLock() {
        wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes
    }

    // Add any other necessary stub methods as needed
}
