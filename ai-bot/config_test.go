package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func testConfig() *Config {
	cfg := &Config{
		Artalk:  ArtalkConfig{Server: "https://artalk.example.com", Site: "s"},
		SiteURL: "https://blog.example.com",
		LLM:     LLMConfig{Endpoint: "https://llm.example.com/v1/chat/completions", Model: "m"},
		Behavior: BehaviorConfig{
			CheckInterval: "5m", SnoozeMin: "30m", SnoozeMax: "1h",
			JoinHumanComment: true, JoinHumanCommentRate: 0.3, AIReplyRate: 0.4,
			CascadeMaxRounds: 2, CascadeDelayMin: "10m", CascadeDelayMax: "30m",
			MaxCommentsPerPost: 8, ActiveWindow: "48h", ContextChars: 800,
		},
		Characters: []Character{
			{Name: "阿伟", Email: "a@example.com", Persona: "毒舌", Activity: 100, IsBestFriend: true},
			{Name: "小雨", Email: "x@example.com", Persona: "温柔", Activity: 50},
		},
	}
	cfg.applyDefaults()
	return cfg
}

func TestConfigValidateBestFriendRequired(t *testing.T) {
	cfg := testConfig()
	cfg.Characters[0].IsBestFriend = false
	if err := cfg.validate(); err == nil {
		t.Fatal("缺少挚友角色应该报错")
	}
}

func TestConfigValidateActivityRange(t *testing.T) {
	cfg := testConfig()
	cfg.Characters[1].Activity = 150
	if err := cfg.validate(); err == nil {
		t.Fatal("activity 超范围应该报错")
	}
}

func TestConfigValidateLLMRequiredWhenNotMock(t *testing.T) {
	cfg := testConfig()
	cfg.LLM.Endpoint = ""
	if err := cfg.validate(); err == nil {
		t.Fatal("非 mock 模式缺 llm.endpoint 应该报错")
	}
	cfg.LLM.Endpoint = "https://x"
	cfg.Mock = true
	cfg.LLM.Model = ""
	if err := cfg.validate(); err != nil {
		t.Fatalf("mock 模式允许缺 llm 配置: %v", err)
	}
}

func TestConfigLoadAndDefaults(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	raw := `{
		"artalk": {"server": "https://a.example.com", "site": "s"},
		"siteUrl": "https://b.example.com",
		"llm": {"endpoint": "https://l", "model": "m"},
		"characters": [{"name": "x", "email": "x@e.com", "isBestFriend": true}]
	}`
	if err := os.WriteFile(path, []byte(raw), 0o644); err != nil {
		t.Fatal(err)
	}
	cfg, err := loadConfig(path)
	if err != nil {
		t.Fatalf("加载配置失败: %v", err)
	}
	if cfg.Behavior.SnoozeMin != "30m" {
		t.Errorf("默认 snoozeMin 应为 30m，得到 %s", cfg.Behavior.SnoozeMin)
	}
	if cfg.Behavior.MaxCommentsPerPost != 8 {
		t.Errorf("默认 maxCommentsPerPost 应为 8")
	}
	if cfg.LLM.Temperature != 0.9 {
		t.Errorf("默认 temperature 应为 0.9")
	}
}

func TestDurationInRange(t *testing.T) {
	bot := NewBot(testConfig(), newState(), newMockArtalk(nil), &mockLLM{}, nil, nil, nil)
	for i := 0; i < 100; i++ {
		d, err := bot.durationInRange("10m", "20m")
		if err != nil {
			t.Fatal(err)
		}
		if d < 10*time.Minute || d > 20*time.Minute {
			t.Fatalf("延迟越界: %v", d)
		}
	}
}
