package main

import (
	"encoding/json"
	"fmt"
	"os"
	"time"
)

// Config 是机器人全部配置，JSON 格式，保持零第三方依赖。
type Config struct {
	Artalk         ArtalkConfig   `json:"artalk"`
	SiteURL        string         `json:"siteUrl"`
	PostURLPattern string         `json:"postUrlPattern"`
	LLM            LLMConfig      `json:"llm"`
	Behavior       BehaviorConfig `json:"behavior"`
	Characters     []Character    `json:"characters"`
	Mock           bool           `json:"mock"`
	Admin          AdminConfig    `json:"admin"`
	StateFile      string         `json:"stateFile"`
	LogLevel       string         `json:"logLevel"`
}

type ArtalkConfig struct {
	Server string `json:"server"`
	Site   string `json:"site"`
}

type LLMConfig struct {
	Endpoint    string  `json:"endpoint"`
	APIKey      string  `json:"apiKey"`
	Model       string  `json:"model"`
	Temperature float64 `json:"temperature"`
	MaxTokens   int     `json:"maxTokens"`
	Timeout     string  `json:"timeout"`
}

type BehaviorConfig struct {
	CheckInterval        string  `json:"checkInterval"`
	SnoozeMin            string  `json:"snoozeMin"`
	SnoozeMax            string  `json:"snoozeMax"`
	JoinHumanComment     bool    `json:"joinHumanComment"`
	JoinHumanCommentRate float64 `json:"joinHumanCommentRate"`
	AIReplyRate          float64 `json:"aiReplyRate"`
	CascadeMaxRounds     int     `json:"cascadeMaxRounds"`
	CascadeDelayMin      string  `json:"cascadeDelayMin"`
	CascadeDelayMax      string  `json:"cascadeDelayMax"`
	MaxCommentsPerPost   int     `json:"maxCommentsPerPost"`
	ActiveWindow         string  `json:"activeWindow"`
	ContextChars         int     `json:"contextChars"`
}

type Character struct {
	Name         string  `json:"name"`
	Email        string  `json:"email"`
	Avatar       string  `json:"avatar"`
	Persona      string  `json:"persona"`
	Activity     int     `json:"activity"` // 0~100
	LikeRate     float64 `json:"likeRate"`
	IsBestFriend bool    `json:"isBestFriend"`
}

// applyDefaults 给缺省字段填默认值。
func (c *Config) applyDefaults() {
	if c.PostURLPattern == "" {
		c.PostURLPattern = `\.html$`
	}
	if c.StateFile == "" {
		c.StateFile = "ai-state.json"
	}
	if c.LogLevel == "" {
		c.LogLevel = "info"
	}
	if c.LLM.Temperature == 0 {
		c.LLM.Temperature = 0.9
	}
	if c.LLM.MaxTokens == 0 {
		c.LLM.MaxTokens = 200
	}
	if c.LLM.Timeout == "" {
		c.LLM.Timeout = "60s"
	}
	b := &c.Behavior
	if b.CheckInterval == "" {
		b.CheckInterval = "5m"
	}
	if b.SnoozeMin == "" {
		b.SnoozeMin = "30m"
	}
	if b.SnoozeMax == "" {
		b.SnoozeMax = "24h"
	}
	if b.JoinHumanCommentRate == 0 {
		b.JoinHumanCommentRate = 0.3
	}
	if b.AIReplyRate == 0 {
		b.AIReplyRate = 0.4
	}
	if b.CascadeMaxRounds == 0 {
		b.CascadeMaxRounds = 2
	}
	if b.CascadeDelayMin == "" {
		b.CascadeDelayMin = "10m"
	}
	if b.CascadeDelayMax == "" {
		b.CascadeDelayMax = "60m"
	}
	if b.MaxCommentsPerPost == 0 {
		b.MaxCommentsPerPost = 8
	}
	if b.ActiveWindow == "" {
		b.ActiveWindow = "48h"
	}
	if b.ContextChars == 0 {
		b.ContextChars = 800
	}
}

// validate 校验配置，返回可读错误。
func (c *Config) validate() error {
	if c.Artalk.Server == "" {
		return fmt.Errorf("artalk.server 不能为空")
	}
	if c.SiteURL == "" {
		return fmt.Errorf("siteUrl 不能为空")
	}
	if len(c.Characters) == 0 {
		return fmt.Errorf("characters 至少需要 1 个角色")
	}
	hasBestFriend := false
	seen := map[string]bool{}
	for i, ch := range c.Characters {
		if ch.Name == "" {
			return fmt.Errorf("characters[%d].name 不能为空", i)
		}
		if ch.Email == "" {
			return fmt.Errorf("characters[%d].email 不能为空", i)
		}
		if ch.Activity < 0 || ch.Activity > 100 {
			return fmt.Errorf("characters[%d].activity 必须在 0~100 之间", i)
		}
		if ch.LikeRate < 0 || ch.LikeRate > 1 {
			return fmt.Errorf("characters[%d].likeRate 必须在 0~1 之间", i)
		}
		if seen[ch.Name] {
			return fmt.Errorf("characters 昵称重复: %s", ch.Name)
		}
		seen[ch.Name] = true
		if ch.IsBestFriend {
			hasBestFriend = true
		}
	}
	if !hasBestFriend {
		return fmt.Errorf("必须设置至少 1 个 isBestFriend 角色（保证每帖 >=1 条回复）")
	}
	if !c.Mock {
		if c.LLM.Endpoint == "" {
			return fmt.Errorf("llm.endpoint 不能为空（或开启 mock）")
		}
		if c.LLM.Model == "" {
			return fmt.Errorf("llm.model 不能为空（或开启 mock）")
		}
	}
	for _, d := range []struct{ name, val string }{
		{"behavior.checkInterval", c.Behavior.CheckInterval},
		{"behavior.snoozeMin", c.Behavior.SnoozeMin},
		{"behavior.snoozeMax", c.Behavior.SnoozeMax},
		{"behavior.cascadeDelayMin", c.Behavior.CascadeDelayMin},
		{"behavior.cascadeDelayMax", c.Behavior.CascadeDelayMax},
		{"behavior.activeWindow", c.Behavior.ActiveWindow},
		{"llm.timeout", c.LLM.Timeout},
	} {
		if _, err := time.ParseDuration(d.val); err != nil {
			return fmt.Errorf("%s 不是合法时长: %v", d.name, err)
		}
	}
	return nil
}

func loadConfig(path string) (*Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("读取配置失败: %w", err)
	}
	var cfg Config
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return nil, fmt.Errorf("解析配置失败: %w", err)
	}
	cfg.applyDefaults()
	if err := cfg.validate(); err != nil {
		return nil, err
	}
	return &cfg, nil
}
