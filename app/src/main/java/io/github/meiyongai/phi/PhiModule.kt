package io.github.meiyongai.phi

import android.app.Activity
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.meiyongai.phi.hook.PhigrosHook
import io.github.meiyongai.phi.nativebridge.NativeGame

/**
 * phi 模块主入口
 *
 * 基于现代 LibXposed API 102 规范构建，负责拦截游戏目标进程生命周期，
 * 并在 Unity 游戏主窗口加载完成时挂载游戏内控制悬浮窗及判定挂钩逻辑。
 *
 * @see io.github.meiyongai.phi.hook.PhigrosHook
 */
class PhiModule : XposedModule() {

    companion object {
        private const val TAG = "Phi-Module"
        private const val TARGET_PACKAGE = "com.PigeonGames.Phigros"
    }

    /**
     * 目标应用包加载生命周期回调
     *
     * @param param 包加载上下文参数，包含包名与 ClassLoader
     * @see io.github.libxposed.api.XposedModule
     */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)

        if (param.packageName != TARGET_PACKAGE) {
            return
        }

        Log.i(TAG, "已成功挂载至目标游戏进程: ${param.packageName}")
        // Register native library callbacks before Unity loads libil2cpp.so.
        NativeGame.load()

        // 挂钩 Activity.onPostCreate 方法
        val onPostCreateMethod = Activity::class.java.getDeclaredMethod("onPostCreate", Bundle::class.java)
        hook(onPostCreateMethod).intercept(object : Hooker {
            /**
             * 拦截 Activity.onPostCreate 并在其执行完成后初始化游戏悬浮控制器
             *
             * @param chain 拦截链上下文
             * @return 目标方法返回值
             * @see io.github.meiyongai.phi.hook.PhigrosHook#onGameActivityCreated
             */
            override fun intercept(chain: Chain): Any? {
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null) {
                    val bundle = chain.getArg(0) as? Bundle
                    PhigrosHook.onGameActivityCreated(activity, bundle)
                }
                return result
            }
        })

        // 挂钩 Activity.onDestroy 方法
        val onDestroyMethod = Activity::class.java.getDeclaredMethod("onDestroy")
        hook(onDestroyMethod).intercept(object : Hooker {
            /**
             * 拦截 Activity.onDestroy 并在销毁时分离悬浮窗图层
             *
             * @param chain 拦截链上下文
             * @return 目标方法返回值
             * @see io.github.meiyongai.phi.hook.PhigrosHook#onGameActivityDestroyed
             */
            override fun intercept(chain: Chain): Any? {
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null) {
                    PhigrosHook.onGameActivityDestroyed(activity)
                }
                return result
            }
        })
    }
}
