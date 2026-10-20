package io.github.meiyongai.phi.patcher

import com.sun.jna.Function
import com.sun.jna.Pointer

/**
 * ARM64 处理器数据缓存与指令缓存同步刷新器
 *
 * 在受写保护的执行内存区域修改机器码后，执行跨核缓存行刷写（Clean/Invalidate）
 * 与指令同步屏障（ISB），确保 CPU 取指管线能立刻观测到最新写入的指令。
 */
internal object InstructionCache {
    private val flushFunction: Function by lazy {
        // dc cvau loop; dsb ish; ic ivau loop; dsb ish; isb; ret.
        // 每次前进 4 字节，覆盖所有缓存行，不依赖特定 CPU 的缓存行大小。
        val bytes = byteArrayOf(
            -30, 3, 0, -86, 34, 123, 11, -43, 66, 16, 0, -111,
            95, 0, 1, -21, -93, -1, -1, 84, -97, 59, 3, -43,
            32, 117, 11, -43, 0, 16, 0, -111, 31, 0, 1, -21,
            -93, -1, -1, 84, -97, 59, 3, -43, -33, 63, 3, -43,
            -64, 3, 95, -42
        )
        val libc = MemoryPatcher.CLibrary.INSTANCE
        // 函数在进程存活期间复用；代码写入后从 RW 改为 RX。
        val memory = libc.mmap(null, bytes.size.toLong(), 3, 0x22, -1, 0)
        check(Pointer.nativeValue(memory) != -1L) { "mmap failed" }
        memory.write(0, bytes, 0, bytes.size)
        check(libc.mprotect(memory, bytes.size.toLong(), 5) == 0) { "mprotect RX failed" }
        Function.getFunction(memory)
    }

    /**
     * 刷新指定虚拟内存地址范围的 CPU 数据缓存与指令缓存
     *
     * Args:
     *     address (Long): 目标内存起始绝对地址。
     *     size (Int): 刷新内存区域字节长度。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `MemoryPatcher.writeBytesToProtectedMemory`: 注入或还原机器码后同步缓存。
     */
    fun flush(address: Long, size: Int) {
        flushFunction.invokeVoid(arrayOf(Pointer(address), Pointer(address + size)))
    }
}
