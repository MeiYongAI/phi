# IL2CPP 运行时绑定

## 绑定契约

- LSPosed native_init 注册库加载回调，在 libil2cpp.so 初始化完成后绑定方法。
- 游戏方法使用程序集、命名空间、类名、返回类型、参数类型及静态标志精确匹配。
- 实例字段按字段名和类型匹配，偏移由 il2cpp_field_get_offset 返回；托管对象引用使用 il2cpp_field_set_value_object 写入。
- 不保留游戏 RVA 表、指令修改、特征扫描或旧版本回退路径。完整契约解析成功后才安装并启用功能。
- 依赖当前 Unity IL2CPP 的 MethodInfo 首成员为原生函数入口的 ABI，以及 AArch64 方法调用约定。引擎 ABI、游戏接口或行为变化仍可能需要维护。

## 功能入口

自动打歌 Hook ClickControl.Judge、DragControl.Judge、FlickControl.Judge 与 HoldControl.Judge，保留原生音效、视觉反馈、音符清理和成绩事件。

一键通关在 ProgressControl.Update 中等谱面和音频资源就绪，结束播放状态并触发原生结束流程。ScoreControl.GetLevelResultInfo 保留原始结果结构，按实际音符数生成 1000000 分、100% 的 All Perfect 结果。

LevelInformation.Update 仅在加载完成、将要显示演奏场景时保留传入截图及颜色。普通模式继续使用原生结算转场；课题模式在 Phigros2.ChallengeMode.Scripts.ChallengeModeControl.Awake 创建下一曲卡片时释放本模块保留的截图，避免遮住后续卡片和总成绩。

零元购 Hook MoneyControl.SpendMoney 和 SongSelectorItem..ctor，仅将原本具有价格的项目设为零 MB。功能关闭时调用原方法。

## 验证记录

2026-10-27，Phigros 4.0.1.2 / versionCode 160，arm64-v8a，用户当前手机：

- 全部 10 个原生方法绑定成功。
- 普通模式一键通关和课题三曲流程经用户实测确认正常。
- 课题录屏显示 Originally、Glaciaxion、What do you want more than a Happy ending? 依次加载，随后显示总成绩 3000000；三曲 Perfect 数分别为 136、66、4。
- 对应完整日志无 NullReferenceException 或原生崩溃；该课题录屏未检测到近全屏纯白帧。
- 用户进一步确认全部功能正常。这里不把用户确认扩大为所有谱面、隐藏剧情或未来版本的全覆盖测试。
