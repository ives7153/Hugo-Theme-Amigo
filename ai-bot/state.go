package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

// CharStatus 是角色在某帖下的状态。
type CharStatus string

const (
	StatusPending CharStatus = "pending" // 已排刷到时刻，还没到点
	StatusReplied CharStatus = "replied" // 已回复
	StatusLiked   CharStatus = "liked"   // 只点赞
	StatusSkipped CharStatus = "skipped" // 忽略
)

// PostState 记录单个帖子的 AI 活动状态。
type PostState struct {
	URL               string                `json:"url"`
	Text              string                `json:"text"`
	Title             string                `json:"title"`
	SeenAt            time.Time             `json:"seenAt"`
	Characters        map[string]*CharState `json:"characters"`
	CascadeRound      int                   `json:"cascadeRound"`
	LastSeenCommentID int64                 `json:"lastSeenCommentId"`
	ActiveUntil       time.Time             `json:"activeUntil"`
	AICount           int                   `json:"aiCount"`
}

type CharState struct {
	Status   CharStatus `json:"status"`
	SnoozeAt time.Time  `json:"snoozeAt"`
	At       time.Time  `json:"at"`
}

// State 是全部持久化状态。
type State struct {
	Posts map[string]*PostState `json:"posts"`
}

func newState() *State {
	return &State{Posts: map[string]*PostState{}}
}

// loadState 从文件加载状态，文件不存在则返回空状态。
func loadState(path string) (*State, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return newState(), nil
		}
		return nil, fmt.Errorf("读取状态失败: %w", err)
	}
	var s State
	if err := json.Unmarshal(raw, &s); err != nil {
		return nil, fmt.Errorf("解析状态失败: %w", err)
	}
	if s.Posts == nil {
		s.Posts = map[string]*PostState{}
	}
	for _, p := range s.Posts {
		if p.Characters == nil {
			p.Characters = map[string]*CharState{}
		}
	}
	return &s, nil
}

// saveState 原子写盘：先写临时文件再改名，防止崩溃损坏状态。
func (s *State) saveState(path string) error {
	dir := filepath.Dir(path)
	if dir == "" {
		dir = "."
	}
	tmp := filepath.Join(dir, fmt.Sprintf(".ai-state.%d.tmp", os.Getpid()))
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
