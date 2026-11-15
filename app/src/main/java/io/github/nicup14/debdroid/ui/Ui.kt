package io.github.nicup14.debdroid.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.nicup14.debdroid.R

// The palette, read out of res/icon/svg/debdroid-mark.svg
//
// Two rules hold everywhere and are not preferences. Crimson fills, never
// labels: it is 4.0:1 on the ground, under the floor for body text, so it
// carries buttons, active states and borders, never a small label. And red is
// the brand, so red cannot mean danger: destructive actions are amber, with an
// outline and a typed confirmation.
object Palette {
    const val GROUND = 0xFF0B0B0D.toInt()
    const val BARS = 0xFF0F0F12.toInt()
    const val CARD = 0xFF141418.toInt()
    const val INSET = 0xFF1C1C21.toInt()
    const val LINE = 0xFF26262D.toInt()
    const val LINE_INSET = 0xFF2E2E36.toInt()
    const val BORDER = 0xFF3A3A44.toInt()
    const val PRIMARY = 0xFFE0103A.toInt()
    const val PRESSED = 0xFFA80D2E.toInt()
    const val STRUCTURE = 0xFF7A0A22.toInt()
    const val TEXT = 0xFFF4F4F6.toInt()
    const val TEXT_SOFT = 0xFFD6D6DC.toInt()
    const val TEXT_MID = 0xFF9B9BA6.toInt()
    const val DIM = 0xFF8E8E9A.toInt()
    const val FAINT = 0xFF5E5E6A.toInt()
    const val OUTPUT = 0xFFCFCFD6.toInt()
    const val RUNNING = 0xFF34D399.toInt()
    const val CAUTION = 0xFFF5A524.toInt()
    const val CAUTION_LINE = 0xFF6B4A12.toInt()
    const val CAUTION_CARD = 0xFF3A2A12.toInt()
    const val PATHS = 0xFF7AA2F7.toInt()

    // A lighter crimson for android's own surfaces, where #E0103A is too dark
    // to read, such as the notification
    const val ON_SYSTEM = 0xFFF08098.toInt()
}

// Building blocks for the screens, so every screen looks like the canvas
object Ui {
    // "If DebDroid printed it, it is monospace": output, paths, sizes, versions
    val mono: Typeface = Typeface.MONOSPACE
    val sans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    // How much larger than the canvas the text is drawn
    // NOTE: The canvas sizes (10.5 to 14.5px for most text) read too small on
    //       a real device, and smaller still on a tablet held further away,
    //       so every size goes through this one factor rather than being
    //       raised screen by screen. The system font scale still applies on
    //       top, as sizes are in sp.
    fun scale(context: Context): Float =
        if (context.resources.configuration.smallestScreenWidthDp >= 600) 1.45f else 1.3f

    // The smallest text the app draws, after scaling
    const val MIN_TEXT = 13f

    // A canvas text size, scaled for this device
    fun sp(context: Context, size: Float): Float = maxOf(size * scale(context), MIN_TEXT)

    // Icons grow with the text, a little less, so rows keep their balance
    fun iconSize(context: Context, size: Float): Int = dp(context, size * (1f + (scale(context) - 1f) * 0.75f))

    fun Context.dp(value: Number) = dp(this, value.toFloat())

    fun rounded(context: Context, fill: Int, radius: Float, stroke: Int = 0, strokeWidth: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radius).toFloat()
            if (stroke != 0) setStroke(dp(context, strokeWidth).coerceAtLeast(1), stroke)
        }

    // Wraps a background in a ripple, so every tap shows that it registered
    fun pressable(context: Context, base: Drawable?, radius: Float, ripple: Int = 0x33FFFFFF): Drawable {
        val mask = rounded(context, Color.WHITE, radius)
        return RippleDrawable(ColorStateList.valueOf(ripple), base, mask)
    }

    fun text(context: Context, value: CharSequence, size: Float, color: Int, face: Typeface = sans): TextView =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(context, size))
            setTextColor(color)
            typeface = face
            includeFontPadding = false
            setLineSpacing(0f, 1.25f)
        }

    fun title(context: Context, value: String, size: Float = 25f) = text(context, value, size, Palette.TEXT, bold).apply {
        letterSpacing = -0.02f
    }

    fun body(context: Context, value: CharSequence, size: Float = 13.5f) = text(context, value, size, Palette.DIM).apply {
        setLineSpacing(0f, 1.4f)
    }

    fun monoText(context: Context, value: CharSequence, size: Float = 11.5f, color: Int = Palette.DIM) =
        text(context, value, size, color, mono)

    // A section's small heading
    fun header(context: Context, value: String) = text(context, value.uppercase(), 11f, Palette.DIM, medium).apply {
        letterSpacing = 0.07f
        setPadding(context.dp(4), context.dp(2), 0, context.dp(8))
    }

    fun icon(context: Context, res: Int, color: Int, size: Float = 17f): ImageView = ImageView(context).apply {
        setImageDrawable(context.getDrawable(res)?.mutate()?.also { it.setTint(color) })
        layoutParams = LinearLayout.LayoutParams(iconSize(context, size), iconSize(context, size))
    }

    fun mark(context: Context, size: Float): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_mark)
        layoutParams = LinearLayout.LayoutParams(context.dp(size), context.dp(size))
        contentDescription = "DebDroid"
    }

    fun vertical(context: Context, gap: Float = 0f): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (gap > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(1, context.dp(gap)) }
        }
    }

    fun horizontal(context: Context, gap: Float = 0f): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gap > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(context.dp(gap), 1) }
        }
    }

    fun space(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
    }

    fun gap(context: Context, height: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, context.dp(height))
    }

    // A card: the grouped container every list sits in
    fun card(context: Context, radius: Float = 16f, stroke: Int = Palette.LINE): LinearLayout = vertical(context).apply {
        background = rounded(context, Palette.CARD, radius, stroke)
        clipToOutline = true
    }

    fun divider(context: Context, inset: Float = 0f): View = View(context).apply {
        setBackgroundColor(Palette.LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            leftMargin = context.dp(inset)
        }
    }

    enum class Kind { PRIMARY, SECONDARY, CAUTION }

    // A button. One primary per screen, in crimson; the rest outlined
    // NOTE: A least height, never a fixed one, so large system text grows the
    //       button instead of cutting its label.
    fun button(context: Context, label: String, kind: Kind = Kind.PRIMARY, iconRes: Int = 0, height: Float = 52f): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            minHeight = context.dp(height)
            setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(context, if (kind == Kind.PRIMARY) 16f else 14.5f))
            typeface = if (kind == Kind.PRIMARY) bold else medium
            isClickable = true
            isFocusable = true
            val (fill, stroke, color) = when (kind) {
                Kind.PRIMARY -> Triple(Palette.PRIMARY, 0, Color.WHITE)
                Kind.SECONDARY -> Triple(Color.TRANSPARENT, Palette.BORDER, Palette.TEXT_SOFT)
                Kind.CAUTION -> Triple(Color.TRANSPARENT, Palette.CAUTION_LINE, Palette.CAUTION)
            }
            setTextColor(color)
            background = pressable(context, rounded(context, fill, 14f, stroke), 14f)
            if (iconRes != 0) {
                // NOTE: The icon goes into the text rather than beside it, so
                //       it stays next to the centred label as the boards draw
                //       it. A compound drawable sits at the button's edge.
                val d = context.getDrawable(iconRes)?.mutate()
                if (d != null) {
                    d.setTint(color)
                    d.setBounds(0, 0, iconSize(context, 18f), iconSize(context, 18f))
                    val spanned = android.text.SpannableString("\u0000  $label")
                    spanned.setSpan(CenteredIcon(d), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text = spanned
                }
            }
        }

    // An icon drawn in a line of text, centred on it vertically
    // NOTE: ImageSpan can only centre from api 29, and the app starts at 21.
    class CenteredIcon(private val drawable: Drawable) : android.text.style.ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            drawable.bounds.right

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            canvas.save()
            val metrics = paint.fontMetricsInt
            val centre = y + (metrics.ascent + metrics.descent) / 2f
            canvas.translate(x, centre - drawable.bounds.bottom / 2f)
            drawable.draw(canvas)
            canvas.restore()
        }
    }

    // Dims a button that cannot be pressed, and says so to accessibility
    // NOTE: Recursive, so a switch inside a disabled row cannot be pressed
    //       on its own either.
    fun enable(view: View, enabled: Boolean) {
        view.alpha = if (enabled) 1f else 0.45f
        setEnabledDeep(view, enabled)
    }

    private fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (i in 0 until view.childCount) setEnabledDeep(view.getChildAt(i), enabled)
    }

    // A tappable list row: icon, label, value, chevron
    fun row(
        context: Context, label: String, value: String? = null, iconRes: Int = 0, iconColor: Int = Palette.PRIMARY,
        valueMono: Boolean = true, chevron: Boolean = true, onClick: (() -> Unit)? = null,
    ): LinearLayout = horizontal(context, 12f).apply {
        minimumHeight = context.dp(50)
        setPadding(context.dp(15), context.dp(10), context.dp(15), context.dp(10))
        if (iconRes != 0) addView(icon(context, iconRes, iconColor))
        addView(text(context, label, 14.5f, Palette.TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (value != null) {
            addView((if (valueMono) monoText(context, value, 12f) else text(context, value, 12f, Palette.DIM)).apply {
                tag = "value"
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        if (chevron && onClick != null) addView(icon(context, R.drawable.ic_chevron, Palette.FAINT, 15f))
        if (onClick != null) {
            isClickable = true
            background = pressable(context, null, 0f)
            setOnClickListener { onClick() }
        }
    }

    // Updates the value a row shows
    fun setRowValue(row: View, value: String, color: Int = Palette.DIM) {
        (row.findViewWithTag<TextView>("value"))?.apply {
            text = value
            setTextColor(color)
        }
    }

    // A two-line row with a switch, for a setting
    fun switchRow(
        context: Context, label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit,
    ): Pair<LinearLayout, Toggle> {
        val toggle = Toggle(context).apply {
            this.checked = checked
            contentDescription = label
            this.onChange = onChange
        }
        val row = horizontal(context, 12f).apply {
            minimumHeight = context.dp(54)
            setPadding(context.dp(15), context.dp(10), context.dp(15), context.dp(10))
            val words = vertical(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(text(context, label, 14f, Palette.TEXT))
                if (detail != null) addView(text(context, detail, 11f, Palette.DIM).apply {
                    tag = "detail"
                    setPadding(0, context.dp(3), 0, 0)
                })
            }
            addView(words)
            addView(toggle)
            isClickable = true
            background = pressable(context, null, 0f)
            setOnClickListener { toggle.toggle() }
        }
        return row to toggle
    }

    // A fact on the install and home screens: a mark, a label, a value
    fun fact(context: Context, ok: Boolean?, label: String, value: String, valueColor: Int = Palette.DIM): LinearLayout =
        horizontal(context, 11f).apply {
            setPadding(context.dp(15), context.dp(13), context.dp(15), context.dp(13))
            val (res, color) = when (ok) {
                true -> R.drawable.ic_check to Palette.RUNNING
                false -> R.drawable.ic_alert to Palette.CAUTION
                null -> R.drawable.ic_circle to Palette.DIM
            }
            addView(icon(context, res, color, 16f))
            addView(text(context, label, 13.5f, Palette.TEXT_SOFT).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(monoText(context, value, 11.5f, valueColor))
        }

    // A stat tile on the running Home card
    fun stat(context: Context, value: String, label: String, valueColor: Int = Palette.TEXT): LinearLayout =
        vertical(context).apply {
            background = rounded(context, Palette.INSET, 10f)
            setPadding(context.dp(8), context.dp(10), context.dp(8), context.dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(context, value, 17f, valueColor, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { tag = "value" })
            addView(text(context, label, 10.5f, Palette.DIM).apply { setPadding(0, context.dp(4), 0, 0) })
        }

    // Colours a line of DebDroid's output the way the canvas shows it
    // NOTE: The scripts' own structure, ":: " sections and " -> " steps (see
    //       docs/reference/output.md), is what is coloured, so nothing here
    //       depends on the wording of any message.
    fun styleLine(line: String, into: SpannableStringBuilder) {
        val start = into.length
        into.append(line).append('\n')
        val end = into.length - 1
        val color = when {
            line.startsWith(":: ") -> Palette.PRIMARY
            line.startsWith("Error:") || line.startsWith("Warning:") || line.startsWith("    ") && into.length > 0 &&
                lastKindWasProblem(into, start) -> Palette.CAUTION
            line.startsWith(" -> ") -> Palette.TEXT_MID
            else -> Palette.OUTPUT
        }
        into.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (line.startsWith(":: ")) into.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // A hint line under an error stays amber, as error and warn indent them
    private fun lastKindWasProblem(text: SpannableStringBuilder, before: Int): Boolean {
        if (before == 0) return false
        val spans = text.getSpans(before - 1, before - 1, ForegroundColorSpan::class.java)
        return spans.any { it.foregroundColor == Palette.CAUTION }
    }

    // The live output of a command, stdout and stderr merged
    class Output(context: Context) : ScrollView(context) {
        private val view = TextView(context).apply {
            typeface = mono
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(context, 11.5f))
            setTextColor(Palette.OUTPUT)
            setLineSpacing(0f, 1.35f)
            setTextIsSelectable(true)
            setPadding(context.dp(13), context.dp(12), context.dp(13), context.dp(12))
        }
        private var shown = 0
        private val builder = SpannableStringBuilder()

        init {
            addView(view)
            background = rounded(context, Palette.BARS, 12f, Palette.LINE)
            isFillViewport = true
        }

        // Shows the lines, appending only what is new
        fun show(lines: List<String>) {
            if (lines.size < shown) {
                builder.clear()
                shown = 0
            }
            if (lines.size == shown) return
            for (i in shown until lines.size) styleLine(lines[i], builder)
            shown = lines.size
            view.text = builder
            post { fullScroll(View.FOCUS_DOWN) }
        }

        // Shows different text altogether, rather than more of the same
        fun replace(lines: List<String>) {
            builder.clear()
            shown = 0
            show(lines)
        }

        fun plainText(): String = builder.toString()
    }

    // A switch drawn the way the canvas draws it
    // NOTE: The framework Switch can only be tinted from api 23, and the app
    //       starts at 21, so this one is drawn by hand.
    @SuppressLint("ClickableViewAccessibility")
    class Toggle(context: Context) : View(context) {
        var checked = false
            set(value) {
                field = value
                invalidate()
            }
        var onChange: (Boolean) -> Unit = {}
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        init {
            isClickable = true
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            layoutParams = LinearLayout.LayoutParams(iconSize(context, 46f), iconSize(context, 28f))
            setOnClickListener { toggle() }
        }

        fun toggle() {
            checked = !checked
            onChange(checked)
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            rect.set(0f, 0f, w, h)
            paint.color = if (checked) Palette.PRIMARY else Palette.LINE_INSET
            canvas.drawRoundRect(rect, h / 2, h / 2, paint)
            val r = h / 2 - context.dp(3)
            paint.color = if (checked) Color.WHITE else Palette.DIM
            val cx = if (checked) w - h / 2 else h / 2
            canvas.drawCircle(cx, h / 2, r, paint)
        }

        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.isCheckable = true
            info.isChecked = checked
            info.className = "android.widget.Switch"
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = super.onTouchEvent(event)
    }

    // A full-height page: a scrolling body over pinned actions
    // NOTE: The actions never scroll away, so no button is pushed off a small
    //       screen, and the body grows with large text instead of being cut.
    class Page(context: Context) : LinearLayout(context) {
        val body: LinearLayout = vertical(context, 0f)
        val actions: LinearLayout = vertical(context, 0f)
        private val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        init {
            orientation = VERTICAL
            setBackgroundColor(Palette.GROUND)
            addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(actions, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // A screen's top bar with a back arrow and a title
    fun backBar(context: Context, title: String, onBack: () -> Unit): LinearLayout = horizontal(context, 6f).apply {
        setPadding(context.dp(8), context.dp(14), context.dp(16), context.dp(8))
        addView(ImageView(context).apply {
            setImageDrawable(context.getDrawable(R.drawable.ic_back)?.mutate()?.also { it.setTint(Palette.TEXT_SOFT) })
            val pad = context.dp(10)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(44))
            contentDescription = "Back"
            background = pressable(context, null, 10f)
            setOnClickListener { onBack() }
        })
        addView(title(context, title, 21f))
    }

    fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun margins(context: Context, left: Float = 0f, top: Float = 0f, right: Float = 0f, bottom: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(context.dp(left), context.dp(top), context.dp(right), context.dp(bottom))
        }

    fun weighted(weight: Float) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)

    // Formats a byte count for display, the way the shell sizes read
    fun size(bytes: Long?): String {
        if (bytes == null) return ""
        val gb = bytes / (1L shl 30).toDouble()
        return if (gb >= 1) String.format(java.util.Locale.US, "%.1f GB", gb)
        else String.format(java.util.Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    }
}
