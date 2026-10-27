#include "il2cpp.hpp"
#include <android/log.h>
#include <dlfcn.h>
#include <stdexcept>

namespace il2cpp {
namespace {
Domain* (*domain_get)();
const Assembly** (*domain_get_assemblies)(const Domain*, size_t*);
const Image* (*assembly_get_image)(const Assembly*);
const char* (*image_get_name)(const Image*);
Class* (*class_from_name)(const Image*, const char*, const char*);
const Type* (*class_get_type)(Class*);
Object* (*type_get_object)(const Type*);
const Method* (*class_get_methods)(Class*, void**);
const char* (*method_get_name)(const Method*);
uint32_t (*method_get_param_count)(const Method*);
const Type* (*method_get_param)(const Method*, uint32_t);
const Type* (*method_get_return_type)(const Method*);
uint32_t (*method_get_flags)(const Method*, uint32_t*);
FieldInfo* (*class_get_field_from_name)(Class*, const char*);
size_t (*field_get_offset)(FieldInfo*);
const Type* (*field_get_type)(FieldInfo*);
uint32_t (*field_get_flags)(FieldInfo*);
void (*field_set_value_object)(Object*, FieldInfo*, Object*);
char* (*type_get_name)(const Type*);
void (*il2cpp_free)(void*);

template<class T> void symbol(void* handle, T& out, const char* name) {
    out = reinterpret_cast<T>(dlsym(handle, name));
    if (!out) throw std::runtime_error(std::string("Missing IL2CPP export: ") + name);
}
std::string typeName(const Type* type) {
    char* raw = type_get_name(type);
    std::string result(raw);
    il2cpp_free(raw);
    return result;
}
}

void initialize(void* handle) {
#define API(name) symbol(handle, name, "il2cpp_" #name)
    API(domain_get); API(domain_get_assemblies); API(assembly_get_image);
    API(image_get_name); API(class_from_name); API(class_get_type); API(type_get_object); API(class_get_methods);
    API(method_get_name); API(method_get_param_count); API(method_get_param);
    API(method_get_return_type); API(method_get_flags);
    API(class_get_field_from_name); API(field_get_offset); API(field_get_type);
    API(field_get_flags); API(field_set_value_object); API(type_get_name);
#undef API
    symbol(handle, il2cpp_free, "il2cpp_free");
}

Class* klass(const char* image, const char* namespaze, const char* name) {
    size_t count;
    const auto assemblies = domain_get_assemblies(domain_get(), &count);
    for (size_t i = 0; i < count; ++i) {
        const auto candidate = assembly_get_image(assemblies[i]);
        if (std::strcmp(image_get_name(candidate), image) != 0) continue;
        auto result = class_from_name(candidate, namespaze, name);
        if (result) return result;
        break;
    }
    throw std::runtime_error(std::string("Missing class: ") + image + "::" + namespaze + "." + name);
}

Object* typeObject(Class* klass) { return type_get_object(class_get_type(klass)); }

const Method* method(Class* klass, const char* name, const char* returns,
                     std::initializer_list<const char*> parameters, bool isStatic) {
    const Method* result = nullptr;
    void* iterator = nullptr;
    while (const auto current = class_get_methods(klass, &iterator)) {
        if (std::strcmp(method_get_name(current), name) != 0 ||
            method_get_param_count(current) != parameters.size()) continue;
        uint32_t implementationFlags;
        if (bool(method_get_flags(current, &implementationFlags) & 0x10) != isStatic) continue;
        if (typeName(method_get_return_type(current)) != returns) continue;
        bool matches = true;
        uint32_t i = 0;
        for (auto parameter : parameters) {
            if (typeName(method_get_param(current, i++)) != parameter) { matches = false; break; }
        }
        if (!matches) continue;
        if (result) throw std::runtime_error(std::string("Ambiguous method: ") + name);
        result = current;
    }
    if (!result) throw std::runtime_error(std::string("Missing method/signature: ") + name);
    return result;
}

void* entryPoint(const Method* method) {
    // Unity IL2CPP MethodInfo ABI: its first member is Il2CppMethodPointer.
    // This is an engine ABI dependency, not a game's RVA or a gameplay field offset.
    void* result;
    std::memcpy(&result, method, sizeof(result));
    if (!result) throw std::runtime_error("Method has no native entry point");
    return result;
}

Field field(Class* klass, const char* name, const char* type) {
    auto info = class_get_field_from_name(klass, name);
    if (!info || typeName(field_get_type(info)) != type || (field_get_flags(info) & 0x10))
        throw std::runtime_error(std::string("Missing instance field/type: ") + name);
    auto offset = field_get_offset(info);
    __android_log_print(ANDROID_LOG_INFO, "Phi-Native", "field %s : %s +%zu", name, type, offset);
    return {info, offset};
}

void Field::setObject(Object* object, Object* value) const {
    field_set_value_object(object, info, value);
}
} // namespace il2cpp
