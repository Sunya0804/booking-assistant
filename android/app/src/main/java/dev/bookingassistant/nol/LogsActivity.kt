package dev.bookingassistant.nol

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Saved runs are selected here; live booking logs stay on the booking screen. */
class LogsActivity : AppCompatActivity(), CoroutineScope by MainScope() {
    private lateinit var palette: Palette
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var count: TextView
    private lateinit var footer: LinearLayout
    private lateinit var share: com.google.android.material.button.MaterialButton
    private lateinit var delete: com.google.android.material.button.MaterialButton
    private var entries = emptyList<RunLogs.Entry>()
    private val selected = linkedSetOf<String>()
    private val rows = mutableMapOf<String, Pair<LinearLayout, MaterialCheckBox>>()
    private var loading: Job? = null
    private var deleting = false
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd (E) HH:mm:ss", Locale.KOREAN).withZone(KST)

    override fun onCreate(savedInstanceState: Bundle?) {
        palette = applyTheme()
        super.onCreate(savedInstanceState)
        selected.addAll(savedInstanceState?.getStringArrayList("selected").orEmpty())
        setContentView(build())
        paintSystemBars(palette)
    }

    override fun onResume() {
        super.onResume()
        if (palette.name != themeName()) {
            recreate()
            return
        }
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("selected", ArrayList(selected))
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.bg)
            padForSystemBars()
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val header = card(palette, 8).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(palette.label)
            background = rounded(android.graphics.Color.TRANSPARENT, 12)
            contentDescription = "뒤로"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        header.addView(text("저장된 기록", palette, 20f, palette.text, 700), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        count = text("선택 0개", palette, 12.5f, palette.muted, 600).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        header.addView(count)
        body.add(header)
        body.add(text("기록을 눌러 선택하고, 보기로 내용을 확인하세요.", palette, 13f, palette.muted).apply {
            setPadding(dp(2), 0, dp(2), 0)
        }, top = 14)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(16))
        }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(list) }
        body.add(scroll, top = 8, height = 0, weight = 1f)
        root.add(body, height = 0, weight = 1f)

        val bottom = LinearLayout(this).apply { setPadding(dp(16), dp(8), dp(16), dp(12)) }
        footer = card(palette, 12).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        share = action("공유 (0)", palette).apply { setOnClickListener { shareEntries(selectedEntries()) } }
        delete = action("삭제", palette, "danger").apply { setOnClickListener { confirmDelete() } }
        footer.addView(share, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        footer.addView(delete, LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)))
        bottom.add(footer)
        // The actions never scroll away with the list.
        root.add(bottom)
        updateSelection()
        return root
    }

    private fun refresh() {
        loading?.cancel()
        loading = launch {
            try {
                val fresh = withContext(Dispatchers.IO) { RunLogs.list(this@LogsActivity) }
                val y = scroll.scrollY
                entries = fresh
                selected.retainAll(fresh.map { it.file.name }.toSet())
                rows.clear()
                list.removeAllViews()
                if (fresh.isEmpty()) {
                    list.add(card(palette, 24).apply {
                        add(text("아직 저장된 기록이 없습니다. 예매를 시작하면 화면을 닫을 때 자동으로 저장됩니다.", palette, 15f, palette.muted).apply {
                            setLineSpacing(dp(5).toFloat(), 1f)
                        })
                    }, top = 8)
                } else fresh.forEach { entry -> list.add(entryCard(entry), top = 8) }
                updateSelection()
                scroll.post { scroll.scrollTo(0, y) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error("기록을 불러오지 못했습니다. 다시 열어 주세요.")
            }
        }
    }

    private fun entryCard(entry: RunLogs.Entry): LinearLayout {
        val date = dateFormat.format(entry.startedAt)
        val card = card(palette, 14).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { toggle(entry) }
            setOnLongClickListener { showEntry(entry); true }
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val check = MaterialCheckBox(this).apply {
            isChecked = entry.file.name in selected
            contentDescription = "$date 기록 선택"
            buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(palette.accent, palette.muted))
            setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(entry.file.name) else selected.remove(entry.file.name)
                updateSelection()
            }
            setOnLongClickListener { showEntry(entry); true }
        }
        head.addView(check, LinearLayout.LayoutParams(dp(40), dp(48)))
        head.addView(text(date, palette, 14f, palette.text, 700).apply { fontFeatureSettings = "tnum" }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(action("보기", palette, "text").apply {
            minWidth = 0; minimumWidth = 0
            contentDescription = "$date 기록 보기"
            setOnClickListener { showEntry(entry) }
        }, LinearLayout.LayoutParams(dp(56), dp(48)))
        card.add(head)
        val info = tile(palette)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (entry.site.isNotBlank()) details.add(text(entry.site, palette, 14f, palette.label, 600))
        details.add(text("기록 ${entry.lines}줄", palette, 12.5f, palette.muted), top = if (entry.site.isBlank()) 0 else 4)
        row.addView(details, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (entry.result.isNotBlank()) {
            val status = when (entry.result) { "클릭 완료" -> "done"; "확인 필요" -> "fail"; else -> "idle" }
            row.addView(TextView(this).apply { chip(palette, status, entry.result) })
        }
        info.add(row)
        if (entry.openAt.isNotBlank()) info.add(text("오픈 ${entry.openAt}", palette, 12.5f, palette.muted).apply { fontFeatureSettings = "tnum" }, top = 10)
        card.add(info, top = 8)
        rows[entry.file.name] = card to check
        return card
    }

    private fun toggle(entry: RunLogs.Entry) {
        if (!selected.add(entry.file.name)) selected.remove(entry.file.name)
        updateSelection()
    }

    private fun selectedEntries() = entries.filter { it.file.name in selected }

    private fun updateSelection() {
        count.text = "선택 ${selected.size}개"
        share.text = "공유 (${selected.size})"
        footer.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        share.isEnabled = !deleting && selected.isNotEmpty()
        delete.isEnabled = !deleting && selected.isNotEmpty()
        rows.forEach { (name, row) ->
            val checked = name in selected
            row.first.background = rounded(palette.surface, 18, if (checked) palette.accent else palette.border)
            row.first.isSelected = checked
            if (row.second.isChecked != checked) row.second.isChecked = checked
        }
    }

    private fun showEntry(entry: RunLogs.Entry) {
        launch {
            val content = try {
                withContext(Dispatchers.IO) { entry.file.readText() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error("기록을 읽지 못했습니다. 목록을 다시 확인해 주세요.")
                refresh()
                return@launch
            }
            val detail = text(content, palette, 14f).apply {
                setTextIsSelectable(true)
                setLineSpacing(dp(5).toFloat(), 1f)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = rounded(palette.tile, 14)
            }
            val scroller = object : ScrollView(this@LogsActivity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val cap = (resources.displayMetrics.heightPixels * 0.55).toInt()
                    val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) cap
                        else minOf(cap, View.MeasureSpec.getSize(heightMeasureSpec))
                    super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
                }
            }.apply { addView(detail) }
            val container = LinearLayout(this@LogsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                add(scroller)
            }
            MaterialAlertDialogBuilder(this@LogsActivity)
                .setTitle(dateFormat.format(entry.startedAt))
                .setView(container)
                .setNegativeButton("닫기", null)
                .setPositiveButton("이 기록 공유") { _, _ -> shareEntries(listOf(entry)) }
                .show()
        }
    }

    private fun shareEntries(chosen: List<RunLogs.Entry>) {
        if (chosen.isEmpty()) return
        try {
            RunLogs.share(this, chosen)
        } catch (e: android.content.ActivityNotFoundException) {
            error("공유할 수 있는 앱을 찾지 못했습니다.")
        }
    }

    private fun confirmDelete() {
        val chosen = selectedEntries()
        if (chosen.isEmpty() || deleting) return
        MaterialAlertDialogBuilder(this)
            .setTitle("${chosen.size}개 기록을 삭제할까요?")
            .setMessage("삭제한 기록은 되돌릴 수 없습니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                deleting = true
                updateSelection()
                launch {
                    try {
                        val failed = withContext(Dispatchers.IO) { chosen.count { !RunLogs.delete(it) } }
                        if (failed > 0) error("${failed}개 기록을 삭제하지 못했습니다. 다시 시도해 주세요.")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error("기록을 삭제하지 못했습니다. 다시 시도해 주세요.")
                    } finally {
                        deleting = false
                        updateSelection()
                        refresh()
                    }
                }
            }.show()
    }

    private fun error(message: String) {
        if (!isFinishing && !isDestroyed) MaterialAlertDialogBuilder(this)
            .setMessage(message).setPositiveButton("확인", null).show()
    }
}
