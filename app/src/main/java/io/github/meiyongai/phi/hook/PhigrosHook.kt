package io.github.meiyongai.phi.hook

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.meiyongai.phi.patcher.MemoryPatcher
import io.github.meiyongai.phi.ui.FloatWindowManager

/**
 * Phigros 游戏生命周期挂钩与上下文桥接器
 *
 * 负责在宿主 UnityPlayerActivity 启动与创建就绪时，
 * 初始化游戏内悬浮控制器，并触发原生底层内存基地址探测与环境就绪流程。
 */
object PhigrosHook {

    private const val TAG = "Phi-Hook"
    private const val GAME_ACTIVITY_NAME = "com.unity3d.player.UnityPlayerActivity"

    private var activeWindowManager: FloatWindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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

        // 延迟短时间确保 DecorView 与窗口 Token 挂载就绪
        mainHandler.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) {
                return@postDelayed
            }

            Log.i(TAG, "初始化 Phigros 悬浮控制器与内存修补引擎")
            MemoryPatcher.initialize()

            val windowManager = FloatWindowManager(activity)
            windowManager.attach()
            activeWindowManager = windowManager
        }, 1200L)
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
