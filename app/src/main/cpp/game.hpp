#pragma once
#include <atomic>
#include <cstdint>
using HookFunction = int (*)(void*, void*, void**);
namespace game {
enum Feature : uint32_t { AutoPlay = 1, FastPass = 2, FreePurchase = 4 };
extern std::atomic<uint32_t> features;
extern std::atomic<bool> ready;
void bind(HookFunction hook);
}
