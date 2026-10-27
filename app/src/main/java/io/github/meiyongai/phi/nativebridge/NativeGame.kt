package io.github.meiyongai.phi.nativebridge

/** Method-level native hooks resolved from the loaded game's IL2CPP metadata. */
object NativeGame {
    private const val AUTO_PLAY = 1
    private const val FAST_PASS = 2
    private const val FREE_PURCHASE = 4

    fun load() = System.loadLibrary("phi")
    private external fun setFeature(feature: Int, enabled: Boolean)
    private external fun getFeature(feature: Int): Boolean

    fun setAutoPlayEnabled(enabled: Boolean) = setFeature(AUTO_PLAY, enabled)
    fun setFastPassEnabled(enabled: Boolean) = setFeature(FAST_PASS, enabled)
    fun setFreePurchaseEnabled(enabled: Boolean) = setFeature(FREE_PURCHASE, enabled)
    fun isAutoPlayEnabled(): Boolean = getFeature(AUTO_PLAY)
    fun isFastPassEnabled(): Boolean = getFeature(FAST_PASS)
    fun isFreePurchaseEnabled(): Boolean = getFeature(FREE_PURCHASE)
}
