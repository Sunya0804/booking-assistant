package dev.bookingassistant.nol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The alarm sound at a chosen volume.
 *
 * The volume is a share of the alarm stream's maximum, applied only while ringing and
 * put back afterwards, so the slider means the same thing whatever the phone is set to.
 */
class AlarmSound(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var savedVolume: Int? = null
    private val handler = Handler(Looper.getMainLooper())

    fun start(volumePercent: Int) {
        stop()
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val target = if (volumePercent <= 0) 0 else maxOf(1, Math.round(max * volumePercent / 100f))
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0) }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = uri?.let {
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    setDataSource(context, it)
                    isLooping = true
                    prepare()
                    start()
                }
            }.getOrNull()
        }
        if (player == null) beep() // no alarm sound on this phone: repeat a tone instead
    }

    private fun beep() {
        val t = tone ?: ToneGenerator(AudioManager.STREAM_ALARM, 100).also { tone = it }
        t.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 600)
        handler.postDelayed(::beep, 1200)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        player?.run { runCatching { stop() }; release() }
        player = null
        tone?.release()
        tone = null
        savedVolume?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        savedVolume = null
    }
}

/** Rings the opening alarm (started by AlarmManager even when the app is closed). */
class AlarmService : Service() {
    companion object {
        private const val CHANNEL = "open_alarm"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "dev.bookingassistant.nol.STOP_ALARM"
        private const val RING_FOR_MS = 3 * 60_000L

        fun stop(context: Context) {
            context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_STOP))
        }

        fun openText(openAt: Double): String = if (openAt <= 0) "" else
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(KST).format(Instant.ofEpochMilli((openAt * 1000).toLong())) + " 오픈"
    }

    private lateinit var sound: AlarmSound
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sound = AlarmSound(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val openAt = intent?.getDoubleExtra(OpenAlarm.EXTRA_OPEN_AT, 0.0) ?: 0.0
        val minutes = intent?.getIntExtra(OpenAlarm.EXTRA_MINUTES, 5) ?: 5
        // Must go foreground within seconds of being started from the alarm.
        startForeground(NOTIFICATION_ID, notification(openAt, minutes), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        sound.start(OpenAlarm.volume(Settings(this)))
        runCatching {
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        }
        handler.postDelayed({ stopSelf() }, RING_FOR_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sound.stop()
        runCatching { getSystemService(Vibrator::class.java)?.cancel() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(openAt: Double, minutes: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "예매 알람", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "오픈 몇 분 전에 울리는 알람"
            setSound(null, null) // the service plays the sound itself, at the chosen volume
            enableVibration(false)
        })
        val screen = Intent(this, AlarmActivity::class.java)
            .putExtra(OpenAlarm.EXTRA_OPEN_AT, openAt)
            .putExtra(OpenAlarm.EXTRA_MINUTES, minutes)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val screenPending = PendingIntent.getActivity(this, 2, screen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopPending = PendingIntent.getService(this, 3,
            Intent(this, AlarmService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ticket_clock_mono)
            .setContentTitle("예매 ${minutes}분 전")
            .setContentText(openText(openAt).ifEmpty { "NOL 예매도우미를 열어 준비하세요" })
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(screenPending)
            .setFullScreenIntent(screenPending, true)
            .addAction(Notification.Action.Builder(null, "끄기", stopPending).build())
            .build()
    }
}

/** Shown over the lock screen while the alarm rings. */
class AlarmActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val palette = applyTheme()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val openAt = intent.getDoubleExtra(OpenAlarm.EXTRA_OPEN_AT, 0.0)
        val minutes = intent.getIntExtra(OpenAlarm.EXTRA_MINUTES, 5)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        // The outer frame takes the system-bar insets; the column keeps its own margins.
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(palette.bg)
            addView(col, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            padForSystemBars()
        }
        val card = card(palette, 28).apply { gravity = Gravity.CENTER_HORIZONTAL }
        card.add(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_mark)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.onAccent)
            background = rounded(palette.accent, 16)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, width = dp(56), height = dp(56))
        card.add(TextView(this).apply { chip(palette, "busy", "오픈 ${minutes}분 전") }, top = 20, width = WRAP_CONTENT)
        card.add(text("예매 ${minutes}분 전", palette, 32f, palette.text, 700).apply { gravity = Gravity.CENTER }, top = 14)
        card.add(text(AlarmService.openText(openAt), palette, 16f, palette.label, 600).apply { gravity = Gravity.CENTER }, top = 10)
        card.add(text("앱을 열어 로그인과 날짜/회차를 확인하고 시작하세요.", palette, 14f, palette.muted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }, top = 8)
        card.add(action("앱 열기", palette).apply {
            textSize = 16f
            setOnClickListener {
                AlarmService.stop(this@AlarmActivity)
                startActivity(Intent(this@AlarmActivity, SetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }, top = 28, height = dp(56))
        card.add(action("알람 끄기", palette, "secondary").apply {
            setOnClickListener {
                AlarmService.stop(this@AlarmActivity)
                finish()
            }
        }, top = 8, height = dp(52))
        col.add(card)
        setContentView(root)
        paintSystemBars(palette)
    }
}
