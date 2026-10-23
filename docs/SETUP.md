# 开发环境与构建

## 必需环境

- JDK 17，用于Gradle和Kotlin工具链。
- Android SDK Platform 35；Build Tools 35.0.0。
- 仓库自带Gradle wrapper，不要求安装全局Gradle。
- 首次下载依赖需要网络。原作者的本机离线缓存不随源码分发。

Android Studio中选择本机JDK 17作为Gradle JDK。命令行开发可配置本机`JAVA_HOME`指向JDK 17；不要在仓库`gradle.properties`写死个人绝对路径。SDK路径写入被忽略的`local.properties`。

Windows的SDK路径可以写成：

```properties
sdk.dir=C\:/Users/YOUR_USER/AppData/Local/Android/Sdk
```

SDK Platform 35、JDK 17是构建要求，App的最低运行系统是Android API 26。眼镜是否可安装并开放所需接口，仍需按设备核实。

## 构建与检查

Windows：

```powershell
.\scripts\build-debug.ps1
```

该脚本检查两个模块的单元测试、lint并构建APK。已经缓存全部依赖后可用：

```powershell
.\scripts\build-debug.ps1 -Offline
```

完整Gradle命令见根目录README。APK输出到`artifacts/`。测试与lint报告位于Gradle用户目录中本项目自定义的构建路径，具体路径会出现在构建日志中。

## 模拟器与安装

用Android Studio创建API 26或更高版本的模拟器，运行`:app`。控制原型使用触控或鼠标模拟眼动，不依赖放大器。也可安装构建产物：

```powershell
adb install -r artifacts/app-debug.apk
```

包名是`com.bci.glasses.prototype`。对应用私有记录使用ADB时，必须使用此包名。`stimulus-app`是另一个独立App，不自动与主App共享刺激事件。

## 采集与分析

真实采集需要Neuracle Recorder的数据服务，主线协议为1000Hz、little-endian Float32、sample-major、每包40点，8 EEG+1 Event或32 EEG+1 Event，需核实实际设备设置及通道映射。

默认主机地址是历史研发配置，接手者须在App设备配置中改成本机实际Recorder地址，不能默认直接连接放大器。Python脚本部分含历史频率、通道和事件格式，应先读脚本参数与实现。Python依赖不是构建安卓App所必需，涉及信号分析时另行建立环境并记录版本。

根目录不包含SDK、JDK、AVD、原始数据和模型。没有这些数据时，先验证安卓构建、模拟控制和模拟数据接口，不声称完成真人或眼镜验收。
