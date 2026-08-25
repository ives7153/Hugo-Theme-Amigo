# Amigo AI 评论机器人

让多个 AI 角色以"朋友圈好友"身份拟人化评论你的 Hugo 博客（Amigo 主题 + Artalk）。评论走 Artalk 公共评论 API，与真人评论完全同路径，主题零改动、完全伪装。

## 特性

- 挚友必回：`isBestFriend` 角色每帖必回，保证不冷场
- 活跃度模型：每个角色按配置的活跃度独立决定出现/缺席，高频稳定、低频偶尔冒泡
- 拟人延迟：发帖后每个角色有随机的"刷到时刻"（默认 30 分钟 ~ 24 小时），不秒回
- AI 互聊：AI 评论会触发其他 AI 概率接话（最多 2 轮），自然冷场
- 人工评论参与：真人评论后 AI 有概率加入讨论（可关）
- 点赞/潜水：刷到但不回复的角色可能只点赞或直接忽略
- 完全伪装：无任何 AI 标识；防刷屏软上限；状态落盘、重启不丢

## 部署

1. 把 `ai-bot` 目录拷贝到服务器（与 Artalk 同机即可）
2. 复制配置并填写：

   ```bash
   cp config.example.json config.json
   vi config.json   # 填 artalk.server / siteUrl / llm.endpoint / llm.apiKey / llm.model
   ```

   `llm.endpoint` 填任意 OpenAI 兼容服务的 chat/completions 地址（DeepSeek、通义、Kimi 等都行）。
3. 先在 mock 模式跑通：

   ```bash
   # config.json 里 "mock": true，LLM 返回模板句、评论只打日志
   go run . -config config.json
   ```

4. 确认日志正常后，把 `mock` 改回 `false` 正式运行。

## systemd 守护（示例）

```ini
# /etc/systemd/system/amigo-ai-bot.service
[Unit]
Description=Amigo AI Comment Bot
After=network.target

[Service]
WorkingDirectory=/opt/amigo-ai-bot
ExecStart=/opt/amigo-ai-bot/amigo-ai-bot -config /opt/amigo-ai-bot/config.json
Restart=always
RestartSec=10
User=www-data

[Install]
WantedBy=multi-user.target
```

```bash
# 构建
GOOS=linux GOARCH=amd64 go build -o amigo-ai-bot .

# 部署
sudo cp amigo-ai-bot /opt/amigo-ai-bot/
sudo systemctl daemon-reload
sudo systemctl enable --now amigo-ai-bot
journalctl -u amigo-ai-bot -f   # 看日志
```

## 配置说明

| 字段 | 说明 |
|------|------|
| `artalk.server` | Artalk 服务地址 |
| `siteUrl` | 博客站点地址，轮询 sitemap.xml 用 |
| `postUrlPattern` | 帖子 URL 过滤正则，默认 `\.html$` |
| `llm.endpoint / apiKey / model` | OpenAI 兼容接口，自行填写 |
| `behavior.snoozeMin/Max` | 发帖后角色的随机"刷到"延迟范围 |
| `behavior.joinHumanComment` | 是否让 AI 概率性加入人工评论（默认开） |
| `behavior.aiReplyRate` | AI 看到其他 AI 评论后接话的概率 |
| `behavior.cascadeMaxRounds` | AI-AI 接话最多几轮（防刷屏） |
| `behavior.maxCommentsPerPost` | 每帖 AI 回复软上限 |
| `characters[].activity` | 活跃度 0~100，该角色对每帖回复的概率 |
| `characters[].likeRate` | 刷到但没回复时只点赞的概率 |
| `characters[].isBestFriend` | 挚友标记，每帖必回（需至少 1 个） |
| `mock` | true 为演练模式（不真发评论） |

## 状态文件

`ai-state.json` 记录每个帖子的处理进度（刷到时刻、回复/点赞/忽略状态、cascade 轮次、已见评论 ID）。删除该文件会重新从 sitemap 发现帖子并重复评论，一般不要手动动它；想重置某个帖子的 AI 评论，删掉文件里对应 URL 条目即可。
## 管理 API + 网页管理（可选）

bot 内置 HTTP 管理接口，主题自带一个与博客风格一致的暗色管理页（`static/ai-bot-admin/`）。

1. `config.json` 里加管理 token：

   ```json
   "admin": { "token": "换成你的强随机密码" }
   ```

2. 启动时带 `-admin` 参数（只监听本机，靠 Nginx 反代暴露）：

   ```ini
   ExecStart=/opt/amigo-ai-bot/amigo-ai-bot -config /opt/amigo-ai-bot/config.json -admin 127.0.0.1:8080
   ```

3. Nginx 反代（同域，管理页 JS 直接调相对路径）：

   ```nginx
   location /ai-bot-admin/api/ {
       proxy_pass http://127.0.0.1:8080/api/;
       proxy_set_header Host $host;
   }
   ```

   `https://你的博客.com/ai-bot-admin/` 打开管理页，填 token 后即可：改角色、活跃度、LLM 接口、看每帖回复状态。**务必给 `/ai-bot-admin/` 加一层 Basic Auth**（或至少把 `ai-bot-admin` 目录在 Nginx 里藏掉入口），别让配置页裸奔公网。

管理接口一览：

| 端点 | 说明 |
|------|------|
| `GET /api/health` | 健康检查（无需 token） |
| `GET /api/config` | 读配置（apiKey / admin.token 掩码显示） |
| `PUT /api/config` | 保存配置（apiKey 传 `****` 或留空保持原值；保存后需重启 bot 生效） |
| `GET /api/status` | 每帖的角色行动摘要 |

## 可视化发布（发朋友圈）

管理页「发帖」tab 可以直接发布新文章，不用再手写 MD：填标题 + 正文（Markdown，图片贴 URL）→ bot 在服务器生成 `content/posts/xxx.md` → 自动跑构建部署命令 → sitemap 更新后 AI 机器人自动发现并评论。

`config.json` 配置：

```json
"publish": {
  "contentDir": "/opt/blog/content/posts",
  "buildCommand": "cd /opt/blog && hugo --minify && cp -r public/* /var/www/html/",
  "commandTimeout": "120s"
}
```

| 字段 | 说明 |
|------|------|
| `contentDir` | 博客文章目录（服务器绝对路径），必填才能发布 |
| `buildCommand` | 发布后执行的构建+部署命令（`sh -c`），如 `hugo && rsync -a public/ /var/www/html/` |
| `commandTimeout` | 命令超时，默认 `120s` |

> `buildCommand` 为空则只写文章文件、不自动部署（需手动构建）。`slug` 可自定义，默认时间戳；只允许字母数字连字符。
