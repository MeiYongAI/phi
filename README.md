# phi

面向 Phigros 4.0.1 (arm64-v8a) 的 libxposed (API 102) 原生功能增强模块。

## 核心特性

- **自动打歌 (AutoPlay)**：基于 ARM64 判定管线微创补丁与时间守卫，实现全音符（Click / Drag / Flick / Hold）零误差理论值判定，阻断触控标志干扰。
- **零元购 (Free Purchase)**：拦截 MB 货币曲目构造与扣款流程，实现 0 MB 直接购买并正常写入本地存档。
- **悬浮控制面板**：游戏内轻量悬浮交互界面，支持功能实时热切换、透明度调节及防录屏/截屏忽略。

## 环境要求

- **操作系统**：Android 8.0 (API 26) 及以上架构
- **设备架构**：仅支持 `arm64-v8a`
- **框架支持**：LSPosed / 支持 libxposed API 102 的 Xposed 框架环境
- **目标游戏**：Phigros 4.0.1 及兼容版本

## 构建说明

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
