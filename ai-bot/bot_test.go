package main

import (
	"context"
	"math/rand"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func newTestBot(t *testing.T, cfg *Config, artalk ArtalkClient, rng *rand.Rand, now time.Time) (*Bot, *State) {
	t.Helper()
	state := newState()
	bot := NewBot(cfg, state, artalk, &mockLLM{}, rng, func() time.Time { return now }, nil)
	return bot, state
}

func addPost(state *State, url string, chars map[string]*CharState, now time.Time) *PostState {
	post := &PostState{URL: url, Title: "标题", Text: "今天去爬山了，天气不错。", SeenAt: now, Characters: chars, ActiveUntil: now.Add(48 * time.Hour)}
	state.Posts[url] = post
	return post
}

func TestBestFriendAlwaysReplies(t *testing.T) {
	cfg := testConfig()
	artalk := newMockArtalk(nil)
	now := time.Date(2026, 8, 25, 12, 0, 0, 0, time.Local)
	bot, state := newTestBot(t, cfg, artalk, rand.New(rand.NewSource(1)), now)

	// 阿伟（挚友，activity 100）和 小雨（activity 0，保证不主动回）都已到刷到时刻
	post := addPost(state, "https://blog.example.com/p1.html", map[string]*CharState{
		"阿伟": {Status: StatusPending, SnoozeAt: now.Add(-time.Minute)},
		"小雨": {Status: StatusPending, SnoozeAt: now.Add(-time.Minute)},
	}, now)
	// 小雨活跃度改成 0 便于验证挚友兜底逻辑
	cfg.Characters[1].Activity = 0

	bot.processDueActions(context.Background())

	if post.Characters["阿伟"].Status != StatusReplied {
		t.Fatalf("挚友应该回复，得到 %s", post.Characters["阿伟"].Status)
	}
	if post.AICount != 1 {
		t.Fatalf("AICount 应为 1，得到 %d", post.AICount)
	}
	cs := post.Characters["小雨"]
	if cs.Status != StatusLiked && cs.Status != StatusSkipped {
		t.Fatalf("0 活跃度角色应为点赞或跳过，得到 %s", cs.Status)
	}
}

func TestActivityDistribution(t *testing.T) {
	cfg := testConfig()
	now := time.Date(2026, 8, 25, 12, 0, 0, 0, time.Local)
	replies := map[string]int{"高活跃": 0, "低活跃": 0}
	total := 2000
	for i := 0; i < total; i++ {
		artalk := newMockArtalk(nil)
		cfg.Characters[1].Activity = 10
		bot, state := newTestBot(t, cfg, artalk, rand.New(rand.NewSource(int64(i))), now)
		post := addPost(state, "https://blog.example.com/p1.html", map[string]*CharState{
			"阿伟": {Status: StatusPending, SnoozeAt: now.Add(-time.Minute)},
			"小雨": {Status: StatusPending, SnoozeAt: now.Add(-time.Minute)},
		}, now)
		bot.processDueActions(context.Background())
		if post.Characters["阿伟"].Status == StatusReplied {
			replies["高活跃"]++
		}
		if post.Characters["小雨"].Status == StatusReplied {
			replies["低活跃"]++
		}
	}
	// 高活跃(100)应远多于低活跃(10)
	if float64(replies["高活跃"]) < float64(total)*0.95 {
		t.Fatalf("100 活跃度角色回复率应接近 100%%，得到 %d/%d", replies["高活跃"], total)
	}
	if float64(replies["低活跃"]) > float64(total)*0.25 {
		t.Fatalf("10 活跃度角色回复率应低于 25%%，得到 %d/%d", replies["低活跃"], total)
	}
}

func TestHumanCommentTriggersFollowup(t *testing.T) {
	cfg := testConfig()
	cfg.Behavior.JoinHumanComment = true
	cfg.Behavior.JoinHumanCommentRate = 1.0 // 必触发
	cfg.Behavior.CascadeMaxRounds = 2
	now := time.Date(2026, 8, 25, 12, 0, 0, 0, time.Local)
	artalk := newMockArtalk(nil)
	// 预置一条人工评论（ID 1）
	artalk.comments["https://blog.example.com/p1.html"] = []Comment{{ID: 1, Nick: "访客", Content: "羡慕了"}}
	bot, state := newTestBot(t, cfg, artalk, rand.New(rand.NewSource(2)), now)
	post := addPost(state, "https://blog.example.com/p1.html", map[string]*CharState{
		"阿伟": {Status: StatusReplied, SnoozeAt: now.Add(-time.Hour), At: now.Add(-time.Hour)},
		"小雨": {Status: StatusReplied, SnoozeAt: now.Add(-time.Hour), At: now.Add(-time.Hour)},
	}, now)
	post.LastSeenCommentID = 0
	post.AICount = 2

	bot.pollComments(context.Background())

	// 至少一个角色被排上接话
	found := false
	for _, cs := range post.Characters {
		if cs.Status == StatusPending {
			found = true
			break
		}
	}
	if !found {
		t.Fatal("人工评论应该触发某个角色接话")
	}
	if post.CascadeRound != 1 {
		t.Fatalf("CascadeRound 应为 1，得到 %d", post.CascadeRound)
	}
	if post.LastSeenCommentID != 1 {
		t.Fatalf("LastSeenCommentID 应更新为 1，得到 %d", post.LastSeenCommentID)
	}

	// 推进到接话时刻，验证真的发布了一条 AI 评论
	bot2, _ := newTestBot(t, cfg, artalk, rand.New(rand.NewSource(3)), now.Add(time.Hour))
	bot2.state = state
	bot2.processDueActions(context.Background())
	if post.AICount != 3 {
		t.Fatalf("接话后 AICount 应为 3，得到 %d", post.AICount)
	}
}

func TestStateRoundtrip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "state.json")
	state := newState()
	post := &PostState{URL: "https://x/p.html", Title: "t", Text: "body", SeenAt: time.Now(), Characters: map[string]*CharState{
		"阿伟": {Status: StatusReplied, SnoozeAt: time.Now().Add(time.Hour), At: time.Now()},
	}}
	state.Posts[post.URL] = post
	if err := state.saveState(path); err != nil {
		t.Fatal(err)
	}
	loaded, err := loadState(path)
	if err != nil {
		t.Fatal(err)
	}
	lp := loaded.Posts["https://x/p.html"]
	if lp == nil || lp.Characters["阿伟"] == nil || lp.Characters["阿伟"].Status != StatusReplied {
		t.Fatal("状态往返不一致")
	}
}

func TestStateMissingFile(t *testing.T) {
	state, err := loadState(filepath.Join(t.TempDir(), "nope.json"))
	if err != nil || len(state.Posts) != 0 {
		t.Fatalf("缺文件应返回空状态: %v", err)
	}
}

func TestPromptIncludesContext(t *testing.T) {
	sys := buildSystem("毒舌老友")
	user := buildUser("标题X", "正文内容Y", []Comment{{Nick: "访客", Content: "羡慕了"}}, false, "")
	if !strings.Contains(sys, "毒舌") {
		t.Error("system 消息应包含人设")
	}
	if !strings.Contains(user, "正文内容Y") || !strings.Contains(user, "访客：羡慕了") {
		t.Error("user 消息应包含帖子内容与评论")
	}
}

func TestCleanReply(t *testing.T) {
	got := cleanReply("- 你好\n> 引用\n真的不错！")
	if got != "你好 引用 真的不错！" {
		t.Fatalf("清理结果不符: %q", got)
	}
	got = cleanReply("\"带引号的话\"")
	if got != "带引号的话" {
		t.Fatalf("应去掉包裹引号: %q", got)
	}
}

func TestLikeCommentFormat(t *testing.T) {
	cfg := testConfig()
	now := time.Date(2026, 8, 25, 12, 0, 0, 0, time.Local)
	artalk := newMockArtalk(nil)
	// 小雨 活跃度 0 + likeRate 1.0 → 必点赞
	cfg.Characters[1].Activity = 0
	cfg.Characters[1].LikeRate = 1.0
	bot, state := newTestBot(t, cfg, artalk, rand.New(rand.NewSource(4)), now)
	post := addPost(state, "https://blog.example.com/p1.html", map[string]*CharState{
		"阿伟": {Status: StatusReplied, SnoozeAt: now.Add(-time.Minute), At: now.Add(-time.Minute)},
		"小雨": {Status: StatusPending, SnoozeAt: now.Add(-time.Minute)},
	}, now)
	bot.processDueActions(context.Background())
	cs := post.Characters["小雨"]
	if cs.Status != StatusLiked {
		t.Fatalf("应点赞，得到 %s", cs.Status)
	}
	comments := artalk.comments["https://blog.example.com/p1.html"]
	foundLike := false
	for _, c := range comments {
		if c.Nick == "小雨" && strings.Contains(c.Content, "[LIKE]") {
			foundLike = true
		}
	}
	if !foundLike {
		t.Fatal("点赞评论应包含 [LIKE] 标记")
	}
}
