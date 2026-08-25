package main

import (
	"fmt"
	"strings"
)

// buildSystem 生成 LLM system 消息：角色人设 + 通用规则。
func buildSystem(persona string) string {
	var sb strings.Builder
	sb.WriteString("你正在以微信朋友圈好友的身份评论朋友的一条朋友圈。")
	if persona != "" {
		sb.WriteString("\n你的设定：" + persona)
	}
	sb.WriteString(`
要求：
- 口语化，像真实朋友聊天，不要书面腔
- 简短自然，一般不超过 120 字
- 不要使用 Markdown、列表、标题、引号包裹
- 只输出评论内容本身，不要任何前后缀说明
- 如果上下文里有人先评论，你可以自然接话或回应其中某个人
- 偶尔可以带一点点小动作描述（如“笑死”“给你点个赞”）但要克制
- 不要提到自己是 AI、机器人或模型`)
	return sb.String()
}

// buildUser 生成 user 消息：帖子内容 + 当前评论串 + 指令。
func buildUser(postTitle, postText string, comments []Comment, isFollowup bool, replyToNick string) string {
	var sb strings.Builder
	sb.WriteString("【朋友圈内容】\n")
	if postTitle != "" {
		sb.WriteString("标题：" + postTitle + "\n")
	}
	sb.WriteString(truncateText(postText, 800) + "\n\n")

	sb.WriteString("【当前评论】\n")
	if len(comments) == 0 {
		sb.WriteString("（还没有人评论）\n")
	} else {
		for i, c := range comments {
			sb.WriteString(fmt.Sprintf("%d. %s：%s\n", i+1, c.Nick, truncateText(cleanContentText(c.Content), 200)))
		}
	}

	sb.WriteString("\n【任务】\n")
	if isFollowup {
		if replyToNick != "" {
			sb.WriteString(fmt.Sprintf("上面刚刚新增了一条评论（来自 %s），请以朋友身份针对这条最新评论自然接话。", replyToNick))
		} else {
			sb.WriteString("上面刚刚有新的评论，请以朋友身份自然接话，可以回应某个人。")
		}
	} else {
		sb.WriteString("请以朋友身份评论这条朋友圈。")
	}
	return sb.String()
}

// cleanContentText 去掉评论里的 HTML 残留（主题会用 [LIKE] 隐藏标记）。
func cleanContentText(s string) string {
	s = strings.ReplaceAll(s, "<span style=\"display:none\">[LIKE]</span>", "")
	s = strings.ReplaceAll(s, "[LIKE]", "")
	s = strings.ReplaceAll(s, "/like", "")
	return strings.TrimSpace(s)
}

func truncateText(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n]) + "…"
}
