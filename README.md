# BCI Glasses

智能眼镜脑机接口安卓研究原型。当前版本为`0.10.0-prototype`，主App包名`com.bci.glasses.prototype`。

当前可运行八方向刺激、模拟眼动三候选选择、人工确认、虚拟移动与退出流程，并保留脑电采集模式。真实眼镜眼动、真实空间锚定、实时SSVEP分类和机械臂尚未接入。

## 开始开发

1. 安装Android Studio、JDK 17、Android SDK Platform 35及Build Tools 35.0.0。
2. 用Android Studio打开仓库根目录。确认Gradle使用JDK 17，并让IDE创建本机`local.properties`；也可复制`local.properties.example`后填写SDK路径。
3. 首次构建需要下载Gradle和Maven依赖，需可访问对应官方仓库。不要将其他电脑的SDK、缓存或`local.properties`加入Git。
4. 运行`:app`；首页可以进入眼动控制原型或脑电采集。控制原型不要求连接设备，闪烁默认关闭。

Windows验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :stimulus-app:testDebugUnitTest :app:lintDebug :stimulus-app:lintDebug :app:assembleDebug :stimulus-app:assembleDebug --no-daemon
```

macOS或Linux使用`./gradlew`。若下载后执行位丢失，可执行`chmod +x gradlew`。

本项目为兼容Windows中文路径，两个模块的构建输出重定向到Gradle用户目录下的`bci-glasses-build/<仓库目录名>/BciGlasses/<模块名>`。构建完成的APK也会复制到仓库根目录的`artifacts/`，该目录不纳入版本控制。

完整环境说明见[SETUP](docs/SETUP.md)，交接与任务边界见[HANDOFF](docs/HANDOFF.md)，操作见[USER_GUIDE](docs/USER_GUIDE.md)。

## 已实现

- 八方向环形刺激，四频率、两相位，可配置尺寸与亮度。
- 鼠标或触控模拟注视，稳定后形成三个候选。
- 组内注视保持、跨组切换、候选版本及过期确认检查。
- 人工注入选择和确认，单目标反馈与屏幕内虚拟移动。
- 模拟注意力下降且视线移开时正常退出。
- 眼动失效、对象失跟、显示范围失效、后台与布局变化处理。
- 配置、诊断和JSONL会话日志导出。
- 原有Recorder TCP接收、SSVEP 11Hz和左右手MI数据采集、原始记录与ZIP导出。

## 未实现

- 具体智能眼镜原生眼动SDK与个人眼动校准。
- 真实对象识别、锚定、显示与运动坐标标定。
- 实时SSVEP解码、注意力或MI确认算法。
- 便携放大器直接连接、真实机械臂及无人机控制。
- 真实光学时序、真人识别效果、功耗与佩戴验证。

2026年10月23日，本上传副本在Windows、JDK 17和现有离线依赖缓存中重新验证：71项单元测试通过，两个模块APK构建成功，Lint无错误；主App仍有14条警告，独立刺激模块无警告。未验证全新电脑首次联网下载依赖。

2026年10月15日原开发环境另有14项MuMu操作检查通过，本次整理未重跑模拟器检查。以上记录不等于真实眼镜均已验收；接手者应重新构建和运行检查。

## 设计约束

- 应用目标为直接在眼镜运行；设备能力依具体型号与官方SDK核实。
- 眼动决定候选激活，SSVEP及确认意图参与最终判决。
- 正常退出保留“注意力下降且注视移开”，故障处理另行定义。
- **不加入脑电判断质量门**：诊断可记录，不以质量分、幅值或有效通道数作为分类、确认或执行的额外放行条件，也不默认增加评分阈值放行门。
- 模拟输入、人工事件和真实采集必须明确区分。

## 目录

```text
app/                 主App、采集模式、控制原型及单元测试
stimulus-app/        独立刺激演示模块与测试
gradle/wrapper/      Gradle wrapper，包含构建所需的jar
scripts/             构建、模拟数据、导出和分析工具
docs/                开发环境、交接说明和操作说明
```

不要将原始脑电、个人会话、签名文件、密钥、专利材料或整套SDK上传到本仓库。APK可通过单独的交付渠道或GitHub Release分发。

本仓库没有自动附加开源许可证。拟公开或授权第三方再分发时，应先由项目所有者确定授权范围及素材来源。
