#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <initializer_list>
#include <string>

namespace il2cpp {
struct Domain;
struct Assembly;
struct Image;
struct Class;
struct Method;
struct FieldInfo;
struct Type;
using Object = void;

void initialize(void* handle);
Class* klass(const char* image, const char* namespaze, const char* name);
Object* typeObject(Class* klass);
const Method* method(Class* klass, const char* name, const char* returns,
                     std::initializer_list<const char*> parameters, bool isStatic = false);
void* entryPoint(const Method* method);

// Offsets come exclusively from the current process's IL2CPP metadata.
struct Field {
    FieldInfo* info{};
    size_t offset{};
    template<class T> T get(Object* object) const {
        T value;
        std::memcpy(&value, static_cast<char*>(object) + offset, sizeof(T));
        return value;
    }
    template<class T> void set(Object* object, T value) const {
        std::memcpy(static_cast<char*>(object) + offset, &value, sizeof(T));
    }
    // Managed references must go through IL2CPP's write barrier.
    void setObject(Object* object, Object* value) const;
};
Field field(Class* klass, const char* name, const char* type);

template<class Signature> struct Call {
    const Method* info{};
    Signature* function{};
    void bind(Class* klass, const char* name, const char* returns,
              std::initializer_list<const char*> parameters, bool isStatic = false) {
        info = method(klass, name, returns, parameters, isStatic);
        function = reinterpret_cast<Signature*>(entryPoint(info));
    }
    template<class... Args> auto operator()(Args... args) const {
        return function(args..., info);
    }
};
} // namespace il2cpp
