package main

import (
	"context"
	"fmt"
	"html"
	"io"
	"net/http"
	"regexp"
	"strings"
	"time"
)

var (
	locRe    = regexp.MustCompile(`(?is)<loc>\s*(.*?)\s*</loc>`)
	scriptRe = regexp.MustCompile(`(?is)<script.*?</script>`)
	styleRe  = regexp.MustCompile(`(?is)<style.*?</style>`)
	tagRe    = regexp.MustCompile(`(?s)<[^>]+>`)
	wsRe     = regexp.MustCompile(`\s+`)
	titleRe  = regexp.MustCompile(`(?is)<title[^>]*>(.*?)</title>`)
)

type siteFetcher struct {
	client *http.Client
}

func newSiteFetcher() *siteFetcher {
	return &siteFetcher{client: &http.Client{Timeout: 30 * time.Second}}
}

// fetchSitemap 拉取 sitemap.xml，返回全部 URL。
func (s *siteFetcher) fetchSitemap(ctx context.Context, siteURL string) ([]string, error) {
	u := strings.TrimRight(siteURL, "/") + "/sitemap.xml"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	resp, err := s.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("拉取 sitemap 失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("sitemap HTTP %d", resp.StatusCode)
	}
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	var urls []string
	seen := map[string]bool{}
	for _, m := range locRe.FindAllStringSubmatch(string(raw), -1) {
		u := strings.TrimSpace(html.UnescapeString(m[1]))
		if u == "" || seen[u] {
			continue
		}
		seen[u] = true
		urls = append(urls, u)
	}
	return urls, nil
}

// fetchPostText 拉取帖子页面，提取标题与正文纯文本。
func (s *siteFetcher) fetchPostText(ctx context.Context, url string, maxChars int) (title, text string, err error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", "", err
	}
	req.Header.Set("User-Agent", "AmigoAIBot/1.0")
	resp, err := s.client.Do(req)
	if err != nil {
		return "", "", fmt.Errorf("拉取帖子失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", "", fmt.Errorf("帖子 HTTP %d", resp.StatusCode)
	}
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	h := string(raw)
	if m := titleRe.FindStringSubmatch(h); len(m) > 1 {
		title = strings.TrimSpace(html.UnescapeString(stripTags(m[1])))
	}
	t := scriptRe.ReplaceAllString(h, " ")
	t = styleRe.ReplaceAllString(t, " ")
	t = stripTags(t)
	t = html.UnescapeString(t)
	t = wsRe.ReplaceAllString(t, " ")
	t = strings.TrimSpace(t)
	if maxChars <= 0 {
		maxChars = 800
	}
	if r := []rune(t); len(r) > maxChars {
		t = string(r[:maxChars]) + "…"
	}
	return title, t, nil
}

func stripTags(s string) string {
	return tagRe.ReplaceAllString(s, " ")
}
