package io.github.meiyongai.phi.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * phi 模块配置管理入口界面
 *
 * 供 LSPosed 模块管理器点击打开展示模块状态与运行指引，
 * 不注册桌面启动器图标，不影响宿主桌面整洁。
 */
class SettingsActivity : Activity() {

    /**
     * 初始化界面组件并展示运行状态
     *
     * @param savedInstanceState 界面状态暂存对象
     * @see android.app.Activity#onCreate
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#12121A"))
            setPadding(32, 32, 32, 32)
        }

        val titleView = TextView(this).apply {
            text = "phi - Phigros 辅助模块"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(titleView)

        val descView = TextView(this).apply {
            text = "模块已就绪。\n\n请在 LSPosed 作用域中确认勾选《Phigros》，进入游戏后左侧将自动展示游戏内悬浮交互按钮。\n\n支持自动打歌、一键通关、零元购及截屏/录屏忽略浮窗。"
            setTextColor(Color.parseColor("#A0A0B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        rootLayout.addView(descView)

        setContentView(rootLayout)
    }
}
