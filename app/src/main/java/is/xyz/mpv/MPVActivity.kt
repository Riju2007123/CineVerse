package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.PlayerBinding
import `is`.xyz.mpv.MPVLib.MpvEvent
import `is`.xyz.mpv.cine.CineIntroDb
import `is`.xyz.mpv.cine.CinePrefs
import `is`.xyz.mpv.cine.CineResolverActivity
import `is`.xyz.mpv.cine.CineStreamCache
import `is`.xyz.mpv.cine.CineTitle
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import androidx.appcompat.app.AlertDialog
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.util.Log
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager.getDefaultSharedPreferences
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.DisplayMetrics
import android.util.Rational
import androidx.core.content.ContextCompat
import android.view.*
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import java.io.File
import java.lang.IllegalArgumentException
import kotlin.math.roundToInt
import org.json.JSONObject

typealias ActivityResultCallback = (Int, Intent?) -> Unit
typealias StateRestoreCallback = () -> Unit

class MPVActivity : AppCompatActivity(), MPVLib.EventObserver, TouchGesturesObserver {
    // for calls to eventUi() and eventPropertyUi()
    private val eventUiHandler = Handler(Looper.getMainLooper())
    // for use with fadeRunnable1..3
    private val fadeHandler = Handler(Looper.getMainLooper())
    // for use with stopServiceRunnable
    private val stopServiceHandler = Handler(Looper.getMainLooper())

    /**
     * DO NOT USE THIS
     */
    private var activityIsStopped = false

    private var activityIsForeground = true
    private var didResumeBackgroundPlayback = false
    private var userIsOperatingSeekbar = false

    private var toast: Toast? = null

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequestCompat? = null
    private var audioFocusRestore: () -> Unit = {}

    private val psc = Utils.PlaybackStateCache()
    private var mediaSession: MediaSessionCompat? = null

    private lateinit var binding: PlayerBinding
    private lateinit var gestures: TouchGestures

    // convenience alias
    private val player get() = binding.player

    private val seekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser)
                return
            player.timePos = progress.toDouble() / SEEK_BAR_PRECISION
            // Note: don't call updatePlaybackPos() here either
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = true
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = false
            showControls() // re-trigger display timeout
        }
    }

    private var becomingNoisyReceiverRegistered = false
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, "noisy")
            }
        }
    }

    // Fade out controls
    private val fadeRunnable = object : Runnable {
        var hasStarted = false
        private val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) { hasStarted = true }

            override fun onAnimationCancel(animation: Animator) { hasStarted = false }

            override fun onAnimationEnd(animation: Animator) {
                if (hasStarted)
                    hideControls()
                hasStarted = false
            }
        }

        override fun run() {
            binding.topControls.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION)
            binding.bottomGradient.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION)
            binding.controls.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION).setListener(listener)
        }
    }

    // Fade out unlock button
    private val fadeRunnable2 = object : Runnable {
        private val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                binding.unlockBtn.visibility = View.GONE
            }
        }

        override fun run() {
            binding.unlockBtn.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION).setListener(listener)
        }
    }

    // Fade out gesture text
    private val fadeRunnable3 = object : Runnable {
        // okay this doesn't actually fade...
        override fun run() {
            binding.gestureTextView.visibility = View.GONE
        }
    }

    private val stopServiceRunnable = Runnable {
        val intent = Intent(this, BackgroundPlaybackService::class.java)
        applicationContext.stopService(intent)
    }

    /* Settings */
    private var statsFPS = false
    private var statsLuaMode = 0 // ==0 disabled, >0 page number

    private var backgroundPlayMode = ""
    private var noUIPauseMode = ""

    private var shouldSavePosition = false
    private var animeTrackId: String? = null

    // Per-series intro / credits windows (from TheIntroDB).
    private var introStartMs = 0L
    private var introEndMs = 0L
    private var creditsStartMs = 0L
    private var creditsEndMs = 0L
    private var skipMode = ""

    private var autoRotationMode = ""

    private var controlsAtBottom = true
    private var showMediaTitle = false
    private var useTimeRemaining = false

    private var rememberBrightness = false
    private var lastScreenBrightness = -1

    private var ignoreAudioFocus = false
    private var playlistExitWarning = true
    private var newIntentReplace = false

    private var smoothSeekGesture = false
    /* * */

    @SuppressLint("ClickableViewAccessibility")
    private fun initListeners() {
        with (binding) {
            playlistPrevBtn.setOnClickListener { playlistPrev() }
            playlistNextBtn.setOnClickListener { playlistNext() }
            prevBtn.setOnClickListener { MPVLib.command(arrayOf("add", "chapter", "-1")) }
            nextBtn.setOnClickListener { MPVLib.command(arrayOf("add", "chapter", "1")) }
            rewindBtn.setOnClickListener { MPVLib.command(arrayOf("seek", "-10", "exact")) }
            forwardBtn.setOnClickListener { MPVLib.command(arrayOf("seek", "10", "exact")) }
            cycleAudioBtn.setOnClickListener { cycleAudio() }
            cycleSubsBtn.setOnClickListener { cycleSub() }
            volumeBtn.setOnClickListener { MPVLib.command(arrayOf("cycle", "mute")) }
            fullscreenBtn.setOnClickListener { cycleOrientation() }
            hamburgerMenuBtn.setOnClickListener { openModernMenu() }
            skipIntroBtn.setOnClickListener {
                when (skipMode) {
                    "intro" -> {
                        val target = (introEndMs + 400) / 1000.0
                        MPVLib.command(arrayOf("seek", target.toString(), "absolute"))
                    }
                    "credits" -> {
                        if (creditsEndMs > 0L) {
                            MPVLib.command(
                                arrayOf("seek", (creditsEndMs / 1000.0).toString(), "absolute")
                            )
                        } else {
                            MPVLib.command(arrayOf("seek", "100", "absolute-percent"))
                        }
                    }
                    else -> MPVLib.command(arrayOf("add", "chapter", "1"))
                }
                hideSkipBtn()
            }

            playBtn.setOnClickListener { player.cyclePause() }
            topLockBtn.setOnClickListener { lockUI() }
            topPiPBtn.setOnClickListener { goIntoPiP() }
            topMenuBtn.setOnClickListener { openModernMenu() }
            unlockBtn.setOnClickListener { unlockUI() }
            playbackDurationTxt.setOnClickListener {
                useTimeRemaining = !useTimeRemaining
                updatePlaybackPos(psc.positionSec)
                updatePlaybackDuration(psc.durationSec)
            }

            cycleAudioBtn.setOnLongClickListener { pickAudio(); true }
            cycleSubsBtn.setOnLongClickListener { pickSub(); true }

            playbackSeekbar.setOnSeekBarChangeListener(seekBarChangeListener)
        }

        player.setOnTouchListener { _, e ->
            if (lockedUI) false else gestures.onTouchEvent(e)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.outside) { v, windowInsets ->
            // guidance: https://medium.com/androiddevelopers/gesture-navigation-handling-visual-overlaps-4aed565c134c
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updateLayoutParams<MarginLayoutParams> {
                // avoid system bars and cutout
                leftMargin = insets.left
                topMargin = insets.top
                bottomMargin = insets.bottom
                rightMargin = insets.right
            }
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this) {
            onBackPressedImpl()
        }

        addOnPictureInPictureModeChangedListener { info ->
            onPiPModeChangedImpl(info.isInPictureInPictureMode)
        }
    }

    private var playbackHasStarted = false
    private var onloadCommands = mutableListOf<Array<String>>()

    // Activity lifetime

    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)

        // Do these here and not in MainActivity because mpv can be launched from a file browser
        Utils.copyAssets(this)
        BackgroundPlaybackService.createNotificationChannel(this)

        binding = PlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Init controls to be hidden and view fullscreen
        hideControls()

        // Initialize listeners for the player view
        initListeners()

        gestures = TouchGestures(this)

        // set up initial UI state
        readSettings()
        onConfigurationChanged(resources.configuration)
        run {
            // edge-to-edge & immersive mode
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
            binding.topPiPBtn.visibility = View.GONE
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
            binding.topLockBtn.visibility = View.GONE

        if (showMediaTitle)
            binding.fullTitleTextView.visibility = View.VISIBLE

        updateOrientation(true)

        // Parse the intent
        val filepath = parsePathFromIntent(intent)

        if (filepath == null) {
            Log.e(TAG, "No file given, exiting")
            showToast(getString(R.string.error_no_file))
            finishWithResult(RESULT_CANCELED)
            return
        }

        player.addObserver(this)
        player.initialize(filesDir.path, cacheDir.path)

        if (intent.action == Intent.ACTION_VIEW) {
            parseIntentExtras(intent.extras)
        }

        player.playFile(filepath)

        mediaSession = initMediaSession()
        updateMediaSession()
        with (BackgroundPlaybackService) {
            mediaToken = mediaSession?.sessionToken
            thumbnailChanged = {
                updateMediaSession()
            }
        }

        updateScreenBrightness()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioSessionId = audioManager!!.generateAudioSessionId()
        if (audioSessionId != AudioManager.ERROR)
            player.setAudioSessionId(audioSessionId)
        else
            Log.w(TAG, "AudioManager.generateAudioSessionId() returned error")

        volumeControlStream = STREAM_TYPE
    }

    private fun finishWithResult(code: Int, includeTimePos: Boolean = false) {
        // Refer to http://mpv-android.github.io/mpv-android/intent.html
        // FIXME: should track end-file events to accurately report OK vs CANCELED
        if (isFinishing) // only count first call
            return
        val result = Intent(RESULT_INTENT)
        result.data = if (intent.data?.scheme == "file") null else intent.data
        if (includeTimePos) {
            result.putExtra("position", psc.position.toInt())
            result.putExtra("duration", psc.duration.toInt())
        }
        setResult(code, result)
        finish()
    }

    override fun onDestroy() {
        Log.v(TAG, "Exiting.")

        // Suppress any further callbacks
        activityIsForeground = false

        if (becomingNoisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
        }

        with (BackgroundPlaybackService) {
            mediaToken = null
            thumbnailChanged = null
        }
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null

        audioFocusRequest?.let {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager!!, it)
        }
        audioFocusRequest = null

        // take the background service with us
        stopServiceRunnable.run()

        player.removeObserver(this)
        player.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        Log.v(TAG, "onNewIntent($intent)")
        super.onNewIntent(intent)
        setIntent(intent)

        val filepath = parsePathFromIntent(intent)
        if (filepath == null) {
            return
        }

        if (intent.action == Intent.ACTION_VIEW) {
            parseIntentExtras(intent.extras)
        }

        if (!activityIsForeground && didResumeBackgroundPlayback) {
            if (this.newIntentReplace) {
                MPVLib.command(arrayOf("loadfile", filepath, "replace"))
                showToast(getString(R.string.notice_file_play))
            } else {
                MPVLib.command(arrayOf("loadfile", filepath, "append"))
                showToast(getString(R.string.notice_file_appended))
            }
            moveTaskToBack(true)
        } else {
            MPVLib.command(arrayOf("loadfile", filepath))
            player.paused = false
            showControls()
        }
    }

    private fun updateAudioPresence() {
        val haveAudio = MPVLib.getPropertyBoolean("current-tracks/audio/selected")
        if (haveAudio == null) {
            // If we *don't know* if there's an active audio track then don't update to avoid
            // spurious UI changes. The property will become available again later.
            return
        }
        isPlayingAudio = (haveAudio && MPVLib.getPropertyBoolean("mute") != true)
    }

    private fun isPlayingAudioOnly(): Boolean {
        if (!isPlayingAudio)
            return false
        val image = MPVLib.getPropertyString("current-tracks/video/image")
        return image.isNullOrEmpty() || image == "yes"
    }

    private fun shouldBackground(): Boolean {
        if (isFinishing) // about to exit?
            return false
        return when (backgroundPlayMode) {
            "always" -> true
            "audio-only" -> isPlayingAudioOnly()
            else -> false // "never"
        }
    }

    override fun onPause() {
        saveAnimeProgress()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isInMultiWindowMode || isInPictureInPictureMode) {
                Log.v(TAG, "Going into multi-window mode")
                super.onPause()
                return
            }
        }

        onPauseImpl()
    }

    private fun tryStartForegroundService(intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, e)
                return false
            }
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
        return true
    }

    private fun onPauseImpl() {
        val shouldBackground = shouldBackground()

        activityIsForeground = false
        eventUiHandler.removeCallbacksAndMessages(null)
        if (isFinishing) {
            savePosition()
            // tell mpv to shut down so that any other property changes or such are ignored,
            // preventing useless busywork
            MPVLib.command(arrayOf("stop"))
        } else if (!shouldBackground) {
            player.paused = true
            // clear any old thumbnails
            BackgroundPlaybackService.thumbnail = null
            updateMediaSession()
        }
        writeSettings()
        super.onPause()

        didResumeBackgroundPlayback = shouldBackground
        if (shouldBackground) {
            Log.v(TAG, "Resuming playback in background")
            stopServiceHandler.removeCallbacks(stopServiceRunnable)
            val serviceIntent = Intent(this, BackgroundPlaybackService::class.java)
            if (!tryStartForegroundService(serviceIntent)) {
                didResumeBackgroundPlayback = false
                player.paused = true
            }
        }
    }

    private fun readSettings() {
        // FIXME: settings should be in their own class completely
        val prefs = getDefaultSharedPreferences(applicationContext)
        val getString: (String, Int) -> String = { key, defaultRes ->
            prefs.getString(key, resources.getString(defaultRes))!!
        }

        gestures.syncSettings(prefs, resources)

        val statsMode = prefs.getString("stats_mode", "") ?: ""
        this.statsFPS = statsMode == "native_fps"
        this.statsLuaMode = if (statsMode.startsWith("lua"))
            statsMode.removePrefix("lua").toInt()
        else
            0
        this.backgroundPlayMode = getString("background_play", R.string.pref_background_play_default)
        this.noUIPauseMode = getString("no_ui_pause", R.string.pref_no_ui_pause_default)
        this.shouldSavePosition = prefs.getBoolean("save_position", false)
        if (this.autoRotationMode != "manual") // don't reset
            this.autoRotationMode = getString("auto_rotation", R.string.pref_auto_rotation_default)
        this.controlsAtBottom = prefs.getBoolean("bottom_controls", true)
        // CineVerse always shows what is playing.
        this.showMediaTitle = true
        this.useTimeRemaining = prefs.getBoolean("use_time_remaining", false)
        this.rememberBrightness = prefs.getBoolean("remember_brightness", false)
        this.lastScreenBrightness = if (this.rememberBrightness)
            prefs.getInt("last_screen_brightness", -1)
        else
            -1
        this.ignoreAudioFocus = prefs.getBoolean("ignore_audio_focus", false)
        this.playlistExitWarning = prefs.getBoolean("playlist_exit_warning", true)
        this.newIntentReplace = prefs.getBoolean("new_intent_replace", false)
        this.smoothSeekGesture = prefs.getBoolean("seek_gesture_smooth", false)
    }

    private fun writeSettings() {
        val prefs = getDefaultSharedPreferences(applicationContext)

        with (prefs.edit()) {
            putBoolean("use_time_remaining", useTimeRemaining)
            if (rememberBrightness)
                putInt("last_screen_brightness", lastScreenBrightness)
            else
                remove("last_screen_brightness")
            commit()
        }
    }

    override fun onStart() {
        super.onStart()
        activityIsStopped = false
    }

    override fun onStop() {
        super.onStop()
        activityIsStopped = true
    }

    override fun onResume() {
        // If we weren't actually in the background (e.g. multi window mode), don't reinitialize stuff
        if (activityIsForeground) {
            super.onResume()
            return
        }

        if (lockedUI) { // precaution
            Log.w(TAG, "resumed with locked UI, unlocking")
            unlockUI()
        }

        // Init controls to be hidden and view fullscreen
        hideControls()
        readSettings()

        activityIsForeground = true
        // stop background service with a delay
        stopServiceHandler.removeCallbacks(stopServiceRunnable)
        stopServiceHandler.postDelayed(stopServiceRunnable, 1000L)

        refreshUi()

        super.onResume()
    }

    private fun savePosition() {
        if (!shouldSavePosition)
            return
        if (MPVLib.getPropertyBoolean("eof-reached") ?: true) {
            Log.d(TAG, "player indicates EOF, not saving watch-later config")
            return
        }
        MPVLib.command(arrayOf("write-watch-later-config"))
    }

    /**
     * Writes the position of the current anime episode so the browser UI can
     * offer "Continue watching". Only runs for players started with the
     * anime_track extra, so normal mpv usage is untouched.
     */
    private fun saveAnimeProgress() {
        val track = animeTrackId ?: return
        val position = MPVLib.getPropertyDouble("time-pos") ?: return
        val duration = MPVLib.getPropertyDouble("duration") ?: return
        if (duration <= 0.0 || position < 0.0)
            return

        val json = JSONObject()
            .put("pos", (position * 1000).toLong())
            .put("dur", (duration * 1000).toLong())
            .put("at", System.currentTimeMillis())

        getSharedPreferences("anime_progress", MODE_PRIVATE)
            .edit()
            .putString(track, json.toString())
            .apply()
        Log.v(TAG, "Saved anime progress for $track: ${position.toInt()}s / ${duration.toInt()}s")
    }

    /**
     * Requests or abandons audio focus and noisy receiver depending on the playback state.
     * @warning Call from event thread, not UI thread
     */
    private fun handleAudioFocus() {
        if ((psc.pause && !psc.cachePause) || !isPlayingAudio) {
            if (becomingNoisyReceiverRegistered)
                unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
            // TODO: could abandon audio focus after a timeout
        } else {
            if (!becomingNoisyReceiverRegistered)
                registerReceiver(
                    becomingNoisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                )
            becomingNoisyReceiverRegistered = true
            // (re-)request audio focus
            // Note that this will actually request focus every time the user unpauses, refer to discussion in #1066
            if (requestAudioFocus()) {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN, "request")
            } else {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, "request")
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        val req = audioFocusRequest ?:
            with(AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)) {
            setAudioAttributes(with(AudioAttributesCompat.Builder()) {
                // N.B.: libmpv may use different values in ao_audiotrack, but here we always pretend to be music.
                setUsage(AudioAttributesCompat.USAGE_MEDIA)
                setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                build()
            })
            setOnAudioFocusChangeListener {
                onAudioFocusChange(it, "callback")
            }
            build()
        }
        val res = AudioManagerCompat.requestAudioFocus(manager, req)
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = req
            return true
        }
        return false
    }

    // This handles both "real" audio focus changes by the callbacks, which aren't
    // really used anymore after Android 12 (except for AUDIOFOCUS_LOSS),
    // as well as actions equivalent to a focus change that we make up ourselves.
    private fun onAudioFocusChange(type: Int, source: String) {
        Log.v(TAG, "Audio focus changed: $type ($source)")
        if (ignoreAudioFocus || isFinishing)
            return
        when (type) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // loss can occur in addition to ducking, so remember the old callback
                val oldRestore = audioFocusRestore
                val wasPlayerPaused = player.paused ?: false
                player.paused = true
                audioFocusRestore = {
                    oldRestore()
                    if (!wasPlayerPaused) player.paused = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                MPVLib.command(arrayOf("multiply", "volume", AUDIO_FOCUS_DUCKING.toString()))
                audioFocusRestore = {
                    val inv = 1f / AUDIO_FOCUS_DUCKING
                    MPVLib.command(arrayOf("multiply", "volume", inv.toString()))
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusRestore()
                audioFocusRestore = {}
            }
        }
    }

    // UI

    /** dpad navigation */
    private var btnSelected = -1

    private var mightWantToToggleControls = false

    /** true if we're actually outputting any audio (includes the mute state, but not pausing) */
    private var isPlayingAudio = false

    private var useAudioUI = false

    private var lockedUI = false

    private fun pauseForDialog(): StateRestoreCallback {
        val useKeepOpen = when (noUIPauseMode) {
            "always" -> true
            "audio-only" -> isPlayingAudioOnly()
            else -> false // "never"
        }
        if (useKeepOpen) {
            // don't pause but set keep-open so mpv doesn't exit while the user is doing stuff
            val oldValue = MPVLib.getPropertyString("keep-open")
            MPVLib.setPropertyBoolean("keep-open", true)
            return {
                oldValue?.also { MPVLib.setPropertyString("keep-open", it) }
            }
        }

        // Pause playback during UI dialogs
        val wasPlayerPaused = player.paused ?: true
        player.paused = true
        return {
            if (!wasPlayerPaused)
                player.paused = false
        }
    }

    private fun updateStats() {
        if (!statsFPS)
            return
        binding.statsTextView.text = getString(R.string.ui_fps, player.estimatedVfFps)
    }

    private fun controlsShouldBeVisible(): Boolean {
        if (lockedUI)
            return false
        return useAudioUI || btnSelected != -1 || userIsOperatingSeekbar
    }

    /** Make controls visible, also controls the timeout until they fade. */
    private fun showControls() {
        if (lockedUI) {
            Log.w(TAG, "cannot show UI in locked mode")
            return
        }

        // remove all callbacks that were to be run for fading
        fadeHandler.removeCallbacks(fadeRunnable)
        binding.controls.animate().cancel()
        binding.topControls.animate().cancel()
        binding.bottomGradient.animate().cancel()

        // reset controls alpha to be visible
        binding.controls.alpha = 1f
        binding.topControls.alpha = 1f
        binding.bottomGradient.alpha = 1f

        if (binding.controls.visibility != View.VISIBLE) {
            binding.controls.visibility = View.VISIBLE
            binding.topControls.visibility = View.VISIBLE
            binding.bottomGradient.visibility = View.VISIBLE

            if (this.statsFPS) {
                updateStats()
                binding.statsTextView.visibility = View.VISIBLE
            }

            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.show(WindowInsetsCompat.Type.navigationBars())
        }

        // add a new callback to hide the controls once again
        if (!controlsShouldBeVisible())
            fadeHandler.postDelayed(fadeRunnable, CONTROLS_DISPLAY_TIMEOUT)
    }

    /** Hide controls instantly */
    fun hideControls() {
        if (controlsShouldBeVisible())
            return
        // use GONE here instead of INVISIBLE (which makes more sense) because of Android bug with surface views
        // see http://stackoverflow.com/a/12655713/2606891
        binding.controls.visibility = View.GONE
        binding.topControls.visibility = View.GONE
        binding.bottomGradient.visibility = View.GONE
        binding.statsTextView.visibility = View.GONE

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Start fading out the controls */
    private fun hideControlsFade() {
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.post(fadeRunnable)
    }

    /**
     * Toggle visibility of controls (if allowed)
     * @return future visibility state
     */
    private fun toggleControls(): Boolean {
        if (lockedUI)
            return false
        if (controlsShouldBeVisible())
            return true
        return if (binding.controls.visibility == View.VISIBLE && !fadeRunnable.hasStarted) {
            hideControlsFade()
            false
        } else {
            showControls()
            true
        }
    }

    private fun showUnlockControls() {
        fadeHandler.removeCallbacks(fadeRunnable2)
        binding.unlockBtn.animate().setListener(null).cancel()

        binding.unlockBtn.alpha = 1f
        binding.unlockBtn.visibility = View.VISIBLE

        fadeHandler.postDelayed(fadeRunnable2, CONTROLS_DISPLAY_TIMEOUT)
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (lockedUI) {
            showUnlockControls()
            return super.dispatchKeyEvent(ev)
        }

        // try built-in event handler first, forward all other events to libmpv
        val handled = interceptDpad(ev) ||
                (ev.action == KeyEvent.ACTION_DOWN && interceptKeyDown(ev)) ||
                player.onKey(ev)
        if (handled) {
            return true
        }
        return super.dispatchKeyEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        if (lockedUI)
            return super.dispatchGenericMotionEvent(ev)

        if (ev != null && ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (player.onPointerEvent(ev))
                return true
            // keep controls visible when mouse moves
            if (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE)
                showControls()
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (lockedUI) {
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_DOWN)
                showUnlockControls()
            return super.dispatchTouchEvent(ev)
        }

        if (super.dispatchTouchEvent(ev)) {
            // reset delay if the event has been handled
            // ideally we'd want to know if the event was delivered to controls, but we can't
            if (binding.controls.visibility == View.VISIBLE && !fadeRunnable.hasStarted)
                showControls()
            if (ev.action == MotionEvent.ACTION_UP)
                return true
        }
        if (ev.action == MotionEvent.ACTION_DOWN)
            mightWantToToggleControls = true
        if (ev.action == MotionEvent.ACTION_UP && mightWantToToggleControls) {
            toggleControls()
        }
        return true
    }

    /**
     * Returns views eligible for dpad button navigation
     */
    private fun dpadButtons(): Sequence<View> {
        val groups = arrayOf(binding.controlsTopRow, binding.controlsBottomRow, binding.topControls)
        return sequence {
            for (g in groups) {
                for (i in 0 until g.childCount) {
                    val view = g.getChildAt(i)
                    if (view.isEnabled && view.isVisible && view.isFocusable)
                        yield(view)
                }
            }
        }
    }

    private fun interceptDpad(ev: KeyEvent): Boolean {
        if (btnSelected == -1) { // UP and DOWN are always grabbed and overridden
            when (ev.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (ev.action == KeyEvent.ACTION_DOWN) { // activate dpad navigation
                        btnSelected = 0
                        updateSelectedDpadButton()
                        showControls()
                    }
                    return true
                }
            }
            return false
        }

        // this runs when dpad navigation is active:
        when (ev.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (ev.action == KeyEvent.ACTION_DOWN) { // deactivate dpad navigation
                    btnSelected = -1
                    updateSelectedDpadButton()
                    hideControlsFade()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    btnSelected = (btnSelected + 1) % dpadButtons().count()
                    updateSelectedDpadButton()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    val count = dpadButtons().count()
                    btnSelected = (count + btnSelected - 1) % count
                    updateSelectedDpadButton()
                }
                return true
            }
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (ev.action == KeyEvent.ACTION_UP) {
                    val view = dpadButtons().elementAtOrNull(btnSelected)
                    // 500ms appears to be the standard
                    if (ev.eventTime - ev.downTime > 500L)
                        view?.performLongClick()
                    else
                        view?.performClick()
                }
                return true
            }
        }
        return false
    }

    private fun updateSelectedDpadButton() {
        val colorFocused = ContextCompat.getColor(this, R.color.tint_btn_bg_focused)
        val colorNoFocus = ContextCompat.getColor(this, R.color.tint_btn_bg_nofocus)

        dpadButtons().forEachIndexed { i, child ->
            if (i == btnSelected)
                child.setBackgroundColor(colorFocused)
            else
                child.setBackgroundColor(colorNoFocus)
        }
    }

    private fun interceptKeyDown(event: KeyEvent): Boolean {
        // intercept some keys to provide functionality "native" to
        // mpv-android even if libmpv already implements these
        var unhandled = 0

        when (event.unicodeChar.toChar()) {
            // (overrides a default binding)
            'j' -> cycleSub()
            '#' -> cycleAudio()

            else -> unhandled++
        }
        // Note: dpad center is bound according to how Android TV apps should generally behave,
        // see <https://developer.android.com/docs/quality-guidelines/tv-app-quality>.
        // Due to implementation inconsistencies enter and numpad enter need to perform the same
        // function (issue #963).
        when (event.keyCode) {
            // (no default binding)
            KeyEvent.KEYCODE_CAPTIONS -> cycleSub()
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> cycleAudio()
            KeyEvent.KEYCODE_INFO -> toggleControls()
            KeyEvent.KEYCODE_MENU -> openTopMenu()
            KeyEvent.KEYCODE_GUIDE -> openTopMenu()
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> player.cyclePause()

            // (overrides a default binding)
            KeyEvent.KEYCODE_ENTER -> player.cyclePause()

            else -> unhandled++
        }

        return unhandled < 2
    }

    private fun onBackPressedImpl() {
        if (lockedUI)
            return showUnlockControls()

        val notYetPlayed = psc.playlistCount - psc.playlistPos - 1
        if (notYetPlayed <= 0 || !playlistExitWarning) {
            finishWithResult(RESULT_OK, true)
            return
        }

        val restore = pauseForDialog()
        with (AlertDialog.Builder(this)) {
            setMessage(getString(R.string.exit_warning_playlist, notYetPlayed))
            setPositiveButton(R.string.dialog_yes) { dialog, _ ->
                dialog.dismiss()
                finishWithResult(RESULT_OK, true)
            }
            setNegativeButton(R.string.dialog_no) { dialog, _ ->
                dialog.dismiss()
                restore()
            }
            create().show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = windowManager.currentWindowMetrics
            gestures.setMetrics(wm.bounds.width().toFloat(), wm.bounds.height().toFloat())
        } else @Suppress("DEPRECATION") {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            gestures.setMetrics(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        }

        // Adjust control margins
        binding.controls.updateLayoutParams<MarginLayoutParams> {
            bottomMargin = if (!controlsAtBottom) {
                Utils.convertDp(this@MPVActivity, 60f)
            } else {
                0
            }
            leftMargin = if (!controlsAtBottom) {
                Utils.convertDp(this@MPVActivity, if (isLandscape) 60f else 24f)
            } else {
                0
            }
            rightMargin = leftMargin
        }
    }

    private fun onPiPModeChangedImpl(state: Boolean) {
        Log.v(TAG, "onPiPModeChanged($state)")
        if (state) {
            lockedUI = true
            hideControls()
            return
        }

        unlockUI()
        // For whatever stupid reason Android provides no good detection for when PiP is exited
        // so we have to do this shit <https://stackoverflow.com/questions/43174507/#answer-56127742>
        // If we don't exit the activity here it will stick around and not be retrievable from the
        // recents screen, or react to onNewIntent().
        if (activityIsStopped) {
            // Note: On Android 12 or older there's another bug with this: the result will not
            // be delivered to the calling activity and is instead instantly returned the next
            // time, which makes it looks like the file picker is broken.
            finishWithResult(RESULT_OK, true)
        }
    }

    private fun playlistPrev() = MPVLib.command(arrayOf("playlist-prev"))
    private fun playlistNext() = MPVLib.command(arrayOf("playlist-next"))

    private fun showToast(msg: String, cancel: Boolean = false) {
        if (cancel)
            toast?.cancel()
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0)
            show()
        }
    }

    // Intent/Uri parsing

    private fun parsePathFromIntent(intent: Intent): String? {
        fun safeResolveUri(u: Uri?): String? {
            return if (u != null) resolveUri(u) else null
        }

        // 1. Try intent.data
        intent.data?.let { uri ->
            safeResolveUri(uri)?.let { return it }
        }

        // 2. Try intent.dataString
        intent.dataString?.let { str ->
            if (str.isNotBlank()) {
                val uri = Uri.parse(str.trim())
                safeResolveUri(uri)?.let { return it }
            }
        }

        // 3. Handle ACTION_SEND / ACTION_SEND_MULTIPLE
        if (intent.action == Intent.ACTION_SEND) {
            var parsed = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (parsed == null) {
                parsed = intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Uri.parse(it.trim()) }
            }
            safeResolveUri(parsed)?.let { return it }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (!uris.isNullOrEmpty()) {
                val paths = uris.mapNotNull { safeResolveUri(it) }
                if (paths.size == 1) {
                    return paths[0]
                } else if (paths.isNotEmpty()) {
                    val memoryUri = Utils.makeMemoryPlaylist(paths)
                    Log.v(TAG, "Created memory playlist URI (${paths.size})")
                    return memoryUri
                }
            }
        }

        // 4. Try string extras: "filepath", "path", "url", "uri", "input", "file"
        val extraKeys = arrayOf("filepath", "path", "url", "uri", "input", "file")
        for (key in extraKeys) {
            intent.getStringExtra(key)?.let { str ->
                if (str.isNotBlank()) {
                    val trimmed = str.trim()
                    if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("file://") || trimmed.startsWith("content://")) {
                        val uri = Uri.parse(trimmed)
                        safeResolveUri(uri)?.let { return it }
                    } else {
                        return trimmed
                    }
                }
            }
        }

        return null
    }

    private fun resolveUri(data: Uri): String? {
        val scheme = data.scheme?.lowercase() ?: ""
        val filepath = when (scheme) {
            "file" -> data.path
            "content" -> translateContentUri(data)
            "data" -> "data://${data.schemeSpecificPart}"
            "http", "https", "rtmp", "rtmps", "rtp", "rtsp", "mms", "mmst", "mmsh",
            "tcp", "udp", "lavf", "ftp"
            -> data.toString()
            else -> {
                val str = data.toString()
                if (str.startsWith("http://", ignoreCase = true) || str.startsWith("https://", ignoreCase = true) || str.startsWith("file://", ignoreCase = true)) {
                    str
                } else null
            }
        }

        if (filepath == null)
            Log.e(TAG, "unknown scheme: ${data.scheme} for URI: $data")
        return filepath
    }

    private fun translateContentUri(uri: Uri): String {
        val resolver = applicationContext.contentResolver
        Log.v(TAG, "Resolving content URI: $uri")
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // See if we can skip the indirection and read the real file directly
                val path = Utils.findRealPath(pfd.fd)
                if (path != null) {
                    Log.v(TAG, "Found real file path: $path")
                    return path
                }
            }
        } catch(e: Exception) {
            Log.e(TAG, "Failed to open content fd: $e")
        }

        // Otherwise, just let mpv open the content URI directly via ffmpeg
        return uri.toString()
    }

    private fun parseIntentExtras(extras: Bundle?) {
        onloadCommands.clear()
        if (extras == null)
            return

        animeTrackId = extras.getString("anime_track")?.takeIf { it.isNotBlank() }

        if (extras.containsKey("intro_imdb")) loadIntroMarks()

        val headerList = mutableListOf<String>()
        var userAgent: String? = null
        var referrer: String? = null

        fun addHeaderItem(item: Any?) {
            when (item) {
                is String -> {
                    val lines = item.split("\r\n", "\n", "; ")
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isNotBlank()) {
                            if (trimmed.startsWith("User-Agent:", ignoreCase = true)) {
                                userAgent = trimmed.substringAfter(":", "").trim()
                            } else if (trimmed.startsWith("Referer:", ignoreCase = true) || trimmed.startsWith("Referrer:", ignoreCase = true)) {
                                referrer = trimmed.substringAfter(":", "").trim()
                            }
                            headerList.add(trimmed)
                        }
                    }
                }
                is Array<*> -> item.forEach { addHeaderItem(it) }
                is Iterable<*> -> item.forEach { addHeaderItem(it) }
            }
        }

        val headerKeys = arrayOf("headers", "http-header-fields", "http_header_fields", "Headers", "Header", "http_headers")
        for (k in headerKeys) {
            if (extras.containsKey(k)) {
                addHeaderItem(extras.get(k))
            }
        }

        val uaKeys = arrayOf("User-Agent", "user_agent", "user-agent", "User_Agent")
        for (k in uaKeys) {
            extras.getString(k)?.let {
                if (it.isNotBlank()) {
                    userAgent = it.trim()
                    headerList.add("User-Agent: ${it.trim()}")
                }
            }
        }

        val refKeys = arrayOf("Referer", "referer", "referrer", "Referrer")
        for (k in refKeys) {
            extras.getString(k)?.let {
                if (it.isNotBlank()) {
                    referrer = it.trim()
                    headerList.add("Referer: ${it.trim()}")
                }
            }
        }

        if (!userAgent.isNullOrEmpty()) {
            Log.v(TAG, "Setting User-Agent: $userAgent")
            MPVLib.setOptionString("user-agent", userAgent!!)
        }
        if (!referrer.isNullOrEmpty()) {
            Log.v(TAG, "Setting Referrer: $referrer")
            MPVLib.setOptionString("referrer", referrer!!)
        }
        if (headerList.isNotEmpty()) {
            val headersStr = headerList.distinct().joinToString("\r\n")
            Log.v(TAG, "Setting HTTP headers from intent:\n$headersStr")
            MPVLib.setOptionString("http-header-fields", headersStr)
        }

        // Optional tuning from the CineVerse resolver (fast-start HLS, caching).
        extras.getString("mpv_opts")?.takeIf { it.isNotBlank() }?.let { raw ->
            raw.split("\n", ";")
                .map { it.trim() }
                .filter { it.contains('=') }
                .forEach { line ->
                    val key = line.substringBefore('=').trim()
                    val value = line.substringAfter('=').trim()
                    if (key in ALLOWED_INTENT_OPTIONS && value.isNotEmpty()) {
                        Log.v(TAG, "Setting option from intent: $key=$value")
                        MPVLib.setOptionString(key, value)
                    }
                }
        }

        val decodeMode = try {
            extras.getByte("decode_mode", 0.toByte()).toInt()
        } catch (e: Exception) {
            extras.getInt("decode_mode", 0)
        }
        if (decodeMode == 2) {
            MPVLib.setOptionString("hwdec", "no")
        }

        val pos = extras.getInt("position", 0)
        if (pos > 0) {
            MPVLib.setOptionString("start", "${pos / 1000f}")
        }

        val titleKeys = arrayOf("title", "force-media-title", "media_title", "name")
        for (k in titleKeys) {
            extras.getString(k)?.let {
                if (it.isNotBlank()) {
                    MPVLib.setOptionString("force-media-title", it.trim())
                    break
                }
            }
        }

        if (extras.containsKey("subs")) {
            val subList = Utils.getParcelableArray<Uri>(extras, "subs")
            val subsToEnable = Utils.getParcelableArray<Uri>(extras, "subs.enable")

            for (suburi in subList) {
                val subfile = resolveUri(suburi) ?: continue
                val flag = if (subsToEnable.any { it == suburi }) "select" else "auto"

                Log.v(TAG, "Adding subtitles from intent extras: $subfile")
                onloadCommands.add(arrayOf("sub-add", subfile, flag))
            }
        }
    }

    // UI (Part 2)

    data class TrackData(val trackId: Int, val trackType: String)
    private fun trackSwitchNotification(f: () -> TrackData) {
        val (track_id, track_type) = f()
        val trackPrefix = when (track_type) {
            "audio" -> getString(R.string.track_audio)
            "sub"   -> getString(R.string.track_subs)
            "video" -> "Video"
            else    -> "???"
        }

        val msg = if (track_id == -1) {
            "$trackPrefix ${getString(R.string.track_off)}"
        } else {
            val trackName = player.tracks[track_type]?.firstOrNull{ it.mpvId == track_id }?.name ?: "???"
            "$trackPrefix $trackName"
        }
        showToast(msg, true)
    }

    private fun cycleAudio() = trackSwitchNotification {
        player.cycleAudio(); TrackData(player.aid, "audio")
    }
    private fun cycleSub() = trackSwitchNotification {
        player.cycleSub(); TrackData(player.sid, "sub")
    }

    private fun selectTrack(type: String, get: () -> Int, set: (Int) -> Unit) {
        val tracks = player.tracks.getValue(type)
        val selectedMpvId = get()
        val selectedIndex = tracks.indexOfFirst { it.mpvId == selectedMpvId }
        val restore = pauseForDialog()

        with (AlertDialog.Builder(this)) {
            setSingleChoiceItems(tracks.map { it.name }.toTypedArray(), selectedIndex) { dialog, item ->
                val trackId = tracks[item].mpvId

                set(trackId)
                dialog.dismiss()
                trackSwitchNotification { TrackData(trackId, type) }
            }
            setOnDismissListener { restore() }
            create().show()
        }
    }

    private fun pickAudio() = selectTrack("audio", { player.aid }, { player.aid = it })

    private fun pickSub() {
        val restore = pauseForDialog()
        val impl = SubTrackDialog(player)
        lateinit var dialog: AlertDialog
        impl.listener = { it, secondary ->
            if (secondary)
                player.secondarySid = it.mpvId
            else
                player.sid = it.mpvId
            dialog.dismiss()
            trackSwitchNotification { TrackData(it.mpvId, SubTrackDialog.TRACK_TYPE) }
        }

        dialog = with (AlertDialog.Builder(this)) {
            val inflater = LayoutInflater.from(context)
            setView(impl.buildView(inflater))
            setOnDismissListener { restore() }
            create()
        }
        dialog.show()
    }

    private fun openPlaylistMenu(restore: StateRestoreCallback) {
        val impl = PlaylistDialog(player)
        lateinit var dialog: AlertDialog

        impl.listeners = object : PlaylistDialog.Listeners {
            private fun openFilePicker(skip: Int) {
                openFilePickerFor(RCODE_LOAD_FILE, "", skip) { result, data ->
                    if (result == RESULT_OK) {
                        val path = data!!.getStringExtra("path")!!
                        MPVLib.command(arrayOf("loadfile", path, "append"))
                        impl.refresh()
                    }
                }
            }
            override fun pickFile() = openFilePicker(FilePickerActivity.FILE_PICKER)

            override fun openUrl() {
                val helper = Utils.OpenUrlDialog(this@MPVActivity)
                with (helper) {
                    builder.setPositiveButton(R.string.dialog_ok) { _, _ ->
                        MPVLib.command(arrayOf("loadfile", helper.text, "append"))
                        impl.refresh()
                    }
                    builder.setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
                    create().show()
                }
            }

            override fun onItemPicked(item: MPVView.PlaylistItem) {
                MPVLib.setPropertyInt("playlist-pos", item.index)
                dialog.dismiss()
            }
        }

        dialog = with (AlertDialog.Builder(this)) {
            val inflater = LayoutInflater.from(context)
            setView(impl.buildView(inflater))
            setOnDismissListener { restore() }
            create()
        }
        dialog.show()
    }

    private fun pickDecoder() {
        val restore = pauseForDialog()

        val items = mutableListOf(
            Pair("HW (mediacodec-copy)", "mediacodec-copy"),
            Pair("SW", "no")
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            items.add(0, Pair("HW+ (mediacodec)", "mediacodec"))
        val hwdecActive = player.hwdecActive
        val selectedIndex = items.indexOfFirst { it.second == hwdecActive }
        with (AlertDialog.Builder(this)) {
            setSingleChoiceItems(items.map { it.first }.toTypedArray(), selectedIndex ) { dialog, idx ->
                MPVLib.setPropertyString("hwdec", items[idx].second)
                dialog.dismiss()
            }
            setOnDismissListener { restore() }
            create().show()
        }
    }

    private fun cycleSpeed() {
        player.cycleSpeed()
    }

    private fun pickSpeed() {
        // TODO: replace this with SliderPickerDialog
        val picker = SpeedPickerDialog()

        val restore = pauseForDialog()
        genericPickerDialog(picker, R.string.title_speed_dialog, "speed") {
            restore()
        }
    }

    private fun goIntoPiP() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
            return
        updatePiPParams(true)
        enterPictureInPictureMode()
    }

    private fun lockUI() {
        lockedUI = true
        hideControlsFade()
    }

    private fun unlockUI() {
        binding.unlockBtn.visibility = View.GONE
        lockedUI = false
        showControls()
    }

    data class MenuItem(@IdRes val idRes: Int, val handler: () -> Boolean)
    private fun genericMenu(
            @LayoutRes layoutRes: Int, buttons: List<MenuItem>, hiddenButtons: Set<Int>,
            restoreState: StateRestoreCallback) {
        lateinit var dialog: AlertDialog

        val builder = AlertDialog.Builder(this)
        val dialogView = LayoutInflater.from(builder.context).inflate(layoutRes, null)

        for (button in buttons) {
            val buttonView = dialogView.findViewById<Button>(button.idRes)
            buttonView.setOnClickListener {
                val ret = button.handler()
                if (ret) // restore state immediately
                    restoreState()
                dialog.dismiss()
            }
        }

        hiddenButtons.forEach { dialogView.findViewById<View>(it).isVisible = false }

        if (Utils.visibleChildren(dialogView) == 0) {
            Log.w(TAG, "Not showing menu because it would be empty")
            restoreState()
            return
        }

        Utils.handleInsetsAsPadding(dialogView)

        with (builder) {
            setView(dialogView)
            setOnCancelListener { restoreState() }
            dialog = create()
        }
        dialog.show()
    }

    private fun openTopMenu() {
        val restoreState = pauseForDialog()

        fun addExternalThing(cmd: String, result: Int, data: Intent?) {
            if (result != RESULT_OK)
                return
            // file picker may return a content URI or a bare file path
            val path = data!!.getStringExtra("path")!!
            val path2 = if (path.startsWith("content://"))
                translateContentUri(Uri.parse(path))
            else
                path
            MPVLib.command(arrayOf(cmd, path2, "cached"))
        }

        /******/
        val hiddenButtons = mutableSetOf<Int>()
        val buttons: MutableList<MenuItem> = mutableListOf(
                MenuItem(R.id.audioBtn) {
                    openFilePickerFor(RCODE_EXTERNAL_AUDIO, R.string.open_external_audio) { result, data ->
                        addExternalThing("audio-add", result, data)
                        restoreState()
                    }; false
                },
                MenuItem(R.id.subBtn) {
                    openFilePickerFor(RCODE_EXTERNAL_SUB, R.string.open_external_sub) { result, data ->
                        addExternalThing("sub-add", result, data)
                        restoreState()
                    }; false
                },
                MenuItem(R.id.playlistBtn) {
                    openPlaylistMenu(restoreState); false
                },
                MenuItem(R.id.backgroundBtn) {
                    // restoring state may (un)pause so do that first
                    restoreState()
                    backgroundPlayMode = "always"
                    player.paused = false
                    moveTaskToBack(true)
                    false
                },
                MenuItem(R.id.chapterBtn) {
                    val chapters = player.loadChapters()
                    if (chapters.isEmpty())
                        return@MenuItem true
                    val chapterArray = chapters.map {
                        val timecode = Utils.prettyTime(it.time.roundToInt())
                        if (!it.title.isNullOrEmpty())
                            getString(R.string.ui_chapter, it.title, timecode)
                        else
                            getString(R.string.ui_chapter_fallback, it.index+1, timecode)
                    }.toTypedArray()
                    val selectedIndex = MPVLib.getPropertyInt("chapter") ?: 0
                    with (AlertDialog.Builder(this)) {
                        setSingleChoiceItems(chapterArray, selectedIndex) { dialog, item ->
                            MPVLib.setPropertyInt("chapter", chapters[item].index)
                            dialog.dismiss()
                        }
                        setOnDismissListener { restoreState() }
                        create().show()
                    }; false
                },
                MenuItem(R.id.chapterPrev) {
                    MPVLib.command(arrayOf("add", "chapter", "-1")); true
                },
                MenuItem(R.id.chapterNext) {
                    MPVLib.command(arrayOf("add", "chapter", "1")); true
                },
                MenuItem(R.id.advancedBtn) { openAdvancedMenu(restoreState); false },
                MenuItem(R.id.orientationBtn) {
                    autoRotationMode = "manual"
                    cycleOrientation()
                    true
                }
        )

        if (!isPlayingAudio)
            hiddenButtons.add(R.id.backgroundBtn)
        if ((MPVLib.getPropertyInt("chapter-list/count") ?: 0) == 0)
            hiddenButtons.add(R.id.rowChapter)
        /******/

        genericMenu(R.layout.dialog_top_menu, buttons, hiddenButtons, restoreState)
    }

    private fun genericPickerDialog(
        picker: PickerDialog, @StringRes titleRes: Int, property: String,
        restoreState: StateRestoreCallback
    ) {
        val dialog = with(AlertDialog.Builder(this)) {
            setTitle(titleRes)
            val inflater = LayoutInflater.from(context)
            setView(picker.buildView(inflater))
            setPositiveButton(R.string.dialog_ok) { _, _ ->
                picker.number?.let {
                    if (picker.isInteger())
                        MPVLib.setPropertyInt(property, it.toInt())
                    else
                        MPVLib.setPropertyDouble(property, it)
                }
            }
            setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
            setOnDismissListener { restoreState() }
            create()
        }

        picker.number = MPVLib.getPropertyDouble(property)
        dialog.show()
    }

    private fun openAdvancedMenu(restoreState: StateRestoreCallback) {
        /******/
        val hiddenButtons = mutableSetOf<Int>()
        val buttons: MutableList<MenuItem> = mutableListOf(
                MenuItem(R.id.subSeekPrev) {
                    MPVLib.command(arrayOf("sub-seek", "-1")); true
                },
                MenuItem(R.id.subSeekNext) {
                    MPVLib.command(arrayOf("sub-seek", "1")); true
                },
                MenuItem(R.id.statsBtn) {
                    MPVLib.command(arrayOf("script-binding", "stats/display-stats-toggle")); true
                },
                MenuItem(R.id.aspectBtn) {
                    val ratios = resources.getStringArray(R.array.aspect_ratios)
                    with (AlertDialog.Builder(this)) {
                        setItems(R.array.aspect_ratio_names) { dialog, item ->
                            if (ratios[item] == "panscan") {
                                MPVLib.setPropertyString("video-aspect-override", "-1")
                                MPVLib.setPropertyDouble("panscan", 1.0)
                            } else {
                                MPVLib.setPropertyString("video-aspect-override", ratios[item])
                                MPVLib.setPropertyDouble("panscan", 0.0)
                            }
                            dialog.dismiss()
                        }
                        setOnDismissListener { restoreState() }
                        create().show()
                    }; false
                },
        )

        val statsButtons = arrayOf(R.id.statsBtn1, R.id.statsBtn2, R.id.statsBtn3)
        for (i in 1..3) {
            buttons.add(MenuItem(statsButtons[i-1]) {
                MPVLib.command(arrayOf("script-binding", "stats/display-page-$i")); true
            })
        }

        // contrast, brightness and others get a -100 to 100 slider
        val basicIds = arrayOf(R.id.contrastBtn, R.id.brightnessBtn, R.id.gammaBtn, R.id.saturationBtn)
        val basicProps = arrayOf("contrast", "brightness", "gamma", "saturation")
        val basicTitles = arrayOf(R.string.contrast, R.string.video_brightness, R.string.gamma, R.string.saturation)
        basicIds.forEachIndexed { index, id ->
            buttons.add(MenuItem(id) {
                val slider = SliderPickerDialog(-100.0, 100.0, 1, R.string.format_fixed_number)
                genericPickerDialog(slider, basicTitles[index], basicProps[index], restoreState)
                false
            })
        }

        // audio / sub delay get a decimal picker
        buttons.add(MenuItem(R.id.audioDelayBtn) {
            val picker = DecimalPickerDialog(-600.0, 600.0)
            genericPickerDialog(picker, R.string.audio_delay, "audio-delay", restoreState)
            false
        })
        buttons.add(MenuItem(R.id.subDelayBtn) {
            val picker = SubDelayDialog(-600.0, 600.0)
            val dialog = with(AlertDialog.Builder(this)) {
                setTitle(R.string.sub_delay)
                val inflater = LayoutInflater.from(context)
                setView(picker.buildView(inflater))
                setPositiveButton(R.string.dialog_ok) { _, _ ->
                    picker.delay1?.let { player.subDelay = it }
                    picker.delay2?.let { player.secondarySubDelay = it }
                }
                setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
                setOnDismissListener { restoreState() }
                create()
            }

            picker.delay1 = player.subDelay ?: 0.0
            picker.delay2 = if (player.secondarySid != -1) player.secondarySubDelay else null
            dialog.show()
            false
        })

        if (player.vid == -1)
            hiddenButtons.addAll(arrayOf(R.id.rowVideo1, R.id.rowVideo2, R.id.aspectBtn))
        if (player.aid == -1 || player.vid == -1)
            hiddenButtons.add(R.id.audioDelayBtn)
        if (player.sid == -1)
            hiddenButtons.addAll(arrayOf(R.id.subDelayBtn, R.id.rowSubSeek))
        /******/

        genericMenu(R.layout.dialog_advanced_menu, buttons, hiddenButtons, restoreState)
    }

    /** Dark dialog matching the player menu panel. */
    private fun cineDialog(): AlertDialog.Builder = AlertDialog.Builder(this, R.style.CineDialog)

    private fun openModernMenu() {        val restoreState = pauseForDialog()
        
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_modern_menu, null)
        val searchEdit: android.widget.EditText = dialogView.findViewById(R.id.menuSearch)
        val recyclerView: androidx.recyclerview.widget.RecyclerView = dialogView.findViewById(R.id.menuRecyclerView)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setOnDismissListener { restoreState() }
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnShowListener { _ ->
            dialog.window?.let { w ->
                val dm = resources.displayMetrics
                w.setGravity(android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
                w.setLayout((360 * dm.density).toInt(), android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                w.setDimAmount(0.55f)
            }
        }
        
        val audioId = player.aid
        val audioName = player.tracks["audio"]?.find { it.mpvId == audioId }?.name ?: "Unknown"
        val subId = player.sid
        val subName = player.tracks["sub"]?.find { it.mpvId == subId }?.name ?: "Unknown"
        val chaptersCount = MPVLib.getPropertyInt("chapter-list/count") ?: 0
        val currentChapter = (MPVLib.getPropertyInt("chapter") ?: 0) + 1
        val playlistCount = psc.playlistCount
        val playlistPos = psc.playlistPos + 1
        val speed = String.format(java.util.Locale.US, "%.2fx", psc.speed)
        val subDelay = "${((player.subDelay ?: 0.0) * 1000).toInt()} ms"
        val aspectRatio = MPVLib.getPropertyString("video-aspect-override") ?: "Default"

        val menuItems = listOf(
            MenuAdapter.MenuRow("Audio", audioName) { dialog.dismiss(); pickAudio() },
            MenuAdapter.MenuRow("Subtitles", subName) { dialog.dismiss(); pickSub() },
            MenuAdapter.MenuRow("Quality", CinePrefs.qualityLabel(this)) {
                dialog.dismiss()
                showQualityDialog()
            },
            MenuAdapter.MenuRow(
                "Chapters",
                if (chaptersCount > 0) "$currentChapter / $chaptersCount" else "—",
            ) {
                dialog.dismiss()
                val chapters = player.loadChapters()
                if (chapters.isEmpty()) return@MenuRow
                val chapterArray = chapters.map {
                    val timecode = Utils.prettyTime(it.time.roundToInt())
                    if (!it.title.isNullOrEmpty())
                        getString(R.string.ui_chapter, it.title, timecode)
                    else
                        getString(R.string.ui_chapter_fallback, it.index+1, timecode)
                }.toTypedArray()
                val selectedIndex = MPVLib.getPropertyInt("chapter") ?: 0
                with (cineDialog()) {
                    setSingleChoiceItems(chapterArray, selectedIndex) { d, item ->
                        MPVLib.setPropertyInt("chapter", chapters[item].index)
                        d.dismiss()
                    }
                    setOnDismissListener { restoreState() }
                    create().show()
                }
            },
            MenuAdapter.MenuRow("Playlist", "$playlistPos / $playlistCount") { dialog.dismiss(); openPlaylistMenu(restoreState) },
            MenuAdapter.MenuRow("Speed", speed) { 
                dialog.dismiss()
                val speeds = arrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
                val speedNames = speeds.map { "${it}x" }.toTypedArray()
                val currentSpeed = psc.speed
                val selectedIndex = speeds.indexOf(currentSpeed.toDouble()).takeIf { it >= 0 } ?: 2
                with (cineDialog()) {
                    setSingleChoiceItems(speedNames, selectedIndex) { d, item ->
                        MPVLib.setPropertyDouble("speed", speeds[item])
                        d.dismiss()
                    }
                    setOnDismissListener { restoreState() }
                    create().show()
                }
            },
            MenuAdapter.MenuRow("Subtitle delay", subDelay) { 
                dialog.dismiss()
                val picker = SubDelayDialog(-600.0, 600.0)
                val d = with(cineDialog()) {
                    setTitle(R.string.sub_delay)
                    val inflater = LayoutInflater.from(context)
                    setView(picker.buildView(inflater))
                    setPositiveButton(R.string.dialog_ok) { _, _ ->
                        picker.delay1?.let { player.subDelay = it }
                        picker.delay2?.let { player.secondarySubDelay = it }
                    }
                    setNegativeButton(R.string.dialog_cancel) { d2, _ -> d2.cancel() }
                    setOnDismissListener { restoreState() }
                    create()
                }
                picker.delay1 = player.subDelay ?: 0.0
                picker.delay2 = if (player.secondarySid != -1) player.secondarySubDelay else null
                d.show()
            },
            MenuAdapter.MenuRow("Aspect ratio", aspectRatio) { 
                dialog.dismiss()
                val ratios = resources.getStringArray(R.array.aspect_ratios)
                with (cineDialog()) {
                    setItems(R.array.aspect_ratio_names) { d, item ->
                        if (ratios[item] == "panscan") {
                            MPVLib.setPropertyString("video-aspect-override", "-1")
                            MPVLib.setPropertyDouble("panscan", 1.0)
                        } else {
                            MPVLib.setPropertyString("video-aspect-override", ratios[item])
                            MPVLib.setPropertyDouble("panscan", 0.0)
                        }
                        d.dismiss()
                    }
                    setOnDismissListener { restoreState() }
                    create().show()
                }
            },
            MenuAdapter.MenuRow("Playback statistics", "") { 
                dialog.dismiss()
                MPVLib.command(arrayOf("script-binding", "stats/display-stats-toggle")) 
            },
            MenuAdapter.MenuRow("File information", "") { 
                dialog.dismiss()
                MPVLib.command(arrayOf("script-binding", "stats/display-page-1")) 
            },
            MenuAdapter.MenuRow("Settings", "") { 
                dialog.dismiss()
                val intent = android.content.Intent(this@MPVActivity, `is`.xyz.mpv.preferences.PreferenceActivity::class.java)
                startActivity(intent)
            },
            MenuAdapter.MenuRow("Help", "") { 
                dialog.dismiss()
                val intent = android.content.Intent(this@MPVActivity, `is`.xyz.mpv.preferences.AboutActivity::class.java)
                startActivity(intent)
            }
        )

        val adapter = MenuAdapter(menuItems)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        searchEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        dialog.show()
    }

    private fun showQualityDialog() {
        val options = CinePrefs.QUALITY_OPTIONS
        val names = CinePrefs.qualityNames()
        val current = options.indexOf(CinePrefs.quality(this)).coerceAtLeast(0)
        with(cineDialog()) {
            setTitle("Playback quality")
            setNegativeButton(android.R.string.cancel, null)
            setSingleChoiceItems(names, current) { d, item ->
                d.dismiss()
                if (options[item] != CinePrefs.quality(this@MPVActivity)) {
                    CinePrefs.setQuality(this@MPVActivity, options[item])
                    // Cached URLs point at the old rendition.
                    CineStreamCache.clear(this@MPVActivity)
                    restartWithNewQuality()
                }
            }
            create().show()
        }
    }

    /** Re-resolves the current episode at the new quality, keeping the position. */
    private fun restartWithNewQuality() {
        val imdb = intent.getStringExtra("stream_imdb")
        if (imdb.isNullOrBlank()) {
            showToast("Quality saved")
            return
        }
        val positionMs = ((MPVLib.getPropertyDouble("time-pos") ?: 0.0) * 1000).toInt()
        val label = intent.getStringExtra("stream_label") ?: ""
        val title = CineTitle(
            id = imdb,
            type = intent.getStringExtra("stream_type") ?: "movie",
            name = label,
            poster = null,
            background = null,
            logo = null,
            description = null,
            genres = emptyList(),
            rating = null,
            releaseInfo = null,
            runtime = null,
            cast = emptyList(),
            director = emptyList(),
            tmdbId = intent.getIntExtra("stream_tmdb", 0).takeIf { it > 0 },
        )
        val season = intent.getIntExtra("stream_season", 0).takeIf { it > 0 }
        val episode = intent.getIntExtra("stream_episode", 0).takeIf { it > 0 }
        val next = CineResolverActivity.intent(
            this,
            title,
            season,
            episode,
            label,
            startMs = positionMs,
        )
        startActivity(next)
        finish()
    }

    private fun cycleOrientation() {        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private var activityResultCallbacks: MutableMap<Int, ActivityResultCallback> = mutableMapOf()
    private fun openFilePickerFor(requestCode: Int, title: String, skip: Int?, callback: ActivityResultCallback) {
        val intent = Intent(this, FilePickerActivity::class.java)
        intent.putExtra("title", title)
        intent.putExtra("allow_document", true)
        skip?.let { intent.putExtra("skip", it) }
        // start file picker at directory of current file
        val path = MPVLib.getPropertyString("path") ?: ""
        if (path.startsWith('/'))
            intent.putExtra("default_path", File(path).parent)

        activityResultCallbacks[requestCode] = callback
        startActivityForResult(intent, requestCode)
    }
    private fun openFilePickerFor(requestCode: Int, @StringRes titleRes: Int, callback: ActivityResultCallback) {
        openFilePickerFor(requestCode, getString(titleRes), null, callback)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        activityResultCallbacks.remove(requestCode)?.invoke(resultCode, data)
    }

    private fun refreshUi() {
        // forces update of entire UI, used when resuming the activity
        updatePlaybackStatus(psc.pause)
        updatePlaybackPos(psc.positionSec)
        updatePlaybackDuration(psc.durationSec)
        updateAudioUI()
        updateOrientation()
        updateMetadataDisplay()
        updatePlaylistButtons()
        player.loadTracks()
    }

    private fun updateAudioUI() {
        val shouldUseAudioUI = isPlayingAudioOnly()
        if (shouldUseAudioUI == useAudioUI)
            return
        useAudioUI = shouldUseAudioUI
        Log.v(TAG, "Audio UI: $useAudioUI")

        if (useAudioUI) {
            updateMetadataDisplay()
            showControls()
        } else {
            // Show title only depending on settings
            if (showMediaTitle) {
                binding.fullTitleTextView.visibility = View.VISIBLE
                updateMetadataDisplay()
            } else {
                binding.fullTitleTextView.visibility = View.GONE
            }
            hideControls() // do NOT use fade runnable
        }

        // Visibility might have changed, so update
        updatePlaylistButtons()
    }

    private fun updateScreenBrightness() {
        if (lastScreenBrightness == -1)
            return
        val lp = window.attributes
        lp.screenBrightness = lastScreenBrightness / 100f
        window.attributes = lp
    }

    private fun updateMetadataDisplay() {
        if (!useAudioUI) {
            if (showMediaTitle)
                binding.fullTitleTextView.text = psc.meta.formatTitle()
        } else {
            val title = psc.meta.formatTitle()
            val minor = psc.meta.formatArtistAlbum()
            binding.fullTitleTextView.text = if (minor.isNullOrBlank()) title else "$title - $minor"
        }
    }

    private fun updatePlaybackPos(position: Int) {
        binding.playbackPositionTxt.text = Utils.prettyTime(position)
        if (useTimeRemaining) {
            val diff = psc.durationSec - position
            binding.playbackDurationTxt.text = if (diff <= 0)
                "-00:00"
            else
                Utils.prettyTime(-diff, true)
        }
        if (!userIsOperatingSeekbar)
            binding.playbackSeekbar.progress = position * SEEK_BAR_PRECISION

        // Note: do NOT add other update functions here just because this is called every second.
        // Use property observation instead.
        updateStats()
    }

    private fun updatePlaybackDuration(duration: Int) {
        if (!useTimeRemaining)
            binding.playbackDurationTxt.text = Utils.prettyTime(duration)
        if (!userIsOperatingSeekbar)
            binding.playbackSeekbar.max = duration * SEEK_BAR_PRECISION
    }

    private fun updatePlaybackStatus(paused: Boolean) {
        val r = if (paused) R.drawable.ic_play_arrow_black_24dp else R.drawable.ic_pause_black_24dp
        binding.playBtn.setImageResource(r)

        updatePiPParams()
        if (paused) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updatePlaylistButtons() {
        val plCount = psc.playlistCount
        val plPos = psc.playlistPos

        val g = ContextCompat.getColor(this, R.color.tint_disabled)
        val w = ContextCompat.getColor(this, R.color.tint_normal)
        binding.playlistPrevBtn.imageTintList = ColorStateList.valueOf(if (plPos == 0) g else w)
        binding.playlistNextBtn.imageTintList = ColorStateList.valueOf(if (plPos == plCount-1) g else w)
    }

    private fun updateSkipOpBtn() {
        // Series only: the intro/credits windows come from TheIntroDB for this
        // exact episode, so the button only shows while it is actually useful.
        val chapterCount = MPVLib.getPropertyInt("chapter-list/count") ?: 0
        val timeMs = ((MPVLib.getPropertyDouble("time-pos") ?: 0.0) * 1000).toLong()

        if (chapterCount > 0) {
            val chapter = MPVLib.getPropertyInt("chapter") ?: -1
            val title = if (chapter in 0 until chapterCount)
                MPVLib.getPropertyString("chapter-list/$chapter/title")?.lowercase().orEmpty()
            else ""
            when {
                title.contains("opening") || title.contains("intro") -> {
                    skipMode = "chapter"
                    binding.skipIntroBtn.setText(R.string.skip_intro)
                    binding.skipIntroBtn.visibility = View.VISIBLE
                }
                title.contains("ending") || title.contains("outro") -> {
                    skipMode = "chapter"
                    binding.skipIntroBtn.setText(R.string.skip_outro)
                    binding.skipIntroBtn.visibility = View.VISIBLE
                }
                else -> hideSkipBtn()
            }
            return
        }

        if (introEndMs > introStartMs && timeMs >= introStartMs && timeMs < introEndMs - 1_000) {
            skipMode = "intro"
            binding.skipIntroBtn.setText(R.string.skip_intro)
            binding.skipIntroBtn.visibility = View.VISIBLE
            return
        }
        if (creditsStartMs > 0 && timeMs >= creditsStartMs &&
            (creditsEndMs <= 0L || timeMs < creditsEndMs)
        ) {
            skipMode = "credits"
            binding.skipIntroBtn.setText(R.string.skip_outro)
            binding.skipIntroBtn.visibility = View.VISIBLE
            return
        }
        hideSkipBtn()
    }

    private fun hideSkipBtn() {
        skipMode = ""
        binding.skipIntroBtn.visibility = View.GONE
    }

    /** Fetches this episode's intro/credits windows without blocking playback. */
    private fun loadIntroMarks() {
        val imdb = intent.getStringExtra("intro_imdb") ?: return
        val season = intent.getIntExtra("intro_season", 0)
        val episode = intent.getIntExtra("intro_episode", 0)
        if (imdb.isBlank() || season <= 0 || episode <= 0) return

        Thread {
            val marks = CineIntroDb.load(this, imdb, season, episode) ?: return@Thread
            eventUiHandler.post {
                introStartMs = marks.introStartMs
                introEndMs = marks.introEndMs
                creditsStartMs = marks.creditsStartMs
                creditsEndMs = marks.creditsEndMs
                Log.v(TAG, "Intro marks: $introStartMs..$introEndMs credits=$creditsStartMs")
                updateSkipOpBtn()
            }
        }.start()
    }

    private fun updateOrientation(initial: Boolean = false) {
        // screen orientation is fixed (Android TV)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT))
            return

        if (autoRotationMode != "auto") {
            if (!initial)
                return // don't reset at runtime
            requestedOrientation = when (autoRotationMode) {
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        if (initial || player.vid == -1)
            return

        val ratio = player.getVideoAspect()?.toFloat() ?: 0f
        if (ratio == 0f || ratio in (1f / ASPECT_RATIO_MIN) .. ASPECT_RATIO_MIN) {
            // video is square, let Android do what it wants
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            return
        }
        requestedOrientation = if (ratio > 1f)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    @RequiresApi(26)
    private fun makeRemoteAction(@DrawableRes icon: Int, @StringRes title: Int, intentAction: String): RemoteAction {
        val intent = NotificationButtonReceiver.createIntent(this, intentAction)
        return RemoteAction(Icon.createWithResource(this, icon), getString(title), "", intent)
    }

    /**
     * Update Picture-in-picture parameters. Will only run if in PiP mode unless
     * `force` is set.
     */
    private fun updatePiPParams(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return
        if (!isInPictureInPictureMode && !force)
            return

        val playPauseAction = if (psc.pause)
            makeRemoteAction(R.drawable.ic_play_arrow_black_24dp, R.string.btn_play, "PLAY_PAUSE")
        else
            makeRemoteAction(R.drawable.ic_pause_black_24dp, R.string.btn_pause, "PLAY_PAUSE")
        val actions = mutableListOf<RemoteAction>()
        if (psc.playlistCount > 1) {
            actions.add(makeRemoteAction(
                R.drawable.ic_skip_previous_black_24dp, R.string.dialog_prev, "ACTION_PREV"
            ))
            actions.add(playPauseAction)
            actions.add(makeRemoteAction(
                R.drawable.ic_skip_next_black_24dp, R.string.dialog_next, "ACTION_NEXT"
            ))
        } else {
            actions.add(playPauseAction)
        }

        val params = with(PictureInPictureParams.Builder()) {
            val aspect = player.getVideoAspect() ?: 0.0
            setAspectRatio(Rational(aspect.times(10000).toInt(), 10000))
            setActions(actions)
        }
        try {
            setPictureInPictureParams(params.build())
        } catch (e: IllegalArgumentException) {
            // Android has some limits of what the aspect ratio can be
            params.setAspectRatio(Rational(1, 1))
            setPictureInPictureParams(params.build())
        }
    }

    // Media Session handling

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPause() {
            player.paused = true
        }
        override fun onPlay() {
            player.paused = false
        }
        override fun onSeekTo(pos: Long) {
            player.timePos = (pos / 1000.0)
        }
        override fun onSkipToNext() = playlistNext()
        override fun onSkipToPrevious() = playlistPrev()
        override fun onSetRepeatMode(repeatMode: Int) {
            MPVLib.setPropertyString("loop-playlist",
                if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ALL) "inf" else "no")
            MPVLib.setPropertyString("loop-file",
                if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) "inf" else "no")
        }
        override fun onSetShuffleMode(shuffleMode: Int) {
            player.changeShuffle(false, shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL)
        }
    }

    private fun initMediaSession(): MediaSessionCompat {
        /*
            https://developer.android.com/guide/topics/media-apps/working-with-a-media-session
            https://developer.android.com/guide/topics/media-apps/audio-app/mediasession-callbacks
            https://developer.android.com/reference/android/support/v4/media/session/MediaSessionCompat
         */
        val session = MediaSessionCompat(this, TAG)
        session.setFlags(0)
        session.setCallback(mediaSessionCallback)
        return session
    }

    private fun updateMediaSession() {
        synchronized (psc) {
            mediaSession?.let { psc.write(it) }
        }
    }

    // mpv events

    private fun eventPropertyUi(property: String, dummy: Any?, metaUpdated: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "track-list" -> player.loadTracks()
            "current-tracks/audio/selected", "current-tracks/video/image" -> updateAudioUI()
        }
        if (metaUpdated)
            updateMetadataDisplay()
    }

    private fun eventPropertyUi(property: String, value: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "pause" -> updatePlaybackStatus(value)
            "mute" -> { // indirectly from updateAudioPresence()
                updateAudioUI()
            }
        }
    }

    private fun eventPropertyUi(property: String, value: Long) {
        if (!activityIsForeground) return
        when (property) {
            "time-pos" -> {
                updatePlaybackPos(psc.positionSec)
                updateSkipOpBtn()
            }
            "playlist-pos", "playlist-count" -> updatePlaylistButtons()
            "chapter" -> updateSkipOpBtn()
        }
    }

    private fun eventPropertyUi(property: String, value: Double) {
        if (!activityIsForeground) return
        when (property) {
            "duration/full" -> updatePlaybackDuration(psc.durationSec)
            "video-params/aspect", "video-params/rotate" -> {
                updateOrientation()
                updatePiPParams()
            }
        }
    }

    private fun eventPropertyUi(property: String, value: String, metaUpdated: Boolean) {
        if (!activityIsForeground) return
        if (metaUpdated)
            updateMetadataDisplay()
    }

    private fun eventUi(eventId: Int) {
        if (!activityIsForeground) return
        // empty
    }

    override fun eventProperty(property: String) {
        val metaUpdated = psc.update(property)
        if (metaUpdated)
            updateMediaSession()
        if (property == "loop-file" || property == "loop-playlist") {
            mediaSession?.setRepeatMode(when (player.getRepeat()) {
                2 -> PlaybackStateCompat.REPEAT_MODE_ONE
                1 -> PlaybackStateCompat.REPEAT_MODE_ALL
                else -> PlaybackStateCompat.REPEAT_MODE_NONE
            })
        } else if (property == "current-tracks/audio/selected") {
            updateAudioPresence()
        }

        if (property == "pause" || property == "current-tracks/audio/selected")
            handleAudioFocus()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, null, metaUpdated) }
    }

    override fun eventProperty(property: String, value: Boolean) {
        val metaUpdated = psc.update(property, value)
        if (metaUpdated)
            updateMediaSession()
        if (property == "shuffle") {
            mediaSession?.setShuffleMode(if (value)
                PlaybackStateCompat.SHUFFLE_MODE_ALL
            else
                PlaybackStateCompat.SHUFFLE_MODE_NONE)
        } else if (property == "mute") {
            updateAudioPresence()
        }

        if (metaUpdated || property == "mute")
            handleAudioFocus()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Long) {
        if (psc.update(property, value))
            updateMediaSession()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Double) {
        if (psc.update(property, value))
            updateMediaSession()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: String) {
        val metaUpdated = psc.update(property, value)
        if (metaUpdated)
            updateMediaSession()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value, metaUpdated) }
    }

    override fun event(eventId: Int) {
        if (eventId == MpvEvent.MPV_EVENT_END_FILE) {
            psc.eof()
            updateMediaSession()
        }

        if (eventId == MpvEvent.MPV_EVENT_SHUTDOWN)
            finishWithResult(if (playbackHasStarted) RESULT_OK else RESULT_CANCELED)

        if (eventId == MpvEvent.MPV_EVENT_START_FILE) {
            val cmds = onloadCommands.toTypedArray()
            onloadCommands.clear()
            for (c in cmds)
                MPVLib.command(c)
            if (this.statsLuaMode > 0 && !playbackHasStarted) {
                MPVLib.command(arrayOf("script-binding", "stats/display-page-${this.statsLuaMode}-toggle"))
            }

            player.paused = false
            playbackHasStarted = true
        }

        if (!activityIsForeground) return
        eventUiHandler.post { eventUi(eventId) }
    }

    // Gesture handler

    private var initialSeek = 0f
    private var initialBright = 0f
    private var initialVolume = 0
    private var maxVolume = 0
    /** 0 = initial, 1 = paused, 2 = was already paused */
    private var pausedForSeek = 0

    private fun fadeGestureText() {
        fadeHandler.removeCallbacks(fadeRunnable3)
        binding.gestureTextView.visibility = View.VISIBLE

        fadeHandler.postDelayed(fadeRunnable3, 500L)
    }

    override fun onPropertyChange(p: PropertyChange, diff: Float) {
        val gestureTextView = binding.gestureTextView
        when (p) {
            /* Drag gestures */
            PropertyChange.Init -> {
                mightWantToToggleControls = false

                initialSeek = (psc.position / 1000f)
                initialBright = Utils.getScreenBrightness(this) ?: 0.5f
                with (audioManager!!) {
                    initialVolume = getStreamVolume(STREAM_TYPE)
                    maxVolume = if (isVolumeFixed)
                        0
                    else
                        getStreamMaxVolume(STREAM_TYPE)
                }
                if (!isPlayingAudio)
                    maxVolume = 0 // disallow volume gesture if no audio
                pausedForSeek = 0

                fadeHandler.removeCallbacks(fadeRunnable3)
                gestureTextView.visibility = View.VISIBLE
                gestureTextView.text = ""
            }
            PropertyChange.Seek -> {
                // disable seeking when duration is unknown
                val duration = (psc.duration / 1000f)
                if (duration == 0f || initialSeek < 0)
                    return
                if (smoothSeekGesture && pausedForSeek == 0) {
                    pausedForSeek = if (psc.pause) 2 else 1
                    if (pausedForSeek == 1)
                        player.paused = true
                }

                val newPosExact = (initialSeek + diff).coerceIn(0f, duration)
                val newPos = newPosExact.roundToInt()
                val newDiff = (newPosExact - initialSeek).roundToInt()
                if (smoothSeekGesture) {
                    player.timePos = newPosExact.toDouble() // (exact seek)
                } else {
                    // seek faster than assigning to timePos but less precise
                    MPVLib.command(arrayOf("seek", "$newPosExact", "absolute+keyframes"))
                }
                // Note: don't call updatePlaybackPos() here because mpv will seek a timestamp
                // actually present in the file, and not the exact one we specified.

                val posText = Utils.prettyTime(newPos)
                val diffText = Utils.prettyTime(newDiff, true)
                gestureTextView.text = getString(R.string.ui_seek_distance, posText, diffText)
            }
            PropertyChange.Volume -> {
                if (maxVolume == 0)
                    return
                val newVolume = (initialVolume + (diff * maxVolume).toInt()).coerceIn(0, maxVolume)
                val newVolumePercent = 100 * newVolume / maxVolume
                audioManager!!.setStreamVolume(STREAM_TYPE, newVolume, 0)

                gestureTextView.text = getString(R.string.ui_volume, newVolumePercent)
            }
            PropertyChange.Bright -> {
                val newBrightPercent = ((initialBright + diff).coerceIn(0f, 1f) * 100).roundToInt()
                lastScreenBrightness = newBrightPercent
                updateScreenBrightness()

                gestureTextView.text = getString(R.string.ui_brightness, newBrightPercent)
            }
            PropertyChange.Finalize -> {
                if (pausedForSeek == 1)
                    player.paused = false
                gestureTextView.visibility = View.GONE
            }

            /* Tap gestures */
            PropertyChange.SeekFixed -> {
                val seekTime = diff * 10f
                val newPos = psc.positionSec + seekTime.toInt() // only for display
                MPVLib.command(arrayOf("seek", seekTime.toString(), "relative"))

                val diffText = Utils.prettyTime(seekTime.toInt(), true)
                gestureTextView.text = getString(R.string.ui_seek_distance, Utils.prettyTime(newPos), diffText)
                fadeGestureText()
            }
            PropertyChange.PlayPause -> player.cyclePause()
            PropertyChange.Custom -> {
                val keycode = 0x10002 + diff.toInt()
                MPVLib.command(arrayOf("keypress", "0x%x".format(keycode)))
            }
        }
    }

    companion object {
        private const val TAG = "mpv"

        /** mpv options the CineVerse resolver is allowed to set through an intent. */
        private val ALLOWED_INTENT_OPTIONS = setOf(
            "hls-bitrate",
            "cache",
            "cache-secs",
            "cache-pause",
            "demuxer-readahead-secs",
            "demuxer-max-bytes",
            "demuxer-max-back-bytes",
            "video-sync",
        )
        // how long should controls be displayed on screen (ms)
        private const val CONTROLS_DISPLAY_TIMEOUT = 1500L
        // how long controls fade to disappear (ms)
        private const val CONTROLS_FADE_DURATION = 500L
        // smallest aspect ratio that is considered non-square
        private const val ASPECT_RATIO_MIN = 1.2f // covers 5:4 and up
        // fraction to which audio volume is ducked on loss of audio focus
        private const val AUDIO_FOCUS_DUCKING = 0.5f
        // request codes for invoking other activities
        private const val RCODE_EXTERNAL_AUDIO = 1000
        private const val RCODE_EXTERNAL_SUB = 1001
        private const val RCODE_LOAD_FILE = 1002
        // action of result intent
        private const val RESULT_INTENT = "is.xyz.mpv.MPVActivity.result"
        // stream type used with AudioManager
        private const val STREAM_TYPE = AudioManager.STREAM_MUSIC
        // precision used by seekbar (1/s)
        private const val SEEK_BAR_PRECISION = 2
    }
}
