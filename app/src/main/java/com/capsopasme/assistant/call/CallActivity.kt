package com.capsopasme.assistant.call

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.Chronometer
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import com.capsopasme.assistant.Prefs
import com.capsopasme.assistant.R
import com.capsopasme.assistant.fx.Haptics
import com.capsopasme.assistant.fx.ScreenGrab
import com.capsopasme.assistant.fx.TimeStopView
import com.capsopasme.assistant.fx.recycleLater
import com.capsopasme.assistant.fx.setStatusBarHidden

/**
 * The voice call screen. Starts [CallService] (from here, while visible: a microphone service
 * may only start from the foreground) and shows its state; the call itself lives in the service,
 * so it goes on with the screen off, when this screen is left, or recreated (dark mode switched
 * by the assistant).
 *
 * Shown over the lock screen, like an incoming-call screen: turning the screen back on during a
 * call shows it without unlocking.
 *
 * The theme is translucent so the time-stop effect can grab the screen behind before the call
 * bursts out of it; the window turns opaque right away, or once that has played.
 */
class CallActivity : Activity(), CallService.Ui {

    private lateinit var prefs: Prefs
    private lateinit var callRoot: View
    private lateinit var orb: OrbView
    private lateinit var timeStop: TimeStopView
    private lateinit var status: TextView
    private lateinit var userText: TextView
    private lateinit var answer: TextView
    private lateinit var captions: CaptionScrollView
    private lateinit var captionsToggle: ImageButton
    private lateinit var confirmRow: View
    private lateinit var mute: ImageButton
    private lateinit var muteLabel: TextView
    private lateinit var speaker: ImageButton
    private lateinit var speakerLabel: TextView
    private lateinit var duration: Chronometer

    private var service: CallService? = null
    private var bound = false
    private var visible = false
    private var chronoBase = 0L
    private var backHintShown = false
    private var closing = false

    /** the time-stop entrance is grabbing the screen or playing */
    private var fxEntering = false

    /** the time-stop exit is playing: the screen closes when it ends */
    private var exiting = false

    /** the frozen world (half resolution), kept for the exit when the call ends */
    private var fxFrame: Bitmap? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            val s = (binder as CallService.LocalBinder).service
            service = s
            s.setUiVisible(visible)
            s.attach(this@CallActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // the process of the service is this one: only on a crash
            service = null
            finishCall()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)
        prefs = Prefs(this)
        callRoot = findViewById(R.id.callRoot)
        orb = findViewById(R.id.orb)
        timeStop = findViewById(R.id.timeStop)
        timeStop.revealed = callRoot
        status = findViewById(R.id.callStatus)
        userText = findViewById(R.id.callUser)
        answer = findViewById(R.id.callAnswer)
        captions = findViewById(R.id.captions)
        captionsToggle = findViewById(R.id.captionsToggle)
        confirmRow = findViewById(R.id.callConfirmRow)
        mute = findViewById(R.id.callMute)
        muteLabel = findViewById(R.id.callMuteLabel)
        speaker = findViewById(R.id.callSpeaker)
        speakerLabel = findViewById(R.id.callSpeakerLabel)
        duration = findViewById(R.id.duration)

        callRoot.setOnApplyWindowInsetsListener { v, insets ->
            // ignoring visibility: hiding the status bar (time stop) must not move anything
            val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            WindowInsets.CONSUMED
        }
        orb.setOnClickListener { service?.tap() }
        findViewById<View>(R.id.callHangUp).setOnClickListener {
            val s = service
            if (s != null) s.hangUp() else finishCall()
        }
        mute.setOnClickListener { service?.toggleMute() }
        speaker.setOnClickListener { service?.toggleSpeaker() }
        findViewById<Button>(R.id.callConfirmYes).setOnClickListener { service?.answerConfirm(true) }
        findViewById<Button>(R.id.callConfirmNo).setOnClickListener { service?.answerConfirm(false) }
        captionsToggle.setOnClickListener {
            prefs.callCaptions = !prefs.callCaptions
            applyCaptions()
        }
        applyCaptions()

        // like a phone call: leaving the screen keeps the call, the notification brings it back
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (!backHintShown) {
                backHintShown = true
                Toast.makeText(this, "通话在后台继续，可以从通知栏回来或挂断", Toast.LENGTH_SHORT).show()
            }
            moveTaskToBack(true)
        }

        // a new call, started in front of the user: burst out of stopped time
        val fx = prefs.timeStopFx && savedInstanceState == null && !CallService.running &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                getSystemService(PowerManager::class.java).isInteractive &&
                !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (fx) startTimeStop() else setTranslucent(false)

        connectOrStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (service == null && !bound) connectOrStart()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        service?.setUiVisible(true)
    }

    override fun onStop() {
        super.onStop()
        visible = false
        service?.setUiVisible(false)
    }

    override fun onDestroy() {
        timeStop.cancel()
        recycleLater(fxFrame)
        fxFrame = null
        service?.detach(this)
        service = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------

    private fun connectOrStart() {
        if (CallService.running) {
            bind()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), REQ_PERMS)
            return
        }
        // the notification (hang up from the lock screen) needs this; the call works without it
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
        startForegroundService(Intent(this, CallService::class.java))
        bind()
    }

    private fun bind() {
        if (!bound) bound = bindService(Intent(this, CallService::class.java), connection, 0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            connectOrStart()
        } else {
            Toast.makeText(this, "需要麦克风权限才能语音通话", Toast.LENGTH_LONG).show()
            finishCall()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // CallService.Ui

    override fun render(state: CallService.State) {
        if (closing) return
        orb.mode = when {
            state.phase == CallService.Phase.Paused || state.muted && state.phase == CallService.Phase.Listening -> OrbView.Mode.Muted
            state.phase == CallService.Phase.Listening -> OrbView.Mode.Listening
            // the question is read out first, then the answer is listened for
            state.phase == CallService.Phase.Confirming -> if (state.micOpen) OrbView.Mode.Listening else OrbView.Mode.Speaking
            state.phase == CallService.Phase.Thinking -> OrbView.Mode.Thinking
            state.phase == CallService.Phase.Speaking -> OrbView.Mode.Speaking
            else -> OrbView.Mode.Idle
        }
        status.text = state.status

        userText.text = state.userText
        userText.visibility = if (state.userText.isEmpty()) View.GONE else View.VISIBLE
        val secondary = getColor(R.color.text_secondary)
        userText.setTextColor(if (state.userPartial) (secondary and 0x00FFFFFF) or (0x99 shl 24) else secondary)
        val answerChanged = answer.text.toString() != state.answer
        answer.text = state.answer
        answer.visibility = if (state.answer.isEmpty()) View.GONE else View.VISIBLE
        answer.setTextColor(getColor(if (state.answerIsError) R.color.error else R.color.text_primary))
        if (answerChanged) captions.scrollToEnd()

        confirmRow.visibility = if (state.confirming) View.VISIBLE else View.GONE

        mute.isActivated = state.muted
        mute.setImageResource(if (state.muted) R.drawable.ic_mic_off else R.drawable.ic_mic_plain)
        muteLabel.text = if (state.muted) "已静音" else "静音"

        speaker.isEnabled = state.canSwitchSpeaker
        speaker.isActivated = state.canSwitchSpeaker && state.speakerOn
        when {
            state.headphones -> {
                speaker.setImageResource(R.drawable.ic_headset)
                speakerLabel.text = "耳机"
            }
            state.speakerOn || !state.canSwitchSpeaker -> {
                speaker.setImageResource(R.drawable.ic_speaker)
                speakerLabel.text = "扬声器"
            }
            else -> {
                speaker.setImageResource(R.drawable.ic_earpiece)
                speakerLabel.text = "听筒"
            }
        }

        if (state.startedAt != chronoBase) {
            chronoBase = state.startedAt
            duration.base = state.startedAt
            duration.start()
        }
        service?.let { volumeControlStream = it.volumeStream }
    }

    override fun onLevel(rms: Float) = orb.setLevel(rms)

    override fun onEnded(launches: List<Intent>) {
        if (closing) return
        if (launches.isEmpty()) {
            finishCall()
            return
        }
        closing = true
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            // the app the user asked for would open behind the lock screen
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = launchAndClose(launches)
                override fun onDismissCancelled() = launchAndClose(launches)
                override fun onDismissError() = launchAndClose(launches)
            })
        } else {
            launchAndClose(launches)
        }
    }

    private fun launchAndClose(launches: List<Intent>) {
        for (intent in launches) {
            try {
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "打开失败", Toast.LENGTH_SHORT).show()
            }
        }
        closing = false
        // the app (the assistant sheet) is opening over this: no effect underneath it
        finishCall(effect = false)
    }

    /** @param effect time flows again (the time-stop exit) if it's on and the call is in view */
    private fun finishCall(effect: Boolean = true) {
        if (isFinishing || exiting) return
        closing = true
        duration.stop()
        val play = effect && prefs.timeStopFx && visible && !fxEntering && chronoBase != 0L &&
                getSystemService(PowerManager::class.java).isInteractive &&
                !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!play) {
            finishAndRemoveTask()
            return
        }
        // the call collapses into the orb, the world frozen when it started thaws around it and
        // fades into the live screen. See-through again first, so the app behind is drawn by then
        // (and when the effect ends nothing of this window is left to slide away)
        exiting = true
        setTranslucent(true)
        timeStop.setWorld(fxFrame)
        setStatusBarHidden(true)
        Haptics.timeResume(this)
        val (x, y) = orbCenter()
        timeStop.play(TimeStopView.Style.Reveal, enter = false, x, y) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            finishAndRemoveTask()
        }
        // the frozen frame has its own status bar; the live screen gets the real one back
        timeStop.postDelayed({ setStatusBarHidden(false) }, (TimeStopView.REVEAL_EXIT_MS * TimeStopView.REVEAL_EXIT_LIVE).toLong())
    }

    // ---------------------------------------------------------------------------------------------
    // time stop

    /**
     * Grabs the screen as it is (this window is still see-through and draws nothing), then the
     * world freezes and the call bursts out of a sphere around the orb.
     */
    private fun startTimeStop() {
        fxEntering = true
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        callRoot.visibility = View.INVISIBLE
        val screen = windowManager.currentWindowMetrics.bounds
        Thread({
            val shot = ScreenGrab.capture(screen.width(), screen.height(), withHalf = true)
            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    // never drawn
                    shot?.full?.recycle()
                    shot?.half?.recycle()
                    return@runOnUiThread
                }
                fxFrame = shot?.half
                timeStop.setWorld(shot?.full)
                callRoot.visibility = View.VISIBLE
                setStatusBarHidden(true)
                Haptics.timeStop(this)
                val (x, y) = orbCenter()
                timeStop.play(TimeStopView.Style.Reveal, enter = true, x, y) {
                    fxEntering = false
                    timeStop.cancel()
                    recycleLater(shot?.full)
                    setStatusBarHidden(false)
                    setTranslucent(false)
                }
            }
        }, "time-stop-grab").start()
    }

    /** the orb's centre in the effect's coordinates */
    private fun orbCenter(): Pair<Float, Float> {
        val a = IntArray(2)
        val b = IntArray(2)
        orb.getLocationInWindow(a)
        timeStop.getLocationInWindow(b)
        return (a[0] - b[0] + orb.width / 2f) to (a[1] - b[1] + orb.height / 2f)
    }

    private fun applyCaptions() {
        val on = prefs.callCaptions
        captions.visibility = if (on) View.VISIBLE else View.INVISIBLE
        captionsToggle.alpha = if (on) 1f else 0.45f
        captionsToggle.contentDescription = if (on) "隐藏字幕" else "显示字幕"
    }

    companion object {
        /** for other apps (QuickBall etc.): `am start -a com.capsopasme.assistant.CALL` */
        const val ACTION_CALL = "com.capsopasme.assistant.CALL"

        private const val REQ_PERMS = 1
        private const val REQ_NOTIFICATIONS = 2
    }
}
