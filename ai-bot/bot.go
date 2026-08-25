package main

import (
	"context"
	"log/slog"
	"math/rand"
	"regexp"
	"sort"
	"strings"
	"time"
)

// Bot 是机器人的核心编排器。
type Bot struct {
	cfg    *Config
	state  *State
	artalk ArtalkClient
	llm    LLMClient
	site   *siteFetcher
	rng    *rand.Rand
	now    func() time.Time
	log    *slog.Logger
}

// NewBot 构造机器人。rng/now 可注入以便测试。
func NewBot(cfg *Config, state *State, artalk ArtalkClient, llm LLMClient, rng *rand.Rand, now func() time.Time, log *slog.Logger) *Bot {
	if rng == nil {
		rng = rand.New(rand.NewSource(time.Now().UnixNano()))
	}
	if now == nil {
		now = time.Now
	}
	if log == nil {
		log = slog.Default()
	}
	return &Bot{cfg: cfg, state: state, artalk: artalk, llm: llm, site: newSiteFetcher(), rng: rng, now: now, log: log}
}

func (b *Bot) durationInRange(minS, maxS string) (time.Duration, error) {
	minD, err := time.ParseDuration(minS)
	if err != nil {
		return 0, err
	}
	maxD, err := time.ParseDuration(maxS)
	if err != nil {
		return 0, err
	}
	if maxD <= minD {
		maxD = minD + time.Minute
	}
	return minD + time.Duration(b.rng.Int63n(int64(maxD-minD)+1)), nil
}

// Tick 执行一轮：发现新帖 → 处理到期动作 → 轮询评论触发接话。
func (b *Bot) Tick(ctx context.Context) error {
	urls, err := b.site.fetchSitemap(ctx, b.cfg.SiteURL)
	if err != nil {
		return err
	}
	pattern, perr := regexp.Compile(b.cfg.PostURLPattern)
	if perr != nil {
		return perr
	}
	for _, u := range urls {
		if !pattern.MatchString(u) {
			continue
		}
		if _, ok := b.state.Posts[u]; !ok {
			b.discoverPost(ctx, u)
		}
	}
	b.processDueActions(ctx)
	b.pollComments(ctx)
	return nil
}

// discoverPost 初始化新帖：每个角色排一个随机的"刷到时刻"。
func (b *Bot) discoverPost(ctx context.Context, url string) {
	title, text, err := b.site.fetchPostText(ctx, url, b.cfg.Behavior.ContextChars)
	if err != nil {
		b.log.Warn("拉取帖子内容失败，稍后重试", "url", url, "err", err)
		title, text = "", ""
	}
	now := b.now()
	post := &PostState{
		URL:         url,
		Title:       title,
		SeenAt:      now,
		Characters:  map[string]*CharState{},
		ActiveUntil: now.Add(b.mustDuration(b.cfg.Behavior.ActiveWindow)),
	}
	for _, ch := range b.cfg.Characters {
		post.Characters[ch.Name] = &CharState{
			Status:   StatusPending,
			SnoozeAt: now.Add(b.randSnooze()),
		}
	}
	b.state.Posts[url] = post
	b.log.Info("发现新帖，已安排角色刷到时刻", "url", url, "title", title, "text", truncate(text, 60))
}

func (b *Bot) randSnooze() time.Duration {
	d, err := b.durationInRange(b.cfg.Behavior.SnoozeMin, b.cfg.Behavior.SnoozeMax)
	if err != nil {
		return time.Hour
	}
	return d
}

func (b *Bot) randCascadeDelay() time.Duration {
	d, err := b.durationInRange(b.cfg.Behavior.CascadeDelayMin, b.cfg.Behavior.CascadeDelayMax)
	if err != nil {
		return 30 * time.Minute
	}
	return d
}

func (b *Bot) mustDuration(s string) time.Duration {
	d, err := time.ParseDuration(s)
	if err != nil {
		return 48 * time.Hour
	}
	return d
}

// processDueActions 处理已到"刷到时刻"的角色。
func (b *Bot) processDueActions(ctx context.Context) {
	now := b.now()
	for _, post := range b.state.Posts {
		names := make([]string, 0, len(post.Characters))
		for name := range post.Characters {
			names = append(names, name)
		}
		sort.Strings(names)
		for _, name := range names {
			cs := post.Characters[name]
			if cs.Status != StatusPending || now.Before(cs.SnoozeAt) {
				continue
			}
			ch := b.findCharacter(name)
			if ch == nil {
				cs.Status = StatusSkipped
				continue
			}
			b.handleFirstWave(ctx, post, ch, cs)
			if err := b.saveState(); err != nil {
				b.log.Error("保存状态失败", "err", err)
			}
		}
	}
}

// handleFirstWave 处理角色的首次出现：回复 / 点赞 / 忽略。
func (b *Bot) handleFirstWave(ctx context.Context, post *PostState, ch *Character, cs *CharState) {
	// 挚友必回；其他角色按活跃度判定是否回复
	if !ch.IsBestFriend && b.rng.Intn(100) >= ch.Activity {
		// 没打算回复：按 likeRate 决定只点赞或忽略
		if b.rng.Float64() < ch.LikeRate {
			cs.Status = StatusLiked
			cs.At = b.now()
			if _, err := b.postLike(ctx, post, ch); err != nil {
				b.log.Error("点赞失败", "nick", ch.Name, "err", err)
				cs.Status = StatusSkipped
			}
		} else {
			cs.Status = StatusSkipped
			cs.At = b.now()
		}
		return
	}
	if post.AICount >= b.cfg.Behavior.MaxCommentsPerPost {
		cs.Status = StatusSkipped
		cs.At = b.now()
		return
	}
	b.doReply(ctx, post, ch, cs, false, "")
}

// doReply 生成并发布一条 AI 回复。
func (b *Bot) doReply(ctx context.Context, post *PostState, ch *Character, cs *CharState, followup bool, replyToNick string) {
	comments, err := b.artalk.GetComments(ctx, post.URL)
	if err != nil {
		b.log.Error("拉取评论失败，本次跳过", "nick", ch.Name, "url", post.URL, "err", err)
		cs.Status = StatusSkipped
		return
	}
	content, err := b.llm.Complete(ctx, buildSystem(ch.Persona), buildUser(post.Title, post.Text, comments, followup, replyToNick))
	if err != nil {
		b.log.Error("LLM 生成失败，跳过该角色", "nick", ch.Name, "err", err)
		cs.Status = StatusSkipped
		return
	}
	content = cleanReply(content)
	if content == "" {
		b.log.Warn("LLM 返回空内容，跳过", "nick", ch.Name)
		cs.Status = StatusSkipped
		return
	}
	id, err := b.artalk.PostComment(ctx, CommentDraft{
		Nick:      ch.Name,
		Email:     ch.Email,
		Content:   content,
		PageKey:   post.URL,
		PageTitle: post.Title,
		SiteName:  b.cfg.Artalk.Site,
	})
	if err != nil {
		b.log.Error("发布评论失败，跳过该角色", "nick", ch.Name, "err", err)
		cs.Status = StatusSkipped
		return
	}
	cs.Status = StatusReplied
	cs.At = b.now()
	post.AICount++
	post.ActiveUntil = b.now().Add(b.mustDuration(b.cfg.Behavior.ActiveWindow))
	b.log.Info("AI 已回复", "nick", ch.Name, "url", post.URL, "content", truncate(content, 60), "commentId", id)
}

// postLike 发布一条 [LIKE] 标记评论（与主题点赞格式一致，前台会渲染成爱心）。
func (b *Bot) postLike(ctx context.Context, post *PostState, ch *Character) (int64, error) {
	return b.artalk.PostComment(ctx, CommentDraft{
		Nick:      ch.Name,
		Email:     ch.Email,
		Content:   "觉得这个文章很赞 <span style=\"display:none\">[LIKE]</span>",
		PageKey:   post.URL,
		PageTitle: post.Title,
		SiteName:  b.cfg.Artalk.Site,
	})
}

// pollComments 轮询活跃帖子的新评论，触发 AI-AI 接话与人工评论参与。
func (b *Bot) pollComments(ctx context.Context) {
	now := b.now()
	for _, post := range b.state.Posts {
		if now.After(post.ActiveUntil) {
			continue
		}
		comments, err := b.artalk.GetComments(ctx, post.URL)
		if err != nil {
			b.log.Debug("轮询评论失败", "url", post.URL, "err", err)
			continue
		}
		var newComments []Comment
		for _, c := range comments {
			if c.ID > post.LastSeenCommentID {
				newComments = append(newComments, c)
			}
		}
		if len(newComments) == 0 {
			continue
		}
		for _, c := range newComments {
			b.handleNewComment(post, c)
		}
		sort.Slice(comments, func(i, j int) bool { return comments[i].ID > comments[j].ID })
		if len(comments) > 0 {
			post.LastSeenCommentID = comments[0].ID
		}
	}
}

// handleNewComment 决定一条新评论是否触发某个角色接话。
func (b *Bot) handleNewComment(post *PostState, c Comment) {
	isAI := b.findCharacter(c.Nick) != nil
	trigger := false
	var replyTo string
	if isAI {
		// AI 之间：按 AIReplyRate 概率接话
		if b.rng.Float64() < b.cfg.Behavior.AIReplyRate {
			trigger = true
			replyTo = c.Nick
		}
	} else if b.cfg.Behavior.JoinHumanComment && b.rng.Float64() < b.cfg.Behavior.JoinHumanCommentRate {
		// 人工评论：按配置概率加入
		trigger = true
		replyTo = c.Nick
	}
	if !trigger {
		return
	}
	if post.CascadeRound >= b.cfg.Behavior.CascadeMaxRounds {
		return
	}
	if post.AICount >= b.cfg.Behavior.MaxCommentsPerPost {
		return
	}
	pick := b.pickCharacterExcluding(c.Nick)
	if pick == nil {
		return
	}
	cs := post.Characters[pick.Name]
	if cs == nil {
		cs = &CharState{}
		post.Characters[pick.Name] = cs
	}
	// 已 pending 的不重置（首个刷到时刻优先）；其他状态可再次参与
	if cs.Status == StatusPending {
		return
	}
	cs.Status = StatusPending
	cs.SnoozeAt = b.now().Add(b.randCascadeDelay())
	cs.At = time.Time{}
	post.CascadeRound++
	post.ActiveUntil = b.now().Add(b.mustDuration(b.cfg.Behavior.ActiveWindow))
	b.log.Info("已安排接话", "replyTo", replyTo, "picker", pick.Name, "url", post.URL)
}

// pickCharacterExcluding 按活跃度加权随机挑一个角色（排除指定昵称）。
func (b *Bot) pickCharacterExcluding(exclude string) *Character {
	total := 0
	for _, ch := range b.cfg.Characters {
		if ch.Name == exclude {
			continue
		}
		if ch.Activity <= 0 {
			continue
		}
		total += ch.Activity
	}
	if total == 0 {
		return nil
	}
	n := b.rng.Intn(total)
	for _, ch := range b.cfg.Characters {
		if ch.Name == exclude || ch.Activity <= 0 {
			continue
		}
		n -= ch.Activity
		if n < 0 {
			return &ch
		}
	}
	return nil
}

func (b *Bot) findCharacter(name string) *Character {
	for _, ch := range b.cfg.Characters {
		if ch.Name == name {
			return &ch
		}
	}
	return nil
}

func (b *Bot) saveState() error {
	return b.state.saveState(b.cfg.StateFile)
}

// cleanReply 清理 LLM 输出：去掉首尾空白/引号、Markdown 残留、限制长度。
func cleanReply(s string) string {
	s = strings.TrimSpace(s)
	s = strings.Trim(s, "\"'“”‘’")
	lines := strings.Split(s, "\n")
	var out []string
	for _, ln := range lines {
		ln = strings.TrimSpace(strings.TrimPrefix(ln, "-"))
		ln = strings.TrimSpace(strings.TrimPrefix(ln, ">"))
		ln = strings.TrimSpace(ln)
		if ln != "" {
			out = append(out, ln)
		}
	}
	s = strings.Join(out, " ")
	if r := []rune(s); len(r) > 200 {
		s = string(r[:200]) + "…"
	}
	return strings.TrimSpace(s)
}
