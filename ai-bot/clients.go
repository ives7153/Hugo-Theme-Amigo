package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Comment 是 Artalk 评论的简化视图。
type Comment struct {
	ID      int64  `json:"id"`
	Nick    string `json:"nick"`
	Content string `json:"content"`
	Date    string `json:"date"`
}

// CommentDraft 是发布评论需要的字段。
type CommentDraft struct {
	Nick      string `json:"nick"`
	Email     string `json:"email"`
	Content   string `json:"content"`
	PageKey   string `json:"page_key"`
	PageTitle string `json:"page_title"`
	SiteName  string `json:"site_name"`
}

// ArtalkClient 负责读写 Artalk 评论。用接口隔离，便于 mock 测试。
type ArtalkClient interface {
	GetComments(ctx context.Context, pageKey string) ([]Comment, error)
	PostComment(ctx context.Context, draft CommentDraft) (int64, error)
}

// artalkHTTP 是真实 HTTP 实现，走公共评论 API（与真人访客同路径）。
type artalkHTTP struct {
	server string
	client *http.Client
	log    *slog.Logger
}

func newArtalkHTTP(server string, timeout time.Duration, log *slog.Logger) *artalkHTTP {
	return &artalkHTTP{server: strings.TrimRight(server, "/"), client: &http.Client{Timeout: timeout}, log: log}
}

func (a *artalkHTTP) GetComments(ctx context.Context, pageKey string) ([]Comment, error) {
	u := a.server + "/api/v2/comments?page_key=" + url.QueryEscape(pageKey)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	resp, err := a.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("拉取评论失败: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("拉取评论 HTTP %d: %s", resp.StatusCode, truncate(string(body), 200))
	}
	// 兼容 {data:[...]} 与裸数组两种返回
	var wrapper struct {
		Data []Comment `json:"data"`
	}
	if err := json.Unmarshal(body, &wrapper); err == nil && wrapper.Data != nil {
		return wrapper.Data, nil
	}
	var arr []Comment
	if err := json.Unmarshal(body, &arr); err != nil {
		return nil, fmt.Errorf("解析评论响应失败: %w", err)
	}
	return arr, nil
}

func (a *artalkHTTP) PostComment(ctx context.Context, draft CommentDraft) (int64, error) {
	payload, err := json.Marshal(draft)
	if err != nil {
		return 0, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, a.server+"/api/v2/comments", bytes.NewReader(payload))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := a.client.Do(req)
	if err != nil {
		return 0, fmt.Errorf("发布评论失败: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		return 0, fmt.Errorf("发布评论 HTTP %d: %s", resp.StatusCode, truncate(string(body), 200))
	}
	var created struct {
		Data struct {
			ID int64 `json:"id"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &created); err == nil && created.Data.ID > 0 {
		return created.Data.ID, nil
	}
	var direct struct {
		ID int64 `json:"id"`
	}
	if err := json.Unmarshal(body, &direct); err == nil && direct.ID > 0 {
		return direct.ID, nil
	}
	return 0, nil
}

// mockArtalk 是测试/演练实现：评论存内存，不打真实 HTTP。
type mockArtalk struct {
	comments map[string][]Comment
	nextID   int64
	log      *slog.Logger
}

func newMockArtalk(log *slog.Logger) *mockArtalk {
	return &mockArtalk{comments: map[string][]Comment{}, nextID: 1, log: log}
}

func (m *mockArtalk) GetComments(_ context.Context, pageKey string) ([]Comment, error) {
	return m.comments[pageKey], nil
}

func (m *mockArtalk) PostComment(_ context.Context, draft CommentDraft) (int64, error) {
	id := m.nextID
	m.nextID++
	m.comments[draft.PageKey] = append(m.comments[draft.PageKey], Comment{ID: id, Nick: draft.Nick, Content: draft.Content, Date: time.Now().Format(time.RFC3339)})
	if m.log != nil {
		m.log.Info("mock 发布评论", "nick", draft.Nick, "page", draft.PageKey, "content", truncate(draft.Content, 60))
	}
	return id, nil
}

// LLMClient 负责生成回复文本。用接口隔离，便于 mock 测试。
type LLMClient interface {
	Complete(ctx context.Context, system, user string) (string, error)
}

// llmHTTP 是 OpenAI 兼容 chat/completions 实现，endpoint 可指向任意兼容服务。
type llmHTTP struct {
	cfg    LLMConfig
	client *http.Client
}

func newLLMHTTP(cfg LLMConfig) (*llmHTTP, error) {
	timeout, err := time.ParseDuration(cfg.Timeout)
	if err != nil {
		return nil, fmt.Errorf("llm.timeout 非法: %w", err)
	}
	return &llmHTTP{cfg: cfg, client: &http.Client{Timeout: timeout}}, nil
}

func (l *llmHTTP) Complete(ctx context.Context, system, user string) (string, error) {
	body := map[string]any{
		"model":       l.cfg.Model,
		"temperature": l.cfg.Temperature,
		"max_tokens":  l.cfg.MaxTokens,
		"messages": []map[string]string{
			{"role": "system", "content": system},
			{"role": "user", "content": user},
		},
	}
	payload, err := json.Marshal(body)
	if err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, l.cfg.Endpoint, bytes.NewReader(payload))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	if l.cfg.APIKey != "" {
		req.Header.Set("Authorization", "Bearer "+l.cfg.APIKey)
	}
	resp, err := l.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("LLM 请求失败: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("LLM HTTP %d: %s", resp.StatusCode, truncate(string(raw), 200))
	}
	var parsed struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return "", fmt.Errorf("解析 LLM 响应失败: %w", err)
	}
	if len(parsed.Choices) == 0 {
		return "", fmt.Errorf("LLM 响应无 choices")
	}
	return strings.TrimSpace(parsed.Choices[0].Message.Content), nil
}

// mockLLM 是测试/演练实现：按人设返回固定模板句。
type mockLLM struct{}

func (m *mockLLM) Complete(_ context.Context, system, user string) (string, error) {
	// 简单提取人设关键词，让 mock 输出略带角色差异，便于肉眼核对
	seed := "哈哈，说得对"
	if strings.Contains(system, "毒舌") {
		seed = "就这？我笑死，不过确实有点道理"
	}
	if strings.Contains(system, "温柔") {
		seed = "辛苦啦，注意休息，改天一起出来玩"
	}
	return seed, nil
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n]) + "..."
}
