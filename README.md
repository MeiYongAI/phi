# phi

面向 Phigros 4.0.1.2（versionCode 160，arm64-v8a）的 libxposed (API 102) 原生功能增强模块。

## 核心特性

- **自动打歌 (AutoPlay)**：直接调整游戏原生 Judge 的时间与命中分支，支持 Click / Drag / Flick / Hold；不模拟触摸，无额外的逐帧音符遍历。
- **一键通关 (Fast Pass)**：支持普通模式和课题模式。每曲开始后直接跳过演奏，按谱面实际音符数生成 1000000 分 All Perfect 成绩；课题模式沿用原生三曲切换及最终结算流程。
- **零元购 (Free Purchase)**：拦截 MB 货币曲目构造与扣款流程，实现 0 MB 直接购买并正常写入本地存档。
- **配置持久化 (Persistent Config)**：基于本地存储持久化记忆所有功能开关、悬浮球透明度及防录屏/截屏忽略设置，游戏启动时自动恢复生效。
- **悬浮控制面板**：游戏内轻量悬浮交互界面，支持功能实时热切换、透明度调节及防录屏/截屏忽略。

## 环境要求

- **操作系统**：Android 8.0 (API 26) 及以上架构
- **设备架构**：仅支持 `arm64-v8a`
- **框架支持**：LSPosed / 支持 libxposed API 102 的 Xposed 框架环境
- **目标游戏**：Phigros 4.0.1.2（versionCode 160）

## v1.2.0

- 更新自动打歌、一键通关、零元购的全部 20 个原生地址，适配 Phigros 4.0.1.2。
- 修正 Hold 自动判定指令的字段偏移，正确清除 `missed` 状态。
- 确认一键通关支持课题模式，更新界面和模块描述中的版本标识。
- [完整变更记录](CHANGELOG.md) · [适配与验证记录](docs/version-4.0.1.2/README.md)

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
