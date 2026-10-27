# phi

面向 Phigros 的 libxposed (API 102) 原生功能增强模块，使用 IL2CPP 运行时元数据绑定游戏方法与字段。当前实测版本为 Phigros 4.0.1.2（versionCode 160，arm64-v8a）。

## 核心特性

- **自动打歌 (AutoPlay)**：通过方法 Hook 为游戏原生 Judge 提供命中状态与判定时间，支持 Click / Drag / Flick / Hold；不模拟触摸，无额外的逐帧音符遍历。
- **一键通关 (Fast Pass)**：支持普通模式和课题模式。歌曲资源加载完成后直接跳过演奏，按谱面实际音符数生成 1000000 分 All Perfect 成绩；课题模式沿用原生三曲切换及最终结算流程。
- **零元购 (Free Purchase)**：拦截 MB 货币曲目构造与扣款流程，实现 0 MB 直接购买并正常写入本地存档。
- **配置持久化 (Persistent Config)**：基于本地存储持久化记忆所有功能开关及截屏/录屏忽略浮窗设置，游戏启动时自动恢复生效。
- **悬浮控制面板**：游戏内轻量悬浮交互界面，支持功能实时切换及截屏/录屏忽略浮窗。

## 环境要求

- **操作系统**：Android 8.0 (API 26) 及以上架构
- **设备架构**：仅支持 `arm64-v8a`
- **框架支持**：LSPosed / 同时支持 libxposed API 102 与 LSPosed Native Hook API 的框架环境
- **当前实测游戏**：Phigros 4.0.1.2（versionCode 160）

## v2.0.0

- 移除固定游戏地址、ARM64 指令替换和 JNA，改用 LSPosed Native Hook 与 IL2CPP 运行时绑定。
- 按程序集、命名空间、类名、方法签名和字段类型解析当前游戏，字段偏移来自当前元数据。
- 普通模式在 ACTION 后加载完成直接结算；课题模式保留三首曲目的加载卡片及最终总成绩，修正判定线闪现、白屏和遮罩滞留。
- 移除零元购下方的运行状态说明，以及悬浮球透明度调节和相关配置。
- [完整变更记录](CHANGELOG.md) · [运行时绑定与验证记录](docs/runtime-binding.md) · [历史 v1.2.0 适配记录](docs/version-4.0.1.2/README.md)

### 更新兼容性

游戏更新只改变代码地址或字段偏移时，不再需要维护地址表。类型名、方法签名、游戏流程或 Unity IL2CPP ABI 改变时仍可能需要适配，不能保证所有未来版本自动兼容。绑定不匹配会记录明确错误，不使用旧地址或扫描结果兜底。

## 构建说明

构建使用 JDK 21、Android SDK、NDK 27.0.12077973 和 CMake 3.22.1。

### 1. 配置签名文件

在项目根目录下创建 `keystore/keystore.properties`（参考 `keystore/keystore.properties.example`）：

```properties
storeFile=keystore/phi.jks
storePassword=your_store_password
keyAlias=phi
keyPassword=your_key_password
```

### 2. 编译打包

```bash
# 生成已签名的 Release APK
./gradlew assembleRelease
```

构建产物输出路径为：`app/build/outputs/apk/release/app-release.apk`。

## 安装与使用

1. 在 Android 设备上安装编译完成的 `app-release.apk`。
2. 在 LSPosed 管理器中启用 **phi** 模块，并勾选作用域 **Phigros**。
3. 强行停止并重新启动 Phigros。
4. 点击游戏界面左侧的 $\varphi$ 悬浮球唤起控制面板，按需切换所需功能。

## 许可证

本项目基于 [MIT License](LICENSE) 开源。
