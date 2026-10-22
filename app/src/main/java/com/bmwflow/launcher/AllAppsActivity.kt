package com.bmwflow.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

/** Экран «Все остальные»: установленные приложения, кроме восьми основных плиток. */
class AllAppsActivity : Activity() {

    private class Item(val entry: AppEntry, val icon: Drawable?)

    private lateinit var st: Stage
    private lateinit var grid: GridView
    private lateinit var status: TextView
    private val items = ArrayList<Item>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Stage.forActivity(this)

        val root = FrameLayout(this).apply { setBackgroundColor(FLOW_BG) }
        val stage = FrameLayout(this)
        root.addView(stage, FrameLayout.LayoutParams(st.px(Stage.W), st.px(Stage.H), Gravity.CENTER))

        val back = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedFill(st, 0xFF161C2A.toInt(), 32f, 0x332E8BFF)
            setPadding(st.px(16f), 0, st.px(26f), 0)
            isClickable = true
            setOnClickListener { finish() }
            pressAnimation()
        }
        back.addView(ImageView(this).apply { setImageResource(R.drawable.ic_back) },
            LinearLayout.LayoutParams(st.px(30f), st.px(30f)))
        back.addView(TextView(this).apply {
            text = "BMW Flow"
            setTextColor(Color.WHITE); typeface = Fonts.medium; sizeU(st, 22f)
            setPadding(st.px(8f), 0, 0, 0)
        })
        stage.place(back, st, 24f, 20f, 200f, 64f)

        val title = TextView(this).apply {
            text = "Все приложения"
            setTextColor(Color.WHITE); typeface = Fonts.medium; sizeU(st, 30f)
            gravity = Gravity.CENTER_VERTICAL
        }
        stage.place(title, st, 252f, 20f, 700f, 64f)

        grid = GridView(this).apply {
            numColumns = 8
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = st.px(14f)
            horizontalSpacing = st.px(12f)
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, st.px(6f), 0, st.px(24f))
            adapter = Adapter()
            setOnItemClickListener { _, _, pos, _ ->
                val e = items[pos].entry
                if (Apps.launchComponent(this@AllAppsActivity, e)) finish()
                else Toast.makeText(this@AllAppsActivity, "Не удалось открыть «${e.label}»", Toast.LENGTH_SHORT).show()
            }
        }
        stage.place(grid, st, 24f, 100f, 1552f, 500f)

        status = TextView(this).apply {
            text = "Загрузка…"
            setTextColor(0xFF8A93A6.toInt()); typeface = Fonts.regular; sizeU(st, 22f)
            gravity = Gravity.CENTER
        }
        stage.place(status, st, 0f, 260f, 1600f, 60f)

        setContentView(root)
        load()
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun load() {
        io.execute {
            val apps = Apps.launchable(this)
            val onTiles = Tiles.all.mapNotNull { Apps.resolve(this, it, apps) }.toHashSet()
            val collator = Collator.getInstance(Locale("ru"))
            val pm = packageManager
            val list = apps.filter { it.pkg !in onTiles }
                .distinctBy { it.pkg + "/" + it.activity }
                .sortedWith { a, b -> collator.compare(a.label, b.label) }
                .map { e ->
                    val icon = try {
                        pm.getActivityIcon(e.component)
                    } catch (ex: Exception) {
                        null
                    }
                    Item(e, icon)
                }
            main.post {
                if (isDestroyed) return@post
                items.clear()
                items.addAll(list)
                (grid.adapter as BaseAdapter).notifyDataSetChanged()
                status.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                if (items.isEmpty()) status.text = "Других приложений нет"
            }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = (convertView as? LinearLayout) ?: LinearLayout(this@AllAppsActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, st.px(150f))
                setPadding(0, st.px(8f), 0, 0)
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                    LinearLayout.LayoutParams(st.px(88f), st.px(88f)))
                addView(TextView(context).apply {
                    setTextColor(Color.WHITE); typeface = Fonts.regular; sizeU(st, 18f)
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(st.px(4f), st.px(8f), st.px(4f), 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val item = items[position]
            val icon = v.getChildAt(0) as ImageView
            if (item.icon != null) icon.setImageDrawable(item.icon) else icon.setImageResource(R.drawable.ic_missing)
            (v.getChildAt(1) as TextView).text = item.entry.label
            return v
        }
    }
}
