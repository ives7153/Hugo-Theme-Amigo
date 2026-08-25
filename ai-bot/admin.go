package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
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
	mux.HandleFunc("POST /api/upload", s.auth(s.handleUpload))
	mux.HandleFunc("GET /api/site", s.auth(s.handleGetSite))
	mux.HandleFunc("PUT /api/site", s.auth(s.handlePutSite))
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

	if err := s.runBuild(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "构建命令失败: " + err.Error()})
		return
	}

	postURL := strings.TrimRight(s.siteURL, "/") + "/posts/" + slug + "/"
	writeJSON(w, http.StatusOK, map[string]string{"ok": "发布成功，AI 评论稍后自动跟进", "url": postURL})
}

// handleUpload 接收管理页上传的图片：校验类型/大小，存到 uploadDir，返回可访问 URL。
func (s *AdminServer) handleUpload(w http.ResponseWriter, r *http.Request) {
	if s.publish.UploadDir == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "config.publish.uploadDir 未配置，无法上传"})
		return
	}
	if err := r.ParseMultipartForm(32 << 20); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "解析上传失败: " + err.Error()})
		return
	}
	file, header, err := r.FormFile("image")
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "缺少 image 文件"})
		return
	}
	defer file.Close()

	maxMB := s.publish.MaxUploadMB
	if maxMB <= 0 {
		maxMB = 5
	}
	maxBytes := int64(maxMB) * 1 << 20
	if header.Size > maxBytes {
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": fmt.Sprintf("图片超过 %dMB 限制", maxMB)})
		return
	}

	ext := strings.ToLower(filepath.Ext(header.Filename))
	switch ext {
	case ".jpg", ".jpeg", ".png", ".gif", ".webp":
	default:
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "只支持 jpg/png/gif/webp 图片"})
		return
	}

	buf := make([]byte, 512)
	n, _ := io.ReadFull(file, buf)
	mime := http.DetectContentType(buf[:n])
	if !strings.HasPrefix(mime, "image/") {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "文件内容不是图片"})
		return
	}

	randBytes := make([]byte, 4)
	if _, err := rand.Read(randBytes); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "生成文件名失败"})
		return
	}
	name := time.Now().Format("20060102") + "-" + hex.EncodeToString(randBytes) + ext

	dst, err := os.Create(filepath.Join(s.publish.UploadDir, name))
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "保存图片失败: " + err.Error()})
		return
	}
	defer dst.Close()
	if _, err := dst.Write(buf[:n]); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "写入图片失败: " + err.Error()})
		return
	}
	if _, err := io.Copy(dst, file); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "写入图片失败: " + err.Error()})
		return
	}

	prefix := strings.TrimRight(s.publish.UploadURLPrefix, "/")
	if prefix == "" {
		prefix = strings.TrimRight(s.siteURL, "/") + "/images/uploads"
	}
	s.log.Info("管理页上传图片", "file", name, "size", header.Size)
	writeJSON(w, http.StatusOK, map[string]string{"ok": "上传成功", "url": prefix + "/" + name})
}

// siteInfo 是博客首页展示的个人信息（写进 Hugo data 目录，主题优先读取）。
type siteInfo struct {
	Name   string `json:"name"`
	Avatar string `json:"avatar"`
}

// siteInfoPath 返回个人信息 JSON 路径：显式配置优先，否则用 contentDir 推导。
func (s *AdminServer) siteInfoPath() string {
	if s.publish.SiteInfoFile != "" {
		return s.publish.SiteInfoFile
	}
	if s.publish.ContentDir != "" {
		blogRoot := filepath.Dir(filepath.Dir(s.publish.ContentDir))
		return filepath.Join(blogRoot, "data", "site_info.json")
	}
	return ""
}

func (s *AdminServer) handleGetSite(w http.ResponseWriter, r *http.Request) {
	path := s.siteInfoPath()
	if path == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "config.publish.contentDir 或 siteInfoFile 未配置"})
		return
	}
	info := siteInfo{}
	if raw, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal(raw, &info)
	} else if !os.IsNotExist(err) {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "读取个人信息失败: " + err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, info)
}

func (s *AdminServer) handlePutSite(w http.ResponseWriter, r *http.Request) {
	var info siteInfo
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&info); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "请求体不是合法 JSON: " + err.Error()})
		return
	}
	info.Name = strings.TrimSpace(info.Name)
	info.Avatar = strings.TrimSpace(info.Avatar)
	if info.Name == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "名字不能为空"})
		return
	}
	if len([]rune(info.Name)) > 50 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "名字太长（最多 50 字）"})
		return
	}
	if info.Avatar != "" && !strings.HasPrefix(info.Avatar, "https://") && !strings.HasPrefix(info.Avatar, "http://") {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "头像必须是 http(s) 图片地址"})
		return
	}
	path := s.siteInfoPath()
	if path == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "config.publish.contentDir 或 siteInfoFile 未配置"})
		return
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "创建目录失败: " + err.Error()})
		return
	}
	data, err := json.MarshalIndent(info, "", "  ")
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "保存失败: " + err.Error()})
		return
	}
	if err := os.Rename(tmp, path); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "保存失败: " + err.Error()})
		return
	}
	s.log.Info("管理页更新个人信息", "name", info.Name)
	if err := s.runBuild(); err != nil {
		writeJSON(w, http.StatusOK, map[string]string{"ok": "个人信息已保存，但构建失败: " + err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"ok": "个人信息已保存，首页已更新"})
}

// runBuild 执行 publish.buildCommand 重建站点。
func (s *AdminServer) runBuild() error {
	if s.publish.BuildCommand == "" {
		return nil
	}
	timeout := 120 * time.Second
	if d, err := time.ParseDuration(s.publish.CommandTimeout); err == nil && d > 0 {
		timeout = d
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, "sh", "-c", s.publish.BuildCommand)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%v: %s", err, strings.TrimSpace(string(out)))
	}
	s.log.Info("构建完成", "output", strings.TrimSpace(string(out)))
	return nil
}
