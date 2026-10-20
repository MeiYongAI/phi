package io.github.meiyongai.phi.patcher

import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
 * 并提供全自动打歌（AutoPlay）与零元购的原生热修补机制。
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

    /** ARM64 指令按小端编码，表中保留指令字，避免手工拆分字节。 */
    private fun arm64(vararg instructions: Long): ByteArray =
        ByteBuffer.allocate(instructions.size * Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { instructions.forEach { putInt(it.toInt()) } }
            .array()

    // Phigros 4.0.1 arm64：直接调整原生 Judge 的时间与命中分支。
    // 仅切换开关时写入/还原；不挂钩 Update，也不遍历音符列表。
    private val autoPlayEntries = listOf(
        // Click：未到线时等待；到线后保留原初始化路径并进入 Perfect。
        PatchEntry("ClickControl.TimeGuard", 0x1D821E0L,
            arm64(0x1E2703E0, 0x1E202120, 0x5400072C)), // fmov s0,wzr; fcmp s9,s0; b.gt wait
        PatchEntry("ClickControl.NoFabs", 0x1D821F0L, arm64(0x1E2703E8)), // fmov s8,wzr
        PatchEntry("ClickControl.LoadZero", 0x1D82208L, arm64(0x1E2703E0)), // fmov s0,wzr
        PatchEntry("ClickControl.PassThrough", 0x1D82210L, arm64(0xD503201F)), // nop
        // Drag / Flick：到线后判定，不依赖触摸命中标志。
        PatchEntry("DragControl.LoadZero", 0x1D829ECL, arm64(0x1E2703E0)),
        PatchEntry("DragControl.PassThrough", 0x1D82A00L, arm64(0xD503201F)),
        PatchEntry("FlickControl.LoadZero", 0x1D82F64L, arm64(0x1E2703E2)), // fmov s2,wzr
        PatchEntry("FlickControl.PassThrough", 0x1D82F80L, arm64(0xD503201F)),
        // Hold：头部按带符号时差等待，身体保持，尾部使用原生结算。
        PatchEntry("HoldControl.HeadPassThrough", 0x1D83C30L, arm64(0xD503201F)),
        PatchEntry("HoldControl.NoFabs", 0x1D83C38L, arm64(0x1E204109)), // fmov s9,s8
        PatchEntry("HoldControl.LoadZero", 0x1D83C50L, arm64(0x1E2703E0)),
        PatchEntry("HoldControl.HeadRedirectWait", 0x1D83C58L, arm64(0x54001325)), // b.pl wait
        PatchEntry("HoldControl.PreventBroken", 0x1D83EDCL, arm64(0x3901827F)), // strb wzr,[x19,#0x8d]
        PatchEntry("HoldControl.BodySkipToTail", 0x1D83EE0L, arm64(0x1400002E)) // b tail
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

    /**
     * 一键通关与满分结算（Fast Pass）注入表
     *
     * 针对打歌流程与成绩快照执行原生链路重定向：
     * 1. ProgressControl.BypassAudioWait (RVA: 0x1D86174):
     *    移除全曲播放等待分支 (NOP)，直接进入关卡收尾逻辑；
     * 2. ProgressControl.BypassCanLevelOver (RVA: 0x1D86184):
     *    移除 canLevelOver 限制条件 (NOP)，停止音频播放并标记 levelOver = true；
     * 3. ProgressControl.SetOverTimeDirectly (RVA: 0x1D861C8):
     *    将关卡结束计时器直接赋为 3.5f (FMOV S0, #3.5)，当帧满足条件并触发原生 LevelOver 结算协程；
     * 4. ScoreControl.InstantPhiResult (RVA: 0x1D8646C):
     *    从关联的 LevelInformation 中动态提取该谱面实际总音符数，直接向 LevelResultInfo
     *    注入 1000000 分、达成率 100%、全连击（All Perfect / Full Combo）且各项判定精准匹配的数据。
     */
    private val fastPassEntries = listOf(
        PatchEntry("ProgressControl.BypassAudioWait", 0x1D86174L, arm64(0xD503201F)),
        PatchEntry("ProgressControl.BypassCanLevelOver", 0x1D86184L, arm64(0xD503201F)),
        PatchEntry("ProgressControl.SetOverTimeDirectly", 0x1D861C8L, arm64(0x1E219000)),
        PatchEntry("ScoreControl.InstantPhiResult", 0x1D8646CL, arm64(
            0xF9401A68, // ldr x8, [x19, #0x30] (levelInformation)
            0xB9402D08, // ldr w8, [x8, #0x2c]  (numOfNotes)
            0x52848009, // movz w9, #0x2400
            0x72A92E89, // movk w9, #0x4974, lsl #16 (float 1000000.0f)
            0x5288480A, // movz w10, #0x4240
            0x72A001EA, // movk w10, #0xf, lsl #16   (int 1000000)
            0x2902268A, // stp w10, w9, [x20, #0x10] (IntScore, score)
            0x52A7F009, // movz w9, #0x3f80, lsl #16 (float 1.0f)
            0x29032289, // stp w9, w8, [x20, #0x18]  (percent, perfect = numOfNotes)
            0xF900129F, // str xzr, [x20, #0x20]     (good = 0, bad = 0)
            0xF900169F, // str xzr, [x20, #0x28]     (miss = 0, early = 0)
            0x2906229F, // stp wzr, w8, [x20, #0x30] (late = 0, maxCombo = numOfNotes)
            0xD503201F, // nop
            0xD503201F  // nop
        ))
    )

    private val originalByteBackups = mutableMapOf<Long, ByteArray>()
    private var baseAddress: Long = 0L
    private var isAutoPlayApplied: Boolean = false
    private var isFreePurchaseApplied: Boolean = false
    private var isFastPassApplied: Boolean = false

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
        fun mmap(addr: Pointer?, len: Long, prot: Int, flags: Int, fd: Int, offset: Long): Pointer

        companion object {
            val INSTANCE: CLibrary by lazy {
                Native.load("c", CLibrary::class.java)
            }
        }
    }

    /**
     * 解析 /proc/self/maps 获取目标动态链接库的真正加载基地址
     *
     * 遍历当前进程虚拟内存映射表，过滤孤立的只读映射片段，通过识别具有完整可执行代码段 (r-xp)
     * 的有效映射结构，提取该动态链接库运行主体的真正加载起始基地址。
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

        var candidateBase = 0L
        BufferedReader(FileReader(mapsFile)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                if (!currentLine.contains(soName)) {
                    continue
                }

                val tokens = currentLine.trim().split("\\s+".toRegex())
                if (tokens.size < 5) continue

                val addressRange = tokens[0]
                val perms = tokens[1]
                val offset = tokens[2]
                val startAddrHex = addressRange.split("-")[0]
                val startAddr = java.lang.Long.parseUnsignedLong(startAddrHex, 16)

                if (offset == "00000000" && perms.startsWith("r")) {
                    candidateBase = startAddr
                }

                if (perms.contains("x") && candidateBase != 0L) {
                    val endAddrHex = addressRange.split("-")[1]
                    val endAddr = java.lang.Long.parseUnsignedLong(endAddrHex, 16)
                    val segSize = endAddr - startAddr
                    if (segSize > 1024 * 1024) {
                        Log.i(TAG, "定位到有效 $soName 执行体基地址: 0x${java.lang.Long.toHexString(candidateBase)}")
                        return candidateBase
                    }
                }
            }
        }

        if (candidateBase != 0L) {
            Log.i(TAG, "回退使用候选 $soName 基地址: 0x${java.lang.Long.toHexString(candidateBase)}")
            return candidateBase
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
     *     - `MemoryPatcher.setFastPassEnabled`: 切换一键通关前确保就绪。
     */
    @Synchronized
    fun initialize(): Boolean {
        val totalExpectedBackups = autoPlayEntries.size + freePurchaseEntries.size + fastPassEntries.size
        if (baseAddress != 0L && originalByteBackups.size == totalExpectedBackups) {
            return true
        }

        baseAddress = findModuleBaseAddress(TARGET_SO_NAME)
        if (baseAddress == 0L) {
            Log.e(TAG, "初始化失败，无法定位 libil2cpp.so 基地址")
            return false
        }

        for (entry in autoPlayEntries + freePurchaseEntries + fastPassEntries) {
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
            error("mprotect 失败: address=0x${java.lang.Long.toHexString(address)}, errno=$errno")
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
        InstructionCache.flush(targetAddress, data.size)
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
     * 切换自动打歌补丁
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

        if (isAutoPlayApplied == enabled) return isAutoPlayApplied

        applyPatchEntries(autoPlayEntries, enabled)
        isAutoPlayApplied = enabled
        Log.i(TAG, "已更新自动打歌状态: enabled=$enabled")
        return isAutoPlayApplied
    }

    /**
     * 查询自动打歌补丁状态
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

    /**
     * 切换一键通关（满分跳过）功能的开启或关闭状态
     *
     * 当开启时，在打歌开始后直接跳过全曲演奏过程，并根据该谱面总音符数自动构造
     * 满分且无失误的结算结果，触发游戏原生结算并持久化写入本地存档；
     * 当关闭时，还原原生打歌与判定流程。
     *
     * Args:
     *     enabled (Boolean): 目标开关状态，true 为开启一键通关，false 为恢复原版逻辑。
     *
     * Returns:
     *     Boolean: 实际执行操作后的当前状态。
     *
     * Callers:
     *     - `PhigrosHook.onGameActivityCreated`: 启动时根据持久化配置恢复状态。
     *     - `FloatPanelView.init`: 响应悬浮面板中一键通关开关的交互变更。
     */
    @Synchronized
    fun setFastPassEnabled(enabled: Boolean): Boolean {
        if (!initialize()) {
            Log.e(TAG, "初始化失败，无法切换一键通关状态")
            return false
        }

        if (isFastPassApplied == enabled) {
            return isFastPassApplied
        }

        applyPatchEntries(fastPassEntries, enabled)
        isFastPassApplied = enabled
        Log.i(TAG, "已更新一键通关状态: enabled=$enabled")
        return isFastPassApplied
    }

    /**
     * 查询当前一键通关补丁是否处于激活状态
     *
     * Args:
     *     无
     *
     * Returns:
     *     Boolean: true 为已开启，false 为未开启。
     *
     * Callers:
     *     - `FloatPanelView.init`: 悬浮面板展开时同步一键通关开关的当前状态。
     */
    fun isFastPassEnabled(): Boolean {
        return isFastPassApplied
    }
}
