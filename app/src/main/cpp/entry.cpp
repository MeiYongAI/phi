#include "game.hpp"
#include "il2cpp.hpp"
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <mutex>
#include <stdexcept>
#include <string>
#include <string_view>

// LSPosed Native Hook ABI, https://github.com/LSPosed/LSPosed/wiki/Native-Hook
using OnLibraryLoaded = void (*)(const char*, void*);
struct NativeAPIEntries {
    uint32_t version;
    HookFunction hook_func;
    int (*unhook_func)(void*);
};
namespace {
HookFunction hookFunction;
int (*originalInit)(const char*);
std::once_flag libraryInitialization;
void setStatus(const std::string& value, int priority = ANDROID_LOG_INFO) {
    __android_log_print(priority, "Phi-Native", "%s", value.c_str());
}
int initializeGame(const char* domain) {
    const int result = originalInit(domain);
    try {
        game::bind(hookFunction);
        setStatus("运行时绑定就绪 · 10 个方法");
    } catch (const std::exception& e) {
        setStatus(std::string("绑定失败：") + e.what(), ANDROID_LOG_ERROR);
    }
    return result;
}
void onLibraryLoaded(const char* name, void* handle) {
    if (!std::string_view(name).ends_with("libil2cpp.so")) return;
    // Unity opens the same library from more than one thread. Subscribe once
    // per process; a repeated dlopen is not another IL2CPP runtime.
    std::call_once(libraryInitialization, [handle] {
        try {
            il2cpp::initialize(handle);
            void* init = dlsym(handle, "il2cpp_init");
            if (!init) throw std::runtime_error("Missing il2cpp_init");
            if (hookFunction(init, reinterpret_cast<void*>(initializeGame),
                             reinterpret_cast<void**>(&originalInit)) != 0)
                throw std::runtime_error("Cannot hook il2cpp_init");
            setStatus("IL2CPP 已加载，等待元数据初始化");
        } catch (const std::exception& e) {
            setStatus(std::string("绑定失败：") + e.what(), ANDROID_LOG_ERROR);
        }
    });
}
}

extern "C" [[gnu::visibility("default"), gnu::used]]
OnLibraryLoaded native_init(const NativeAPIEntries* entries) {
    hookFunction = entries->hook_func;
    setStatus("原生 Hook 已注册，等待游戏加载");
    return onLibraryLoaded;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_meiyongai_phi_nativebridge_NativeGame_setFeature(JNIEnv*, jobject, jint feature, jboolean enabled) {
    if (enabled) game::features.fetch_or(static_cast<uint32_t>(feature), std::memory_order_relaxed);
    else game::features.fetch_and(~static_cast<uint32_t>(feature), std::memory_order_relaxed);
    __android_log_print(ANDROID_LOG_INFO, "Phi-Native", "feature %d = %d", feature, enabled);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_meiyongai_phi_nativebridge_NativeGame_getFeature(JNIEnv*, jobject, jint feature) {
    return (game::features.load(std::memory_order_relaxed) & static_cast<uint32_t>(feature)) != 0;
}
