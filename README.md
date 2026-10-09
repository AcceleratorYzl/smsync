# 短信同步系统 —— 使用说明

把手机里的短信读取出来，上传到电脑上的网页端查看（含短信内容与接收时间）。
支持：**连到指定 WiFi 自动上传**、**网页点按钮触发 App 立即上传**、**公网访问**。

## 一、组成

```
┌──────────────┐   HTTP POST /upload   ┌────────────────────┐
│ 安卓 App      │──────────────────────►│  本地 Java 服务器    │
│ (读短信+上传)  │   GET /command 轮询    │  SmsServer (8080)   │
└──────────────┘◄──────────────────────│  + /command 指令     │
   连到 perpower_5G 自动上传            └────────────────────┘
                                                ▲
                                                │ 浏览器访问 http://电脑IP:8080/  或  cpolar 公网域名
                                          ┌─────┴──────────┐
                                          │  网页端          │
                                          │  口令登录+查看     │
                                          │  +「立即上传」按钮  │
                                          └──────────────────┘
```

| 组件 | 位置 | 说明 |
|------|------|------|
| 安卓 App | `SmsSync.apk` | 读短信+上传；后台 Service 监听 WiFi 与指令轮询 |
| 本地服务器 | `server/SmsServer.java` | 纯 JDK，存数据 + 托管网页 + `/command` 指令 + 密钥/口令保护 |
| 网页前端 | `server/web/index.html` | 口令登录、查看、搜索、"立即上传"按钮 |
| 公网接入 | `公网接入-cpolar.md` | 用 cpolar 把 8080 暴露到公网 |

## 二、准备工作（本地）

1. 启动服务器：双击 `start_server.bat`（自动编译+启动+开浏览器），或手动：

   ```cmd
   cd C:\Users\Microsoft\Documents\AgnesCode\apk\server
   D:\tools\jdk\jdk-17.0.11+9\bin\javac -encoding UTF-8 SmsServer.java
   D:\tools\jdk\jdk-17.0.11+9\bin\java -cp . SmsServer
   ```

   启动后终端打印访问地址，例如 `http://192.168.1.26:8080/`。

2. 默认口令/密钥（改 `SmsServer.java` 里的 `VIEW_TOKEN` / `SHARE_KEY` 可自定义）：
   - **网页访问口令**：`smsync2026`
   - **上传/轮询共享密钥**：`SmsSync#2026#`（App 默认值在 `Pref.java`）

## 三、手机端操作

1. 安装 `SmsSync.apk`。
2. "服务器地址"填：
   - 同局域网：`http://<电脑IP>:8080`
   - 公网：cpolar 给的域名（见下节），如 `https://abcdef.cpolar.io`
3. 点 **申请读取短信权限**，允许"读取短信 + 通知 + 定位"。
4. 点 **开启/停止后台监听**：
   - 连到 **perpower_5G** WiFi 时自动读短信并上传；
   - 常驻轮询服务器 `/command`，网页点"立即上传"后 5 秒内自动同步。

## 四、网页端查看

1. 打开 `http://<电脑IP>:8080/` 或 cpolar 公网域名。
2. 输入访问口令 `smsync2026` 进入。
3. 短信按**接收时间倒序**排列，可搜索、刷新。
4. 点 **⚡ 立即上传**：下发指令让 App 立即读短信并上传（App 需开启后台监听）。

## 五、公网访问（cpolar）

见 `公网接入-cpolar.md`。要点：装 cpolar 客户端 → `cpolar http 8080` 得到公网域名 → 手机浏览器/App 都用该域名访问，输入口令 `smsync2026`。

## 六、常见问题

| 现象 | 处理 |
|------|------|
| App 上传失败 | 服务器在跑？地址填对（局域网 IP 或 cpolar 域名）？手机与服务器同网段或走公网？ |
| 网页打不开 | 服务器在跑？本机 `http://localhost:8080/`，局域网/公网按服务器打印的地址 |
| 网页提示"口令错误" | 输入服务器 `VIEW_TOKEN`（默认 `smsync2026`）；改过口令要同步 |
| 自动上传不触发 | App 开了"后台监听"吗？定位权限给了吗？手机确实连到 `perpower_5G`？ |
| 中文乱码 | 编译服务器务必加 `-encoding UTF-8` |
| 端口被占用 | 改 `SmsServer.java` 的 `PORT`（默认 8080），同步 App 地址与 cpolar 指向 |

## 七、重新构建 APK

```cmd
cd C:\Users\Microsoft\Documents\AgnesCode\apk
set JAVA_HOME=D:\tools\jdk\jdk-17.0.11+9
set ANDROID_HOME=D:\tools\sdk
D:\tools\gradle\gradle-8.5\bin\gradle :app:assembleDebug
```
产物 `app\build\outputs\apk\debug\app-debug.apk`（已复制到根目录 `SmsSync.apk`）。
