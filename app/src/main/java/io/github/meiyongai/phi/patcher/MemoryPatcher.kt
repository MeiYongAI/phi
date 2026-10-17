package io.github.meiyongai.phi.patcher

import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * 补丁注入目标描述结构体
 *
 * @property name 目标注入点功能与模块描述
 * @property rva 相对虚拟地址 (Relative Virtual Address)
 * @property patchBytes 目标注入的 ARM64 机器指令序列
 */
data class PatchEntry(
    val name: String,
    val rva: Long,
    val patchBytes: ByteArray
)

/**
 * Phigros 原生内存补丁管理器
 *
 * 负责解析游戏进程内部 libil2cpp.so 动态链接库的内存加载基地址，
 * 并提供全自动打歌（AutoPlay）的原生热修补机制。
 */
object MemoryPatcher {

    private const val TAG = "Phi-MemoryPatcher"
    private const val TARGET_SO_NAME = "libil2cpp.so"
    private const val PAGE_SIZE = 4096L

    /**
     * Linux / Android 内存保护常量定义 (sys/mman.h)
     */
    private const val PROT_READ = 1
    private const val PROT_WRITE = 2
    private const val PROT_EXEC = 4

    /**
     * ARM64 指令：NOP 空指令 (0x1F2003D5)
     */
    /**
     * ARM64 指令：NOP 空指令 (0xD503201F)
     */
    private val ARM64_NOP = byteArrayOf(
        0x1F.toByte(), 0x20.toByte(), 0x03.toByte(), 0xD5.toByte()
    )

    /**
     * ARM64 指令：FMOV S9, S8 浮点寄存器直通复制 (0x1E204109)
     * 取消 Hold 头部判定时对时差的取绝对值操作，保留带符号时差 realTime - nowTime
     */
    private val PATCH_FMOV_S9_S8 = byteArrayOf(
        0x09.toByte(), 0x41.toByte(), 0x20.toByte(), 0x1E.toByte()
    )

    /**
     * ARM64 指令：FMOV S0, WZR 载入 0.0 单精度浮点零值 (0x1E2703E0)
     * 将判定基准时间窗口严格归零收窄至 0.0s 触线理论值时刻
     */
    private val PATCH_FMOV_S0_WZR = byteArrayOf(
        0xE0.toByte(), 0x03.toByte(), 0x27.toByte(), 0x1E.toByte()
    )

    /**
     * ARM64 指令：FMOV S2, WZR 载入 0.0 单精度浮点零值 (0x1E2703E2)
     * 将 Flick 判定基准时间窗口严格归零收窄至 0.0s 触线理论值时刻
     */
    private val PATCH_FMOV_S2_WZR = byteArrayOf(
        0xE2.toByte(), 0x03.toByte(), 0x27.toByte(), 0x1E.toByte()
    )

    /**
     * ClickControl.Judge 浮点时间守卫指令块 (12 字节)
     *
     * 位于 0x1D821E0，替换原 TST W8,#0xFF / STRB W8,[X19,#0x60] / B.EQ loc_1D822CC 三条指令：
     *   FMOV S0, WZR       ; 载入 0.0 作为比较基准
     *   FCMP S9, S0        ; 比较 S9 = realTime - nowTime 与 0.0
     *   B.GT loc_1D822CC   ; 音符未到判定线 (S9 > 0) 则跳转等待，已到则顺延
     * 保留后续 0x1D821EC LDR X0,[X20] 原始 ScoreControl 初始化路径。
     */
    private val PATCH_CLICK_TIMEGUARD = byteArrayOf(
        0xE0.toByte(), 0x03.toByte(), 0x27.toByte(), 0x1E.toByte(),  // FMOV S0, WZR
        0x20.toByte(), 0x21.toByte(), 0x20.toByte(), 0x1E.toByte(),  // FCMP S9, S0
        0x2C.toByte(), 0x07.toByte(), 0x00.toByte(), 0x54.toByte()   // B.GT loc_1D822CC (imm19=57)
    )

    /**
     * ARM64 指令：FMOV S8, WZR 载入 0.0 单精度浮点零值 (0x1E2703E8)
     * 取消 FABS S8,S9 对时差取绝对值，使后续 FCMP S8,S0 始终给出 Equal，
     * 配合 LoadZero 与 PassThrough 将判定强制落在 Perfect 分支。
     */
    private val PATCH_FMOV_S8_WZR = byteArrayOf(
        0xE8.toByte(), 0x03.toByte(), 0x27.toByte(), 0x1E.toByte()   // FMOV S8, WZR
    )

    /**
     * HoldControl.Judge 头部未到判定线时重定向跳转到等待分支 (b.pl 0x01D83EBC，指令码 0x54001325)
     */
    private val PATCH_HOLD_HEAD_REDIRECT_WAIT = byteArrayOf(
        0x25.toByte(), 0x13.toByte(), 0x00.toByte(), 0x54.toByte()
    )

    /**
     * HoldControl.Judge 重置 isHoldBroken 字段 (0x3901827F)
     */
    private val PATCH_STRB_WZR_X19_8D = byteArrayOf(
        0x7F.toByte(), 0x82.toByte(), 0x01.toByte(), 0x39.toByte()
    )

    /**
     * HoldControl.Judge 身体检测入口重定向跳转到尾部结算 (b 0x01D83F98，指令码 0x1400002E)
     */
    private val PATCH_HOLD_BODY_SKIP_TO_TAIL = byteArrayOf(
        0x2E.toByte(), 0x00.toByte(), 0x00.toByte(), 0x14.toByte()
    )

    /**
     * Phigros 4.0.1 (arm64-v8a) 零误差理论值自动打歌（Zero-Error AutoPlay）注入表
     */
    private val autoPlayEntries = listOf(
        PatchEntry("ClickControl.TimeGuard",    0x1D821E0L, PATCH_CLICK_TIMEGUARD),
        PatchEntry("ClickControl.NoFabs",        0x1D821F0L, PATCH_FMOV_S8_WZR),
        PatchEntry("ClickControl.LoadZero",      0x1D82208L, PATCH_FMOV_S0_WZR),
        PatchEntry("ClickControl.PassThrough",   0x1D82210L, ARM64_NOP),
        PatchEntry(
            name = "DragControl.LoadZero",
            rva = 0x1D829ECL,
            patchBytes = PATCH_FMOV_S0_WZR
        ),
        PatchEntry(
            name = "DragControl.PassThrough",
            rva = 0x1D82A00L,
            patchBytes = ARM64_NOP
        ),
        PatchEntry(
            name = "FlickControl.LoadZero",
            rva = 0x1D82F64L,
            patchBytes = PATCH_FMOV_S2_WZR
        ),
        PatchEntry(
            name = "FlickControl.PassThrough",
            rva = 0x1D82F80L,
            patchBytes = ARM64_NOP
        ),
        PatchEntry(
            name = "HoldControl.HeadPassThrough",
            rva = 0x1D83C30L,
            patchBytes = ARM64_NOP
        ),
        PatchEntry(
            name = "HoldControl.NoFabs",
            rva = 0x1D83C38L,
            patchBytes = PATCH_FMOV_S9_S8
        ),
        PatchEntry(
            name = "HoldControl.LoadZero",
            rva = 0x1D83C50L,
            patchBytes = PATCH_FMOV_S0_WZR
        ),
        PatchEntry(
            name = "HoldControl.HeadRedirectWait",
            rva = 0x1D83C58L,
            patchBytes = PATCH_HOLD_HEAD_REDIRECT_WAIT
        ),
        PatchEntry(
            name = "HoldControl.PreventBroken",
            rva = 0x1D83EDCL,
            patchBytes = PATCH_STRB_WZR_X19_8D
        ),
        PatchEntry(
            name = "HoldControl.BodySkipToTail",
            rva = 0x1D83EE0L,
            patchBytes = PATCH_HOLD_BODY_SKIP_TO_TAIL
        )
    )

    /**
     * 零元购（Free Purchase）注入表
     *
     * 针对 MB 货币购买解锁的曲目实施价格归零与扣款豁免：
     * 1. SongSelectorItem..ctor (RVA: 0x1DAE724):
     *    将价格构造参数强制归零 (MOV W0, WZR)，使选曲界面中 MB 曲目的展示价格变更为 0 MB；
     * 2. MoneyControl.SpendMoney (RVA: 0x1CAA034):
     *    直接返回扣款成功状态码 (MOV W0, #1; RET)，豁免 MB 余额校验与扣减，
     *    触发游戏原生曲目解锁与本地持久化存档写入。
     */
    private val freePurchaseEntries = listOf(
        PatchEntry(
            name = "SongSelectorItem.SetZeroPrice",
            rva = 0x1DAE724L,
            patchBytes = byteArrayOf(
                0xE0.toByte(), 0x03.toByte(), 0x1F.toByte(), 0x2A.toByte()
            )
        ),
        PatchEntry(
            name = "MoneyControl.SpendMoney",
            rva = 0x1CAA034L,
            patchBytes = byteArrayOf(
                0x20.toByte(), 0x00.toByte(), 0x80.toByte(), 0x52.toByte(),
                0xC0.toByte(), 0x03.toByte(), 0x5F.toByte(), 0xD6.toByte()
            )
        )
    )

    private val originalByteBackups = mutableMapOf<Long, ByteArray>()
    private var baseAddress: Long = 0L
    private var isAutoPlayApplied: Boolean = false
    private var isFreePurchaseApplied: Boolean = false

    /**
     * JNA 绑定的 POSIX 标准 C 运行时接口
     */
    interface CLibrary : Library {
        /**
         * 修改虚拟内存页面保护权限
         *
         * @param addr 页面对齐的内存起始地址
         * @param len 保护内存区域长度
         * @param prot 目标保护标志位
         * @return 成功返回 0，失败返回 -1
         */
        fun mprotect(addr: Pointer, len: Long, prot: Int): Int

        companion object {
            val INSTANCE: CLibrary by lazy {
                Native.load("c", CLibrary::class.java)
            }
        }
    }

    /**
     * 解析 /proc/self/maps 获取目标动态链接库的内存加载基地址
     *
     * 遍历当前进程的虚拟内存映射表，定位指定动态库并提取其第一个代码段的绝对加载基地址。
     *
     * Args:
     *     soName (String): 目标动态链接库文件名（如 libil2cpp.so）。
     *
     * Returns:
     *     Long: 内存加载起始绝对地址，未找到时返回 0。
     *
     * Callers:
     *     - `MemoryPatcher.initialize`: 模块初始化时计算加载基址。
     */
    fun findModuleBaseAddress(soName: String): Long {
        val mapsFile = File("/proc/self/maps")
        if (!mapsFile.exists() || !mapsFile.canRead()) {
            Log.e(TAG, "无法访问 /proc/self/maps 内存映射文件")
            return 0L
        }

        BufferedReader(FileReader(mapsFile)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                if (currentLine.contains(soName)) {
                    val addressRange = currentLine.split(" ")[0]
                    val startAddrHex = addressRange.split("-")[0]
                    val parsedAddress = java.lang.Long.parseUnsignedLong(startAddrHex, 16)
                    Log.i(TAG, "定位到 $soName 基地址: 0x${java.lang.Long.toHexString(parsedAddress)}")
                    return parsedAddress
                }
            }
        }

        Log.e(TAG, "未能在 /proc/self/maps 中找到目标动态链接库: $soName")
        return 0L
    }

    /**
     * 初始化内存补丁管理器，完成基地址解析与全部注入点的原始指令备份
     *
     * 在初次调用时定位动态链接库基地址，并完整备份所有目标注入点上的原始机器码序列，
     * 以便后续随时无缝还原。
     *
     * Returns:
     *     Boolean: 初始化成功返回 true，基地址定位失败返回 false。
     *
     * Callers:
     *     - `PhigrosHook.onGameActivityCreated`: 游戏主 Activity 挂载就绪后调用。
     *     - `MemoryPatcher.setAutoPlayEnabled`: 切换自动打歌前确保就绪。
     *     - `MemoryPatcher.setFreePurchaseEnabled`: 切换零元购前确保就绪。
     */
    @Synchronized
    fun initialize(): Boolean {
        val totalExpectedBackups = autoPlayEntries.size + freePurchaseEntries.size
        if (baseAddress != 0L && originalByteBackups.size == totalExpectedBackups) {
            return true
        }

        baseAddress = findModuleBaseAddress(TARGET_SO_NAME)
        if (baseAddress == 0L) {
            Log.e(TAG, "初始化失败，无法定位 libil2cpp.so 基地址")
            return false
        }

        val allEntries = autoPlayEntries + freePurchaseEntries
        for (entry in allEntries) {
            val targetAddr = baseAddress + entry.rva
            if (!originalByteBackups.containsKey(targetAddr)) {
                val pointer = Pointer(targetAddr)
                val backup = ByteArray(entry.patchBytes.size)
                pointer.read(0, backup, 0, backup.size)
                originalByteBackups[targetAddr] = backup
                Log.d(TAG, "已备份 ${entry.name} 地址 0x${java.lang.Long.toHexString(targetAddr)} 的原始机器码")
            }
        }
        return true
    }

    /**
     * 设置目标内存区域的保护属性
     *
     * 自动对齐内存页面边界，并调用 POSIX 标准 mprotect 函数修改内存保护属性。
     *
     * Args:
     *     address (Long): 目标内存起始绝对地址。
     *     length (Long): 目标区域字节长度。
     *     prot (Int): 目标权限位组合（如 PROT_READ or PROT_WRITE or PROT_EXEC）。
     *
     * Callers:
     *     - `MemoryPatcher.writeBytesToProtectedMemory`: 写内存前开启写保护并在写入后还原。
     */
    private fun setMemoryProtection(address: Long, length: Long, prot: Int) {
        val pageStart = address and (PAGE_SIZE - 1).inv()
        val pageEnd = (address + length + PAGE_SIZE - 1) and (PAGE_SIZE - 1).inv()
        val totalLength = pageEnd - pageStart
        val ret = CLibrary.INSTANCE.mprotect(Pointer(pageStart), totalLength, prot)
        if (ret != 0) {
            val errno = Native.getLastError()
            Log.e(TAG, "mprotect 失败: address=0x${java.lang.Long.toHexString(address)}, errno=$errno")
        }
    }

    /**
     * 向受写保护的虚拟内存空间写入指定字节数组
     *
     * 自动切换目标内存页权限为可读可写可执行，写入目标机器指令后立即恢复为可读可执行。
     *
     * Args:
     *     targetAddress (Long): 目标内存起始绝对地址。
     *     data (ByteArray): 待写入的机器指令字节序列。
     *
     * Callers:
     *     - `MemoryPatcher.applyPatchEntries`: 写入或还原指定的补丁机器码序列。
     */
    private fun writeBytesToProtectedMemory(targetAddress: Long, data: ByteArray) {
        val readWriteExec = PROT_READ or PROT_WRITE or PROT_EXEC
        val readExec = PROT_READ or PROT_EXEC

        setMemoryProtection(targetAddress, data.size.toLong(), readWriteExec)
        val pointer = Pointer(targetAddress)
        pointer.write(0, data, 0, data.size)
        setMemoryProtection(targetAddress, data.size.toLong(), readExec)
    }

    /**
     * 批量应用或还原指定的补丁条目集合
     *
     * Args:
     *     entries (List<PatchEntry>): 目标补丁项列表。
     *     enabled (Boolean): true 为写入补丁机器码，false 为还原备份的原始机器码。
     *
     * Callers:
     *     - `MemoryPatcher.setAutoPlayEnabled`: 批量更新自动打歌指令。
     *     - `MemoryPatcher.setFreePurchaseEnabled`: 批量更新零元购指令。
     */
    private fun applyPatchEntries(entries: List<PatchEntry>, enabled: Boolean) {
        for (entry in entries) {
            val targetAddr = baseAddress + entry.rva
            if (enabled) {
                writeBytesToProtectedMemory(targetAddr, entry.patchBytes)
                Log.d(TAG, "已注入补丁指令: ${entry.name} at 0x${java.lang.Long.toHexString(targetAddr)}")
            } else {
                val backup = originalByteBackups[targetAddr]
                if (backup != null) {
                    writeBytesToProtectedMemory(targetAddr, backup)
                    Log.d(TAG, "已还原原始指令: ${entry.name} at 0x${java.lang.Long.toHexString(targetAddr)}")
                }
            }
        }
    }

    /**
     * 切换自动打歌功能的开启或关闭状态
     *
     * Args:
     *     enabled (Boolean): true 为开启自动打歌，false 为恢复原版逻辑。
     *
     * Returns:
     *     Boolean: 实际执行操作后的当前状态。
     *
     * Callers:
     *     - `FloatPanelView.init`: 响应自动打歌开关状态变更。
     */
    @Synchronized
    fun setAutoPlayEnabled(enabled: Boolean): Boolean {
        if (!initialize()) {
            Log.e(TAG, "初始化失败，无法切换自动打歌状态")
            return false
        }

        if (isAutoPlayApplied == enabled) {
            return isAutoPlayApplied
        }

        applyPatchEntries(autoPlayEntries, enabled)
        isAutoPlayApplied = enabled
        Log.i(TAG, "已更新自动打歌状态: enabled=$enabled")
        return isAutoPlayApplied
    }

    /**
     * 查询当前自动打歌补丁是否处于激活状态
     *
     * Returns:
     *     Boolean: true 为已开启，false 为未开启。
     *
     * Callers:
     *     - `FloatPanelView.init`: 同步开关当前状态。
     */
    fun isAutoPlayEnabled(): Boolean {
        return isAutoPlayApplied
    }

    /**
     * 切换零元购（Free Purchase）功能的开启或关闭状态
     *
     * 当开启时，将 MB 曲目价格生成参数置零并豁免扣款校验，允许直接购买曲目并写入本地永久存档；
     * 当关闭时，恢复原版价格生成与扣款机制。
     *
     * Args:
     *     enabled (Boolean): 目标开关状态，true 为开启零元购，false 为恢复原版逻辑。
     *
     * Returns:
     *     Boolean: 实际执行操作后的当前状态。
     *
     * Callers:
     *     - `FloatPanelView.init`: 响应悬浮面板中零元购开关的交互变更。
     */
    @Synchronized
    fun setFreePurchaseEnabled(enabled: Boolean): Boolean {
        if (!initialize()) {
            Log.e(TAG, "初始化失败，无法切换零元购状态")
            return false
        }

        if (isFreePurchaseApplied == enabled) {
            return isFreePurchaseApplied
        }

        applyPatchEntries(freePurchaseEntries, enabled)
        isFreePurchaseApplied = enabled
        Log.i(TAG, "已更新零元购状态: enabled=$enabled")
        return isFreePurchaseApplied
    }

    /**
     * 查询当前零元购补丁是否处于激活状态
     *
     * Returns:
     *     Boolean: true 为已开启，false 为未开启。
     *
     * Callers:
     *     - `FloatPanelView.init`: 悬浮面板展开时同步零元购开关的当前状态。
     */
    fun isFreePurchaseEnabled(): Boolean {
        return isFreePurchaseApplied
    }
}
