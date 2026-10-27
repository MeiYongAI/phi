package io.github.meiyongai.phi.config

import android.content.Context
import android.content.SharedPreferences

/**
 * 模块持久化配置管理器
 *
 * 负责统一维护与持久化存储模块在游戏运行环境下的功能开关及交互偏好，
 * 避免用户在每次冷启动 Phigros 时重复手动开启功能。
 */
object ConfigManager {

    private const val PREFS_NAME = "phi_preferences"
    private const val KEY_AUTO_PLAY = "key_auto_play"
    private const val KEY_FREE_PURCHASE = "key_free_purchase"
    private const val KEY_FAST_PASS = "key_fast_pass"
    private const val KEY_SECURE_SCREEN = "key_secure_screen"

    private lateinit var preferences: SharedPreferences

    /**
     * 初始化持久化存储管理器
     *
     * 绑定宿主应用上下文环境下的 SharedPreferences 实例，为后续读写操作就绪存储句柄。
     *
     * Args:
     *     context (Context): 宿主游戏 Activity 或 Application 上下文。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `PhigrosHook.onGameActivityCreated`: 游戏主 Activity 挂载就绪后首要初始化。
     */
    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 查询自动打歌是否持久化启用
     *
     * Args:
     *     无
     *
     * Returns:
     *     Boolean: true 为已保存启用状态，false 为未启用（默认值 false）。
     *
     * Callers:
     *     - `PhigrosHook.onGameActivityCreated`: 启动时按持久配置启用原生功能。
     *     - `FloatPanelView.init`: 初始化自动打歌开关状态。
     */
    fun isAutoPlayEnabled(): Boolean {
        return preferences.getBoolean(KEY_AUTO_PLAY, false)
    }

    /**
     * 保存自动打歌开关配置
     *
     * Args:
     *     enabled (Boolean): 目标开关状态。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `FloatPanelView.init`: 响应自动打歌开关点击。
     */
    fun setAutoPlayEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_PLAY, enabled).apply()
    }

    /**
     * 查询零元购是否持久化启用
     *
     * Args:
     *     无
     *
     * Returns:
     *     Boolean: true 为已保存启用状态，false 为未启用（默认值 false）。
     *
     * Callers:
     *     - `PhigrosHook.onGameActivityCreated`: 启动时按持久配置启用原生功能。
     *     - `FloatPanelView.init`: 初始化零元购开关状态。
     */
    fun isFreePurchaseEnabled(): Boolean {
        return preferences.getBoolean(KEY_FREE_PURCHASE, false)
    }

    /**
     * 保存零元购开关配置
     *
     * Args:
     *     enabled (Boolean): 目标开关状态。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `FloatPanelView.init`: 响应零元购开关点击。
     */
    fun setFreePurchaseEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_FREE_PURCHASE, enabled).apply()
    }

    /**
     * 查询一键通关（满分跳过）是否持久化启用
     *
     * Args:
     *     无
     *
     * Returns:
     *     Boolean: true 为已保存启用状态，false 为未启用（默认值 false）。
     *
     * Callers:
     *     - `PhigrosHook.onGameActivityCreated`: 启动时按持久配置启用原生功能。
     *     - `FloatPanelView.init`: 初始化一键通关开关状态。
     */
    fun isFastPassEnabled(): Boolean {
        return preferences.getBoolean(KEY_FAST_PASS, false)
    }

    /**
     * 保存一键通关（满分跳过）开关配置
     *
     * Args:
     *     enabled (Boolean): 目标开关状态。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `FloatPanelView.init`: 响应一键通关开关点击。
     */
    fun setFastPassEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_FAST_PASS, enabled).apply()
    }

    /**
     * 查询截屏与录屏忽略是否持久化启用
     *
     * Args:
     *     无
     *
     * Returns:
     *     Boolean: true 为已保存启用状态，false 为未启用（默认值 false）。
     *
     * Callers:
     *     - `PhigrosHook.onGameActivityCreated`: 启动时按持久配置配置悬浮窗。
     *     - `FloatPanelView.init`: 初始化防截屏录屏开关状态。
     */
    fun isSecureScreenEnabled(): Boolean {
        return preferences.getBoolean(KEY_SECURE_SCREEN, false)
    }

    /**
     * 保存截屏与录屏忽略开关配置
     *
     * Args:
     *     enabled (Boolean): 目标开关状态。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `FloatPanelView.init`: 响应防截屏录屏开关点击。
     */
    fun setSecureScreenEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SECURE_SCREEN, enabled).apply()
    }
}
