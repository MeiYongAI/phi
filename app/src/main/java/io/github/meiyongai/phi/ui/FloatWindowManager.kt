package io.github.meiyongai.phi.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import io.github.meiyongai.phi.config.ConfigManager

/**
 * 游戏内悬浮交互窗口管理器
 *
 * 负责依附于当前游戏的 Activity 运行环境，创建并维护半透明浮动气泡按钮及可折叠操作面板，
 * 并基于底层 SurfaceControl.Transaction.setSkipScreenshot 机制实现截屏录屏忽略浮窗特性。
 *
 * @param activity 当前宿主游戏主 Activity 实例
 * @see io.github.meiyongai.phi.hook.PhigrosHook
 */
class FloatWindowManager(private val activity: Activity) {

    private val windowManager: WindowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val bubbleView: SecureFrameLayout
    private val bubbleParams: WindowManager.LayoutParams
    private val panelContainer: SecureFrameLayout
    private val panelParams: WindowManager.LayoutParams
    private val panelView: FloatPanelView

    private var isPanelExpanded: Boolean = false
    private var isSecureEnabled: Boolean = ConfigManager.isSecureScreenEnabled()

    init {
        // 构建悬浮球视图：半透明圆形质感按钮
        bubbleView = SecureFrameLayout(activity).apply {
            val size = dpToPx(52)
            val bubbleDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC2A2A38"))
                setStroke(dpToPx(2), Color.parseColor("#8888AAFF"))
            }
            background = bubbleDrawable

            val label = object : View(activity) {
                private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics)
                    typeface = Typeface.DEFAULT_BOLD
                }
                private val textBounds = Rect()

                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val text = "φ"
                    textPaint.getTextBounds(text, 0, text.length, textBounds)
                    val x = (width / 2f) - textBounds.exactCenterX()
                    val y = (height / 2f) - textBounds.exactCenterY()
                    canvas.drawText(text, x, y, textPaint)
                }
            }
            addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        // 悬浮球布局参数
        bubbleParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
            token = activity.window.decorView.windowToken
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
            width = dpToPx(52)
            height = dpToPx(52)
            x = dpToPx(20)
            y = dpToPx(120)
        }

        // 悬浮球拖拽与点击判定
        setupBubbleTouchListener()

        // 构建折叠面板容器
        panelContainer = SecureFrameLayout(activity).apply {
            visibility = View.GONE
        }
        panelView = FloatPanelView(activity, this)
        val panelContentParams = FrameLayout.LayoutParams(dpToPx(280), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        panelContainer.addView(panelView, panelContentParams)

        // 面板布局参数：居中模态覆盖
        panelParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
            token = activity.window.decorView.windowToken
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            gravity = Gravity.CENTER
            width = dpToPx(300)
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }

        if (isSecureEnabled) {
            setSecureScreenEnabled(true)
        }
    }

    /**
     * 将悬浮窗组件挂载至当前游戏 Activity 视窗
     *
     * @see io.github.meiyongai.phi.hook.PhigrosHook#onGameActivityCreated
     */
    fun attach() {
        if (bubbleView.parent == null) {
            windowManager.addView(bubbleView, bubbleParams)
        }
        if (panelContainer.parent == null) {
            windowManager.addView(panelContainer, panelParams)
        }
    }

    /**
     * 将悬浮窗组件从当前游戏视窗分离移除
     *
     * @see io.github.meiyongai.phi.hook.PhigrosHook#onGameActivityDestroyed
     */
    fun detach() {
        bubbleView.isSecure = false
        panelContainer.isSecure = false

        if (panelContainer.parent != null) {
            windowManager.removeViewImmediate(panelContainer)
        }
        if (bubbleView.parent != null) {
            windowManager.removeViewImmediate(bubbleView)
        }
    }

    /**
     * 设置悬浮球触摸事件监听，实现自由拖曳吸边与点击展开
     *
     * @see io.github.meiyongai.phi.ui.FloatWindowManager#init
     */
    private fun setupBubbleTouchListener() {
        bubbleView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoving = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams.x
                        initialY = bubbleParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()
                        if (Math.hypot(deltaX.toDouble(), deltaY.toDouble()) > 10) {
                            isMoving = true
                            bubbleParams.x = initialX + deltaX
                            bubbleParams.y = initialY + deltaY
                            windowManager.updateViewLayout(bubbleView, bubbleParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoving) {
                            togglePanel()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    /**
     * 切换控制面板的展开与折叠状态
     *
     * @see io.github.meiyongai.phi.ui.FloatWindowManager#setupBubbleTouchListener
     */
    fun togglePanel() {
        if (isPanelExpanded) {
            collapsePanel()
        } else {
            expandPanel()
        }
    }

    /**
     * 展开控制面板
     *
     * @see io.github.meiyongai.phi.ui.FloatWindowManager#togglePanel
     */
    fun expandPanel() {
        isPanelExpanded = true
        panelContainer.visibility = View.VISIBLE
    }

    /**
     * 折叠并隐藏控制面板
     *
     * @see io.github.meiyongai.phi.ui.FloatPanelView
     */
    fun collapsePanel() {
        isPanelExpanded = false
        panelContainer.visibility = View.GONE
    }

    /**
     * 动态设置是否在截屏与录屏时忽略隐藏悬浮窗
     *
     * 基于 SurfaceControl.Transaction.setSkipScreenshot 机制，
     * 消除系统级截屏阻止与“该页面无法截屏”提示，实现截图录屏时的自然隐形穿透。
     *
     * Args:
     *     enabled (Boolean): 是否开启截屏/录屏忽略悬浮窗。
     *
     * Callers:
     *     - `FloatPanelView`: 开关切换时调用。
     */
    fun setSecureScreenEnabled(enabled: Boolean) {
        isSecureEnabled = enabled
        bubbleView.isSecure = enabled
        panelContainer.isSecure = enabled
    }

    /**
     * 查询当前防截屏状态
     *
     * @return true 为启用截屏忽略，false 为未启用
     * @see io.github.meiyongai.phi.ui.FloatPanelView
     */
    fun isSecureScreenEnabled(): Boolean {
        return isSecureEnabled
    }

    /**
     * 密度像素值转换绝对像素值
     *
     * @param dp 密度像素值
     * @return 实际屏幕物理像素整数值
     * @see io.github.meiyongai.phi.ui.FloatWindowManager#init
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * activity.resources.displayMetrics.density).toInt()
    }

    /**
     * 支持自适应截屏/录屏忽略特性的原生安全悬浮容器
     *
     * 内部深度融合 View 的 dispatchDraw 与 onWindowFocusChanged 机制：
     * 仅在 View 处于真实绘制阶段时向系统底层 SurfaceControl 施加 setSkipScreenshot 标记，
     * 当应用退至后台或失去焦点时自然重置，切回前台重绘时自动重新施加，
     * 无需外部维护繁杂的生命周期监听与状态缓存。
     *
     * @param context Android 上下文环境
     */
    private open class SecureFrameLayout(context: Context) : FrameLayout(context) {

        /** 是否开启截屏/录屏忽略特性 */
        var isSecure: Boolean = false
            set(value) {
                if (field != value) {
                    field = value
                    if (!value) {
                        applySkip(false)
                    }
                    isApplied = false
                    invalidate()
                }
            }

        /** 底层 Native 图层当前是否已成功施加标记 */
        private var isApplied: Boolean = false

        /**
         * 响应视窗可见性变化
         *
         * @param visibility 目标可见状态 (View.VISIBLE / GONE / INVISIBLE)
         */
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility != View.VISIBLE) {
                isApplied = false
            }
        }

        /**
         * 响应窗口焦点变动
         *
         * 当应用进入后台或拉下通知栏时标记失效；重新恢复前台获得焦点时触发重新应用。
         *
         * @param hasWindowFocus 当前窗口是否持有焦点
         */
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (!hasWindowFocus) {
                isApplied = false
            } else if (isSecure) {
                invalidate()
            }
        }

        /**
         * 视图子组件绘制分发拦截
         *
         * 在 View 实际绘制的第一帧，此时 ViewRootImpl 中的 SurfaceControl 必然已完成 Native 初始化，
         * 执行底层 setSkipScreenshot 标记应用。
         *
         * @param canvas 绘制画布
         */
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (isSecure && !isApplied) {
                if (applySkip(true)) {
                    isApplied = true
                }
            }
        }

        /**
         * 对当前视窗关联的底层 SurfaceControl 提交 setSkipScreenshot 事务
         *
         * @param skip true 为在截屏/录屏中忽略隐藏，false 为正常捕获
         * @return 事务提交成功返回 true，底层尚未就绪返回 false
         */
        private fun applySkip(skip: Boolean): Boolean {
            val getViewRootImplMethod = View::class.java.getMethod("getViewRootImpl")
            val viewRootImpl = getViewRootImplMethod.invoke(this) ?: return false
            val getSurfaceControlMethod = viewRootImpl.javaClass.getMethod("getSurfaceControl")
            val surfaceControl = getSurfaceControlMethod.invoke(viewRootImpl) ?: return false

            val surfaceControlClass = Class.forName("android.view.SurfaceControl")
            val isValidMethod = surfaceControlClass.getMethod("isValid")
            if ((isValidMethod.invoke(surfaceControl) as? Boolean) != true) {
                return false
            }

            val transactionClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val transaction = transactionClass.getConstructor().newInstance()
            val setSkipMethod = transactionClass.getMethod("setSkipScreenshot", surfaceControlClass, Boolean::class.javaPrimitiveType)
            setSkipMethod.invoke(transaction, surfaceControl, skip)
            val applyMethod = transactionClass.getMethod("apply")
            applyMethod.invoke(transaction)
            return true
        }
    }
}
