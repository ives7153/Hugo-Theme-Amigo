package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	configPath := flag.String("config", "config.json", "配置文件路径（JSON）")
	adminAddr := flag.String("admin", "", "管理 API 监听地址（如 127.0.0.1:8080），留空不启动")
	flag.Parse()

	log := slog.New(slog.NewTextHandler(os.Stderr, nil))

	cfg, err := loadConfig(*configPath)
	if err != nil {
		log.Error("配置加载失败", "err", err)
		os.Exit(1)
	}

	state, err := loadState(cfg.StateFile)
	if err != nil {
		log.Error("状态加载失败", "err", err)
		os.Exit(1)
	}

	var artalk ArtalkClient
	var llm LLMClient
	if cfg.Mock {
		log.Info("mock 模式：LLM 返回模板句，评论只打日志不发真实请求")
		artalk = newMockArtalk(log)
		llm = &mockLLM{}
	} else {
		timeout, err := time.ParseDuration(cfg.LLM.Timeout)
		if err != nil {
			log.Error("llm.timeout 非法", "err", err)
			os.Exit(1)
		}
		artalk = newArtalkHTTP(cfg.Artalk.Server, 30*time.Second, log)
		llm, err = newLLMHTTP(cfg.LLM)
		if err != nil {
			log.Error("LLM 客户端初始化失败", "err", err)
			os.Exit(1)
		}
		_ = timeout
	}

	bot := NewBot(cfg, state, artalk, llm, nil, nil, log)

	interval := parseIntervalOr(cfg.Behavior.CheckInterval, 5*time.Minute, log)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	log.Info("AI 评论机器人启动",
		"checkInterval", interval.String(),
		"characters", len(cfg.Characters),
		"site", cfg.SiteURL,
		"mock", cfg.Mock)

	if *adminAddr != "" {
		srv := &http.Server{
			Addr:    *adminAddr,
			Handler: NewAdminServer(*configPath, cfg.StateFile, cfg.Admin.Token, cfg.SiteURL, cfg.Publish, log).Handler(),
		}
		go func() {
			log.Info("管理 API 启动", "addr", *adminAddr)
			if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
				log.Error("管理 API 退出", "err", err)
			}
		}()
	}

	// 启动先跑一轮，之后按间隔循环
	for {
		if err := bot.Tick(ctx); err != nil {
			log.Warn("本轮执行出错，稍后重试", "err", err)
		}
		select {
		case <-ctx.Done():
			log.Info("收到退出信号，保存状态后退出")
			if err := bot.saveState(); err != nil {
				log.Error("退出前保存状态失败", "err", err)
			}
			return
		case <-time.After(interval):
		}
	}
}

func parseIntervalOr(s string, def time.Duration, log *slog.Logger) time.Duration {
	d, err := time.ParseDuration(s)
	if err != nil || d <= 0 {
		log.Warn("checkInterval 非法，使用默认", "value", s, "default", def.String())
		return def
	}
	return d
}

var _ = fmt.Sprintf
