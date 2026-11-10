# 小眼睛 · Android 悬浮球

在 Hello World 项目上做的原生 Java + Canvas 悬浮球。浅色小球上有两只黑色眼睛，点击或滑动时看向指尖；松手后停留 1200ms，再用 280ms 缓入缓出回正。眼睛用 120ms 可中断的过渡跟随目标，再次触摸会立即打断回正，最大位移为球半径的 45%（原为 25%）。每隔随机 4–8 秒轻眨一次，每次 180ms，眨眼期间仍保持跟随。系统关闭动画时直接更新位置并停用眨眼；视图不可见或移除后停止眨眼调度。

## 使用

1. 打开「小眼睛」，点击「开启悬浮球」，在系统设置允许显示在其他应用上层，然后返回。
2. 确保 Shizuku 已启动，点击「授权 Shizuku · 开启全局跟随」，允许授权。
3. 页面显示「全局跟随已连接」后，回到桌面或其他 App 操作。
4. 拖动小球可调整位置；通过本页或通知里的「关闭悬浮球」停止。

全局触摸读取在手机本地的 Shizuku UserService 中运行，不依赖 Mac 上持续运行的进程。首次启动已使用连接的电脑完成；重启手机后如果 Shizuku 停止，可以通过 Shizuku 的无线调试流程重新启动。参见 [Shizuku 官方说明](https://shizuku.rikka.app/guide/setup/)。

如果未授权或 Shizuku 未启动，小球仍能跨 App 显示，本 App 的预览可跟随；其他 App 中的连续跟随不可用，状态栏会明确提示。

## 数据和生命周期

- 仅解析直接触摸屏的坐标和触点状态，不读取页面内容，不注入或拦截手势。
- 坐标只用于即时绘制，不写入文件、不上传；App 没有网络权限。
- 锁屏和屏幕关闭时暂停触摸读取、隐藏小球；解锁后恢复。
- 关闭小球时移除窗口、通知、输入读取进程和 Shizuku UserService。
- 读取进程使用非守护模式，App 退出后不会独立常驻；服务不自动开机启动。
- 多指时优先跟随第一个有效触点，该手指松开后切换到下一个触点。

## 开发

VS Code 中按 **⌘⇧B**，或执行：

```sh
./scripts/android.sh run     # 构建、安装、打开
./scripts/android.sh build   # 仅构建
./scripts/android.sh lint    # Android 静态检查
./scripts/test-touch.sh      # 触摸解码与坐标旋转测试
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。

| 文件 | 用途 |
| --- | --- |
| `EyeBallView.java` | 球体、黑色眼睛、跟随与回正 |
| `OverlayService.java` | 悬浮窗、拖动、通知、锁屏处理、Shizuku 连接 |
| `TouchReaderService.java` | 经 Shizuku 授权后在手机端读取触摸屏 |
| `TouchDecoder.java` | Linux 多指事件解码、坐标归一化与旋转 |
| `MainActivity.java` | 预览、权限入口、状态和开关 |
| `app/src/main/res/layout/activity_main.xml` | 控制页面布局 |

Java 文件位于 `app/src/main/java/com/example/helloworld/`。为覆盖更新原来的 Hello World，包名保留 `com.example.helloworld`。

环境：JDK 17、AGP 8.13.2、Gradle 8.13、SDK 36、Shizuku API 13.1.5。本机的 Java 路径和 Gradle 下载代理在 `.env.local`；SDK 位于 `.tooling/android-sdk`，缓存位于 `.gradle-home`，这些目录不属于源码。移动 HBuilderX 或更换代理后应更新 `.env.local`。

## 验证与范围

- Pixel 7 / Android 16：已确认悬浮球显示在桌面上方，Shizuku UserService 已收到真实连续触摸事件。
- 解码测试覆盖重复原地点击、滑动、多指交接、异常槽位、队列溢出和四个旋转方向。
- 目前针对手机主触摸屏的 Linux type-B 多指协议；其他厂商设备、外接触摸屏和折叠屏尚未实测。
- 普通悬浮窗权限不能持续读取其他 App 的触摸位置，完整跟随依赖 Shizuku 授权；不使用会吞掉其他 App 手势的全屏透明触摸层。
- 构建和静态检查结果、当前版本实机证据保存在本地 `artifacts/`。

参考：[Shizuku API 官方文档](https://github.com/RikkaApps/Shizuku-API)、[Android 悬浮窗 API](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY)。
