package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// apiKeyMask 是管理页展示 API Key 时的掩码，提交时保留原值。
const apiKeyMask = "****"

// AdminConfig 是管理 API 的配置。
type AdminConfig struct {
	Token string `json:"token"`
}

// AdminServer 提供配置读写与状态查询 API，供主题管理页调用。
type AdminServer struct {
	siteURL    string
	publish    PublishConfig
	configPath string
	statePath  string
	token      string
	log        *slog.Logger
}

func NewAdminServer(configPath, statePath, token, siteURL string, publish PublishConfig, log *slog.Logger) *AdminServer {
	return &AdminServer{configPath: configPath, statePath: statePath, token: token, siteURL: siteURL, publish: publish, log: log}
}

func (s *AdminServer) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/health", s.handleHealth)
	mux.HandleFunc("GET /api/config", s.auth(s.handleGetConfig))
	mux.HandleFunc("PUT /api/config", s.auth(s.handlePutConfig))
	mux.HandleFunc("GET /api/status", s.auth(s.handleGetStatus))
	mux.HandleFunc("POST /api/publish", s.auth(s.handlePublish))
	return cors(mux)
}

func (s *AdminServer) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s.token == "" {
			writeJSON(w, http.StatusForbidden, map[string]string{"error": "管理 token 未配置（config.admin.token），请在 config.json 里设置"})
			return
		}
		got := r.Header.Get("Authorization")
		if len(got) > 7 && got[:7] == "Bearer " {
			got = got[7:]
		}
		if subtle.ConstantTimeCompare([]byte(got), []byte(s.token)) != 1 {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "token 无效"})
			return
		}
		next(w, r)
	}
}

func (s *AdminServer) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *AdminServer) handleGetConfig(w http.ResponseWriter, r *http.Request) {
	raw, err := os.ReadFile(s.configPath)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "读取配置失败: " + err.Error()})
		return
	}
	var cfg Config
	if err := json.Unmarshal(raw, &cfg); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "解析配置失败: " + err.Error()})
		return
	}
	if cfg.LLM.APIKey != "" {
		cfg.LLM.APIKey = apiKeyMask
		if cfg.Admin.Token != "" {
			cfg.Admin.Token = apiKeyMask
		}
	}
	writeJSON(w, http.StatusOK, cfg)
}

func (s *AdminServer) handlePutConfig(w http.ResponseWriter, r *http.Request) {
	var incoming Config
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&incoming); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "请求体不是合法 JSON: " + err.Error()})
		return
	}
	// API Key 掩码或留空时保留原值
	cur, err := loadConfig(s.configPath)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	incoming.LLM.APIKey = cur.LLM.APIKey
	incoming.Admin.Token = cur.Admin.Token
	incoming.applyDefaults()
	if err := incoming.validate(); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	if err := writeConfigAtomic(s.configPath, &incoming); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "保存配置失败: " + err.Error()})
		return
	}
	s.log.Info("管理页更新配置", "characters", len(incoming.Characters), "mock", incoming.Mock)
	writeJSON(w, http.StatusOK, map[string]string{"ok": "配置已保存，重启 bot 生效"})
}

func (s *AdminServer) handleGetStatus(w http.ResponseWriter, r *http.Request) {
	state, err := loadState(s.statePath)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	type postSummary struct {
		URL          string                `json:"url"`
		Title        string                `json:"title"`
		SeenAt       time.Time             `json:"seenAt"`
		AICount      int                   `json:"aiCount"`
		CascadeRound int                   `json:"cascadeRound"`
		Characters   map[string]CharStatus `json:"characters"`
	}
	sum := struct {
		Posts []postSummary `json:"posts"`
	}{Posts: []postSummary{}}
	for _, p := range state.Posts {
		ps := postSummary{
			URL:          p.URL,
			Title:        p.Title,
			SeenAt:       p.SeenAt,
			AICount:      p.AICount,
			CascadeRound: p.CascadeRound,
			Characters:   map[string]CharStatus{},
		}
		for name, cs := range p.Characters {
			ps.Characters[name] = cs.Status
		}
		sum.Posts = append(sum.Posts, ps)
	}
	writeJSON(w, http.StatusOK, sum)
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

// writeConfigAtomic 原子写配置：先写临时文件再改名。
func writeConfigAtomic(path string, cfg *Config) error {
	dir := filepath.Dir(path)
	if dir == "" {
		dir = "."
	}
	tmp := filepath.Join(dir, ".config."+time.Now().Format("20060102150405")+".tmp")
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// cors 允许管理页跨域直连（生产推荐 Nginx 同域反代，此头仅作兜底）。
func cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, PUT, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// slugRe 限制发布文章的文件名格式。
var slugRe = regexp.MustCompile(`^[a-zA-Z0-9-]{1,64}$`)

// handlePublish 接收管理页的"发朋友圈"请求：写 MD 文件并执行构建部署命令。
func (s *AdminServer) handlePublish(w http.ResponseWriter, r *http.Request) {
	if s.publish.ContentDir == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "config.publish.contentDir 未配置，无法发布"})
		return
	}
	var req struct {
		Title   string `json:"title"`
		Content string `json:"content"`
		Slug    string `json:"slug"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "请求体不是合法 JSON: " + err.Error()})
		return
	}
	req.Title = strings.TrimSpace(req.Title)
	req.Content = strings.TrimSpace(req.Content)
	if req.Title == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "标题不能为空"})
		return
	}
	if req.Content == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "正文不能为空"})
		return
	}
	slug := strings.TrimSpace(req.Slug)
	if slug == "" {
		slug = time.Now().Format("20060102150405")
	}
	if !slugRe.MatchString(slug) {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "slug 只允许字母、数字、连字符"})
		return
	}
	md := fmt.Sprintf("---\ntitle: %q\ndate: %s\ndraft: false\n---\n\n%s\n", req.Title, time.Now().Format(time.RFC3339), req.Content)
	filename := filepath.Join(s.publish.ContentDir, slug+".md")
	if err := os.WriteFile(filename, []byte(md), 0o644); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "写入文章失败: " + err.Error()})
		return
	}
	s.log.Info("管理页发布新帖", "title", req.Title, "file", filename)

	if s.publish.BuildCommand != "" {
		timeout := 120 * time.Second
		if d, err := time.ParseDuration(s.publish.CommandTimeout); err == nil && d > 0 {
			timeout = d
		}
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		defer cancel()
		cmd := exec.CommandContext(ctx, "sh", "-c", s.publish.BuildCommand)
		out, err := cmd.CombinedOutput()
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "构建命令失败: " + err.Error(), "output": string(out)})
			return
		}
		s.log.Info("发布后构建完成", "output", strings.TrimSpace(string(out)))
	}

	postURL := strings.TrimRight(s.siteURL, "/") + "/posts/" + slug + "/"
	writeJSON(w, http.StatusOK, map[string]string{"ok": "发布成功，AI 评论稍后自动跟进", "url": postURL})
}
