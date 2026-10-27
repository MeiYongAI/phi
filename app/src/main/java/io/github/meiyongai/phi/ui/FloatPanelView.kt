package io.github.meiyongai.phi.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.Switch
import android.widget.TextView
import io.github.meiyongai.phi.config.ConfigManager
import io.github.meiyongai.phi.nativebridge.NativeGame

/**
 * 游戏内控制面板视图
 *
 * 以纯代码方式构建现代暗色半透明风格的控制面板，
 * 负责提供自动打歌、一键通关、零元购与截屏录屏忽略浮窗的功能开关。
 *
 * @param context 当前运行宿主的上下文环境
 * @param windowManager 悬浮窗管理器，用于协同状态变更
 * @see io.github.meiyongai.phi.ui.FloatWindowManager
 */
class FloatPanelView(
    context: Context,
    private val windowManager: FloatWindowManager
) : LinearLayout(context) {

    private val autoPlaySwitch: Switch
    private val fastPassSwitch: Switch
    private val freePurchaseSwitch: Switch
    private val secureScreenSwitch: Switch

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(16))

        // 背景卡片设计：半透明深色圆角
        val backgroundDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#E61E1E28")) // 深紫灰微透明
            cornerRadius = dpToPx(18).toFloat()
            setStroke(dpToPx(1), Color.parseColor("#44FFFFFF"))
        }
        background = backgroundDrawable

        // 顶部标题栏（带右上角收起叉叉按钮）
        val headerLayout = RelativeLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(8)
            }
        }

        val titleTextView = TextView(context).apply {
            id = View.generateViewId()
            text = "phi"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            val titleParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START, RelativeLayout.TRUE)
                addRule(RelativeLayout.CENTER_VERTICAL, RelativeLayout.TRUE)
            }
            layoutParams = titleParams
        }
        headerLayout.addView(titleTextView)

        val closeButton = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#88FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            val closeParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END, RelativeLayout.TRUE)
                addRule(RelativeLayout.CENTER_VERTICAL, RelativeLayout.TRUE)
            }
            layoutParams = closeParams
            setOnClickListener {
                windowManager.collapsePanel()
            }
        }
        headerLayout.addView(closeButton)
        addView(headerLayout)

        // 分割线
        addView(createDivider())

        // 自动打歌开关行
        autoPlaySwitch = Switch(context).apply {
            text = "自动打歌"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isChecked = NativeGame.isAutoPlayEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                NativeGame.setAutoPlayEnabled(isChecked)
                ConfigManager.setAutoPlayEnabled(isChecked)
            }
        }
        addView(createRowContainer(autoPlaySwitch))

        // 一键通关开关行
        fastPassSwitch = Switch(context).apply {
            text = "一键通关"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isChecked = NativeGame.isFastPassEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                NativeGame.setFastPassEnabled(isChecked)
                ConfigManager.setFastPassEnabled(isChecked)
            }
        }
        addView(createRowContainer(fastPassSwitch))

        // 零元购开关行
        freePurchaseSwitch = Switch(context).apply {
            text = "零元购"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isChecked = NativeGame.isFreePurchaseEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                NativeGame.setFreePurchaseEnabled(isChecked)
                ConfigManager.setFreePurchaseEnabled(isChecked)
            }
        }
        addView(createRowContainer(freePurchaseSwitch))

        // 截屏录屏忽略开关行
        secureScreenSwitch = Switch(context).apply {
            text = "截屏/录屏忽略浮窗"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isChecked = windowManager.isSecureScreenEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                windowManager.setSecureScreenEnabled(isChecked)
                ConfigManager.setSecureScreenEnabled(isChecked)
            }
        }
        addView(createRowContainer(secureScreenSwitch))

    }

    /**
     * 创建横向包装容器并绑定整行点击交互
     *
     * @param child 内部子视图（如 Switch）
     * @return 统一布局参数且支持整行点击切换的包装容器
     * @see io.github.meiyongai.phi.ui.FloatPanelView#init
     */
    private fun createRowContainer(child: View): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(6)
                bottomMargin = dpToPx(6)
            }
            if (child is Switch) {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    child.toggle()
                }
            }
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    /**
     * 创建分割线视图
     *
     * @return 统一风格的分割线
     * @see io.github.meiyongai.phi.ui.FloatPanelView#init
     */
    private fun createDivider(): View {
        return View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(1)).apply {
                topMargin = dpToPx(4)
                bottomMargin = dpToPx(4)
            }
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
        }
    }

    /**
     * 将标准 dp 单位转换为当前设备的像素 px 值
     *
     * @param dp 待转换的 dp 数值
     * @return 对应的像素整数值
     * @see io.github.meiyongai.phi.ui.FloatPanelView
     */
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
