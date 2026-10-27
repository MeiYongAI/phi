# Phigros 4.0.1.2 适配记录

## 样本

- 从连接手机当前安装的 `com.PigeonGames.Phigros` 提取。
- `versionName = 4.0.1.2`，`versionCode = 160`，`arm64-v8a`。
- `libil2cpp.so` SHA-256：`c5f6d6b72658ab6d5f03d3d11a63b47778c54d4b93871422218d6a75f7b4a10f`。

## 失效原因与修改

新版原生库重新布局，旧版固定 RVA 已指向其他代码。按目标函数分别重新定位，不能对全部功能套用同一偏移。

| 功能 | 4.0.1 方法 RVA | 4.0.1.2 方法 RVA |
| --- | --- | --- |
| MoneyControl.SpendMoney | `0x1CAA034` | `0x1CAC2F4` |
| ClickControl.Judge | `0x1D8214C` | `0x1D84858` |
| DragControl.Judge | `0x1D828F8` | `0x1D85004` |
| FlickControl.Judge | `0x1D82EF8` | `0x1D85604` |
| HoldControl.Judge | `0x1D83B88` | `0x1D86294` |
| ProgressControl.Update | `0x1D85B94` | `0x1D882A0` |
| ScoreControl.GetLevelResultInfo | `0x1D863C0` | `0x1D88ACC` |
| SongSelectorItem..ctor | `0x1DAE340` | `0x1DB0B2C` |

另修正原有 Hold 指令编码：`0x3901827F` 实际为 `strb wzr, [x19, #0x60]`，会写到 `scale`；正确编码为 `0x3902367F`，对应 `strb wzr, [x19, #0x8d]`，与原生 `missed` 字段一致。

## 课题模式

现有一键通关已经覆盖课题模式，无需额外修改其流程：

1. `ChallengeModeLevelModControl.OnEnable`（`0x1D2391C`）调用 `ProgressControl.LevelBreak` 和 `EnterChallengeMode`，设置原生下一场景及课题状态。
2. `ProgressControl.Update` 的课题分支读取同一 `ScoreControl.GetLevelResultInfo` 成绩快照；将结束计时设为 3.5 秒同时满足其 1.2 秒切曲条件。
3. `ChallengeModeControl.<Start>d__21.MoveNext`（`0x1D227B4`）保存上一曲成绩，按原生顺序启动下一曲，三曲结束后进入课题结算。

## 验证

- `assembleRelease lintRelease` 通过；lint 为 0 errors、20 warnings。
- Release APK 的 v2 签名验证通过，并覆盖安装为 phi 1.2.0（versionCode 3）。
- 真机冷启动完成 20 个目标点备份；自动打歌与零元购按原有保存配置成功启用。
- 真机自动打歌 `Desultory Signals / EZ Lv.10`：1000000 分，831 Perfect，831 Max Combo，100.00%，Good / Bad / Miss 均为 0。
- 用户确认自动打歌、一键通关及课题模式均正常。零元购本轮验证了目标指令和启用日志，未额外执行购买操作。

离线核验（需要 Python 与 `capstone`）：

```sh
python docs/version-4.0.1.2/verify_native.py /path/to/libil2cpp.so
```

输出见 `native-verification.txt`；原生指令摘录见 `ida-evidence.txt`。验证脚本不打包到 APK，也不作为运行时兼容或回退逻辑。
