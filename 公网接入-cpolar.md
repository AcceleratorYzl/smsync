# 公网访问 —— 内网穿透（cpolar）接入步骤

把本地 8080 端口通过 cpolar 暴露到公网，让手机在**任意网络**（4G、别的 WiFi）都能打开网页看短信、点"立即上传"。

## 为什么用 cpolar
- 你电脑（服务器）在内网，没有公网 IP，手机在外部网络打不进来。
- cpolar 在本地装一个客户端，自动给你一个公网域名（形如 `https://xxxx.cpolar.io` 或 `http://xxxx.cpolar.io:8080`），把流量转到你电脑的 8080。
- 无需改路由器、无需有公网 IPv4，家用宽带也能用。

## 安装与启动（Windows）

1. 到 https://www.cpolar.com 注册并登录。
2. 下载 Windows 版 cpolar，安装（默认 `C:\cpolar\cpolar.exe` 或解压后路径）。
3. 启动隧道（把本地 8080 指出去）：

   ```cmd
   :: 方式一：临时隧道（每次启动给一个临时域名）
   C:\cpolar\cpolar.exe http 8080
   ```

   或

   ```cmd
   :: 方式二：自定义子域名（稳定，需在 cpolar 后台先申请一个固定子域名，如 my-sms）
   C:\cpolar\cpolar.exe authtoken <你的authtoken>
   C:\cpolar\cpolar.exe http my-sms:8080
   ```

   启动成功后终端会打印：
   ```
   General: ...
   Tunnels:
     sms  http://192.168.1.26:8080  ->  https://abcdef.cpolar.io   (临时域名)
   ```
   那个 `https://abcdef.cpolar.io`（或你自定义的子域名）就是**公网入口**。

## 使用

- 手机浏览器打开 cpolar 给的公网域名，例如 `https://abcdef.cpolar.io`。
- 网页会要求输入访问口令：默认 **`smsync2026`**。
- 手机 App 里"服务器地址"也填这个公网域名（cpolar 给的，一般是 `https`；App 的 `AndroidManifest` 已开 `usesCleartextTraffic` 允许 http，但 **https 无需额外设置**），例如 `https://abcdef.cpolar.io`。
  - 点"开启/停止后台监听" → App 常驻轮询 `https://abcdef.cpolar.io/command`。
  - 连到 **perpower_5G** WiFi 或网页点"立即上传"时，App 自动读短信并 POST 到 `https://abcdef.cpolar.io/upload`。

## 安全说明（重要）

- 公网入口 = 任何人都能访问你的网页。所以本方案默认启用了**两道防线**：
  - **网页访问口令** `smsync2026`（改服务器 `SmsServer.java` 里的 `VIEW_TOKEN` 即可；改完重编译、网页缓存要清）。
  - **上传/轮询共享密钥** `SmsSync#2026#`（改 `SHARE_KEY`；App 里 `Pref.java` 的 `K_KEY` 默认值也要同步改）。
- 建议把默认口令/密钥改成你自己的强口令，并只信任自己手机访问。
- cpolar 临时域名每次重启会变；若要稳定公网入口，用**固定子域名**方式（方式二）或在 cpolar 后台绑定你自己的域名。

## 替代方案

| 方案 | 适用 | 备注 |
|------|------|------|
| cpolar（本方案） | 内网宽带、最快 | 免费版域名会变，可买固定子域名 |
| natapp /花生壳 / frp | 同 cpolar | 原理相同，换客户端即可 |
| 云服务器 | 最稳 | 把 `SmsServer` 部署到有公网 IP 的轻量服务器，网页与接口全走云 |
| 路由器端口映射 | 有公网 IPv4 时 | 需在光猫/路由器把 8080 映射到电脑内网 IP，且你的宽带得是公网 IP |

## 快速联调 checklist

- [ ] 服务器在跑（`start_server.bat`）：`http://localhost:8080/` 能开。
- [ ] cpolar 在跑，终端打印出公网域名。
- [ ] 手机浏览器打开 cpolar 域名 → 输入口令 `smsync2026` → 看到短信列表。
- [ ] 手机 App 服务器地址填 cpolar 域名，点"开启/停止后台监听"。
- [ ] 手机连上 **perpower_5G** → 自动上传；或网页点"立即上传" → App 自动同步。
