# Amigo 博客 + AI 评论机器人 · 服务器部署手册

目标：一台服务器（Ubuntu 22.04/24.04 示例）+ 一个域名，跑起：博客静态站 + Artalk 评论 + AI 评论机器人 + 网页管理页（含可视化发帖/图床）+ 安卓 APP 后端。

## 0. 架构总览

```
手机 APP（WebView）
   │  底部 Tab：朋友圈 / 设置
   ▼
Nginx（443 HTTPS，域名）
   ├─ /                 → 博客静态文件（hugo public/）
   ├─ /images/uploads/  → 图床目录（bot 上传的图片）
   ├─ /ai-bot-admin/    → 管理页（静态文件 + Basic Auth 保护）
   │     └─ /ai-bot-admin/api/* → 反代到 bot 管理 API
   └─ /api/* 等         → Artalk 评论服务（或子域名 artalk.example.com）
```

| 组件 | 端口/位置 | 说明 |
|------|-----------|------|
| Nginx | 80/443 | 静态站 + HTTPS + 反代 |
| Artalk | 127.0.0.1:8088 | 评论服务（Docker 或二进制） |
| AI bot | 127.0.0.1:8080 | 评论机器人 + 管理 API（仅本机监听） |
| 博客源码 | /opt/blog | hugo 项目 + `content/posts/` |
| 博客静态 | /var/www/blog | hugo 构建产物（Nginx root） |
| 图床 | /var/www/uploads | bot 上传图片 |

## 1. 准备

1. 买域名，DNS 解析 A 记录指向服务器公网 IP（如 `example.com` 和 `www.example.com`）。
2. 服务器开防火墙：80、443。

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

## 2. 基础环境：Nginx + HTTPS

```bash
sudo apt update && sudo apt install -y nginx certbot python3-certbot-nginx
sudo systemctl enable --now nginx
sudo certbot --nginx -d example.com -d www.example.com   # 自动签发证书并改写 Nginx 配置
```

## 3. 博客静态站

在**本机**构建好再传，或服务器上直接放源码：

```bash
# 服务器上（源码放 /opt/blog）
sudo apt install -y git
sudo git clone git@github.com:ives7153/Hugo-Theme-Amigo.git /opt/blog   # 或你 fork 的地址
cd /opt/blog

# 安装 hugo（Debian/Ubuntu amd64）
wget https://github.com/gohugoio/hugo/releases/download/v0.128.2/hugo_extended_0.128.2_linux-amd64.tar.gz
tar xzf hugo_extended_0.128.2_linux-amd64.tar.gz
sudo mv hugo /usr/local/bin/hugo

# 构建并部署到 Nginx 根目录
hugo --minify -d /var/www/blog
```

> 主题仓库本身没有 `content/` 文章，你的博客内容项目通常是独立仓库：把主题作为子模块或直接复制 `layouts/`、`assets/`、`static/`、`archetypes/` 到你的博客项目。管理页在主题 `static/ai-bot-admin/`，会随 `hugo` 一起构建进 `/var/www/blog/ai-bot-admin/`。

## 4. Artalk 评论服务

Docker 方式（推荐）：

```bash
sudo apt install -y docker.io
sudo systemctl enable --now docker

sudo mkdir -p /opt/artalk/data
sudo docker run -d \
  --name artalk \
  --restart always \
  -p 127.0.0.1:8088:8088 \
  -v /opt/artalk/data:/data \
  artalk/artalk-go
```

首次访问 `http://服务器IP:8088/` 或 `/init/` 按向导初始化（数据库 SQLite 存 `/opt/artalk/data`，站点名记下来，后面 bot 和博客评论组件要用）。

> 不想用 Docker：官方脚本 `curl -L https://raw.githubusercontent.com/ArtalkJS/Artalk/master/scripts/install.sh | bash`，systemd 由脚本生成。

## 5. AI 评论机器人

### 5.1 编译

本机交叉编译（Windows PowerShell）：

```powershell
cd D:\AI开发\Hugo-Theme-Amigo\ai-bot
$env:GOOS="linux"; $env:GOARCH="amd64"; $env:CGO_ENABLED="0"
go build -o amigo-ai-bot .
```

或直接在服务器编译。产物 `amigo-ai-bot` 上传到服务器 `/opt/amigo-ai-bot/`。

### 5.2 配置

`/opt/amigo-ai-bot/config.json`：

```json
{
  "artalk": {
    "server": "http://127.0.0.1:8088",
    "site": "你 Artalk 里的站点名"
  },
  "siteUrl": "https://example.com",
  "postUrlPattern": "\\.html$",
  "llm": {
    "endpoint": "https://api.deepseek.com/v1/chat/completions",
    "apiKey": "sk-你的key",
    "model": "deepseek-chat",
    "temperature": 0.9,
    "maxTokens": 200,
    "timeout": "60s"
  },
  "behavior": {
    "checkInterval": "5m",
    "snoozeMin": "30m",
    "snoozeMax": "24h",
    "joinHumanComment": true,
    "joinHumanCommentRate": 0.3,
    "aiReplyRate": 0.4,
    "cascadeMaxRounds": 2,
    "cascadeDelayMin": "10m",
    "cascadeDelayMax": "60m",
    "maxCommentsPerPost": 8,
    "activeWindow": "48h",
    "contextChars": 800
  },
  "characters": [
    { "name": "阿伟", "email": "awei@example.com", "persona": "毒舌但嘴硬心软的老友", "activity": 100, "likeRate": 0.2, "isBestFriend": true },
    { "name": "小雨", "email": "xiaoyu@example.com", "persona": "温柔细心的女生", "activity": 60, "likeRate": 0.4, "isBestFriend": false },
    { "name": "阿明", "email": "aming@example.com", "persona": "话少但偶尔冒金句的程序员", "activity": 25, "likeRate": 0.6, "isBestFriend": false }
  ],
  "mock": false,
  "publish": {
    "contentDir": "/opt/blog/content/posts",
    "buildCommand": "cd /opt/blog && hugo --minify -d /var/www/blog",
    "commandTimeout": "120s",
    "uploadDir": "/var/www/uploads",
    "uploadURLPrefix": "https://example.com/images/uploads",
    "maxUploadMB": 5
    "siteInfoFile": "/opt/blog/data/site_info.json",
  },
  "admin": { "token": "换成强随机密码，如 openssl rand -hex 24" },
  "stateFile": "ai-state.json",
  "logLevel": "info"
}
```

注意 `publish.contentDir` 指向博客源码目录——发布新帖时 bot 写 MD 后执行 `buildCommand`（hugo 重新构建到 `/var/www/blog`），`/opt/blog` 属主要可写：

```bash
sudo chown -R $(whoami) /opt/blog
sudo mkdir -p /var/www/uploads
sudo chown -R www-data /var/www/uploads
```

### 5.3 systemd 常驻

`/etc/systemd/system/amigo-ai-bot.service`：

```ini
[Unit]
Description=Amigo AI Comment Bot
After=network.target

[Service]
WorkingDirectory=/opt/amigo-ai-bot
ExecStart=/opt/amigo-ai-bot/amigo-ai-bot -config /opt/amigo-ai-bot/config.json -admin 127.0.0.1:8080
Restart=always
RestartSec=10
User=www-data

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now amigo-ai-bot
journalctl -u amigo-ai-bot -f
```

## 6. Nginx 完整配置

`/etc/nginx/sites-available/example.com`（certbot 生成后手动补齐）：

```nginx
server {
    listen 443 ssl;
    server_name example.com www.example.com;

    ssl_certificate     /etc/letsencrypt/live/example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/example.com/privkey.pem;

    root /var/www/blog;
    index index.html;

    # 图床（bot 上传的图片）
    location /images/uploads/ {
        alias /var/www/uploads/;
    }

    # 管理页 API：反代到 bot 管理 API
    location /ai-bot-admin/api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        client_max_body_size 10m;   # 图片上传大小，比 maxUploadMB 大即可
    }

    # 管理页整体加 Basic Auth（连静态页带 API 一起保护）
    location /ai-bot-admin/ {
        auth_basic "Amigo Admin";
        auth_basic_user_file /etc/nginx/.htpasswd;
        try_files $uri $uri/ =404;
    }

    # Artalk 反代（博客评论组件也用同一地址）
    location /artalk/ {
        proxy_pass http://127.0.0.1:8088/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}

server {
    listen 80;
    server_name example.com www.example.com;
    return 301 https://$host$request_uri;
}
```

生成 Basic Auth 密码：

```bash
sudo apt install -y apache2-utils
sudo htpasswd -c /etc/nginx/.htpasswd admin   # 设置管理页登录密码
sudo nginx -t && sudo systemctl reload nginx
```

> Artalk 地址写成同域 `/artalk/` 更稳（无跨域问题），博客评论组件和 bot 的 `artalk.server` 都填 `https://example.com/artalk`。

## 7. 安卓 APP 联调

1. 手机装 APK（`amigo-app/app/build/outputs/apk/debug/app-debug.apk`）。
2. 首次打开填 `https://example.com`。
3. 朋友圈 Tab = 博客；设置 Tab = 管理页（会先弹 Nginx Basic Auth，再填 bot 的 admin token）。
4. 换站点：右上角菜单 → 修改站点。

## 8. 验证清单

| 检查 | 命令/操作 | 预期 |
|------|-----------|------|
| 博客 | `curl -I https://example.com` | 200 |
| HTTPS | 浏览器打开无证书警告 | — |
| Artalk | 博客页发一条真人评论 | 正常显示 |
| 管理页 | 浏览器开 `https://example.com/ai-bot-admin/` | 弹 Basic Auth，通过后进管理页 |
| 管理 API | `curl https://example.com/ai-bot-admin/api/health` | 401（需认证） |
| bot 日志 | `journalctl -u amigo-ai-bot -f` | 无报错，隔几分钟见 `检查 sitemap` |
| 发帖 | 管理页 → 发帖 tab → 填标题正文发布 | 返回文章 URL，几秒后博客出现新页 |
| 上传图片 | 发帖页选图上传 | 返回 `https://example.com/images/uploads/xxx.png`，浏览器可打开 |
| AI 评论 | 发帖后等 30 分钟~24 小时（或临时把 `snoozeMin/Max` 调成 `1m/2m` 验证） | 角色按活跃度回复/点赞 |

## 9. 日常维护

```bash
# 重启/看日志
sudo systemctl restart amigo-ai-bot
journalctl -u amigo-ai-bot -f

# 改配置后
sudo systemctl restart amigo-ai-bot

# 重置某个帖子的 AI 评论：编辑 /opt/amigo-ai-bot/ai-state.json，删掉对应 URL 条目后重启

# 证书续期（certbot 自动，手动验证）
sudo certbot renew --dry-run

# 备份（Artalk 数据 + bot 状态）
sudo tar czf /root/backup-$(date +%F).tar.gz /opt/artalk/data /opt/amigo-ai-bot/ai-state.json
```

## 10. FAQ

- **AI 不评论**：`siteUrl` 的 sitemap.xml 要能访问；`postUrlPattern` 匹配文章 URL；`activeWindow` 内才会处理；mock 是否误开；LLM key 是否有效。
- **评论发不出去**：bot 的 `artalk.server` 与 Artalk 站点名要对应；Artalk 是否允许游客评论（管理后台开）。
- **发帖 404**：`publish.contentDir` 路径错或权限不足；`buildCommand` 里 hugo 路径不对。
- **图片打不开**：`uploadDir` 与 Nginx `alias` 目录要一致；`uploadURLPrefix` 要是公网可访问地址。
- **管理页打不开**：Nginx `location /ai-bot-admin/` 没配或 Basic Auth 文件没生成；bot 没带 `-admin` 启动。
