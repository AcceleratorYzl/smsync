# 短信同步系统 —— 使用说明

把手机里的短信读取出来，上传到电脑上的网页端查看（含短信内容与接收时间）。

## 一、组成

```
┌──────────────┐   HTTP POST /upload   ┌────────────────────┐
│ 安卓 App      │──────────────────────►│  本地 Java 服务器    │
│ (读短信+上传)  │                       │  SmsServer (8080)   │
└──────────────┘                       └────────────────────┘
                                                ▲
                                                │ 浏览器访问 http://电脑IP:8080
                                          ┌─────┴──────────┐
                                          │  网页端          │
                                          │  查看短信列表     │
                                          └──────────────────┘
```

| 组件 | 位置 | 说明 |
|------|------|------|
| 安卓 App | `SmsSync.apk` | 安装在手机上，申请"读取短信"权限，把短信 JSON 上传到服务器 |
| 本地服务器 | `server/SmsServer.java` | 纯 JDK 实现，接收数据存到 `server/data/sms_store.json`，并托管网页 |
| 网页前端 | `server/web/index.html` | 浏览器查看短信，支持搜索、刷新、按时间倒序 |

## 二、准备工作

1. 手机和电脑连在**同一个局域网**（同一 WiFi，或手机 USB 连电脑并开 ADB）。
2. 电脑用 JDK 17 运行服务器：

   ```cmd
   cd C:\Users\Microsoft\Documents\AgnesCode\apk\server
   D:\tools\jdk\jdk-17.0.11+9\bin\javac -encoding UTF-8 SmsServer.java
   D:\tools\jdk\jdk-17.0.11+9\bin\java -cp . SmsServer
   ```

   启动后终端会打印访问地址，例如：

   ```
   本机访问:   http://localhost:8080/
   局域网访问: http://192.168.1.x:8080/
   App 上传到: http://192.168.1.x:8080/upload
   ```

## 三、手机端操作

1. 把 `SmsSync.apk` 传到手机并安装（需允许"安装未知应用"）。
2. 打开"短信同步" App。
3. 确认"服务器地址"填的是**电脑的局域网 IP**，如 `http://192.168.1.x:8080`（就是你电脑在局域网里的 IP，可在终端打印的地址里看到）。
4. 点 **申请读取短信权限**，在弹窗里允许。
5. 点 **读取并上传短信**。

   - 看到"完成，成功上传 N 条"即成功。
   - 若失败，检查：手机与电脑是否同网段、服务器是否在运行、地址是否写错。

## 四、网页端查看

浏览器打开 `http://192.168.1.x:8080`（电脑本机用 `http://localhost:8080`）：

- 显示全部短信，按**接收时间倒序**排列。
- 每条显示：发件人/号码、类型（收件/已发等）、内容、接收时间。
- 顶部可**搜索**（匹配号码或内容关键字）、点"刷新"重新拉取。

## 五、常见问题

| 现象 | 处理 |
|------|------|
| App 上传失败 | 确认服务器已启动、手机与电脑同一局域网、地址填的是电脑 IP 而非手机 IP |
| 网页打不开 | 确认服务器在运行；本机访问用 `http://localhost:8080`；局域网访问用手机/其他设备 IP |
| 中文乱码 | 编译服务器时务必加 `-encoding UTF-8`（见上） |
| 权限被拒 | 重进 App 再点"申请读取短信权限"，或在手机设置里手动授权 |
| 端口被占用 | 改 `SmsServer.java` 里的 `PORT`（默认 8080），重新编译并同步 App 里的服务器地址 |

## 六、重新构建 APK（可选）

```cmd
cd C:\Users\Microsoft\Documents\AgnesCode\apk
set JAVA_HOME=D:\tools\jdk\jdk-17.0.11+9
set ANDROID_HOME=D:\tools\sdk
D:\tools\gradle\gradle-8.5\bin\gradle :app:assembleDebug
```

产物在 `app\build\outputs\apk\debug\app-debug.apk`（已复制到根目录 `SmsSync.apk`）。
