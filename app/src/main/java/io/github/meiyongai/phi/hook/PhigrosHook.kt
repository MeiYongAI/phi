package io.github.meiyongai.phi.hook

import android.app.Activity
import android.os.Bundle
import android.util.Log
import io.github.meiyongai.phi.nativebridge.NativeGame
import io.github.meiyongai.phi.ui.FloatWindowManager

/**
 * Phigros 游戏生命周期挂钩与上下文桥接器
 *
 * 负责在宿主 UnityPlayerActivity 启动与创建就绪时，
 * 初始化游戏内悬浮控制器，并触发原生底层运行时功能配置。
 */
object PhigrosHook {

    private const val TAG = "Phi-Hook"
    private const val GAME_ACTIVITY_NAME = "com.unity3d.player.UnityPlayerActivity"

    private var activeWindowManager: FloatWindowManager? = null

    /**
     * 响应游戏 Activity 创建完毕生命周期回调
     *
     * @param activity 宿主 Activity 实例
     * @param savedInstanceState 状态保存包
     * @see io.github.meiyongai.phi.PhiModule
     */
    fun onGameActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val className = activity.javaClass.name
        Log.i(TAG, "检测到 Activity 创建: $className")

        if (className != GAME_ACTIVITY_NAME && !className.contains("UnityPlayer")) {
            return
        }

        // 在 DecorView 挂载后附加浮窗；原生绑定由 IL2CPP 生命周期驱动
        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed) {
                return@post
            }

            Log.i(TAG, "初始化持久化配置、Phigros 悬浮控制器与原生功能开关")
            io.github.meiyongai.phi.config.ConfigManager.initialize(activity)

            // 依照已保存的持久化配置恢复功能状态
            if (io.github.meiyongai.phi.config.ConfigManager.isAutoPlayEnabled()) {
                NativeGame.setAutoPlayEnabled(true)
            }
            if (io.github.meiyongai.phi.config.ConfigManager.isFreePurchaseEnabled()) {
                NativeGame.setFreePurchaseEnabled(true)
            }
            if (io.github.meiyongai.phi.config.ConfigManager.isFastPassEnabled()) {
                NativeGame.setFastPassEnabled(true)
            }

            val windowManager = FloatWindowManager(activity)
            windowManager.attach()
            activeWindowManager = windowManager
        }
    }

    /**
     * 响应游戏 Activity 销毁生命周期回调
     *
     * @param activity 宿主 Activity 实例
     * @see io.github.meiyongai.phi.PhiModule
     */
    fun onGameActivityDestroyed(activity: Activity) {
        val className = activity.javaClass.name
        if (className == GAME_ACTIVITY_NAME || className.contains("UnityPlayer")) {
            Log.i(TAG, "游戏 Activity 销毁，分离悬浮窗口")
            activeWindowManager?.detach()
            activeWindowManager = null
        }
    }
}
