# annona Makefile (P0-13)
#
# 目标读者：CI 与 Linux/macOS 用户。Windows 用户按 AGENTS.md §8.1 用原始命令
# （`./mvnw.cmd` + `pnpm`），本文件里的 shell 依赖 POSIX 工具（`test -f`、
# `curl`、`for` 循环），Windows 的 cmd/powershell 不解释。
#
# 8 个 workflow 目标 + 1 个内部占位：
#   setup        配 hooks path + 建 .env 骨架
#   up           docker compose 起完整栈，等健康
#   down         停栈 + 清卷
#   dev          打印本机跑（中间件 + spring-boot:run + pnpm dev）指令
#   test         mvn verify + pnpm typecheck + pnpm build
#   eval         scripts/rag-eval/eval.py（P1a-09 之前是 no-op）
#   logs         docker compose logs -f
#   reset        down + up（推倒重来）
#   quickstart   setup + up + 探活 + 打印入口；README 首屏一条命令

PHONY := setup up down dev test eval logs reset quickstart
.PHONY: $(PHONY)

COMPOSE      := docker compose -f docker/docker-compose.yml --env-file .env
COMPOSE_DEV  := docker compose -f docker/docker-compose.dev.yml --env-file .env

setup:
	@git config core.hooksPath .githooks 2>/dev/null || true
	@test -f .env || cp .env.example .env
	@echo ".env 已就绪。请编辑下列变量："
	@echo "  ANNONA_SECRET_KEY         (生成:  openssl rand -base64 32)"
	@echo "  SPRING_DATASOURCE_PASSWORD"
	@echo "  S3_ACCESS_KEY / S3_SECRET_KEY"
	@echo "  .env.example 内含完整清单。"

up:
	$(COMPOSE) up -d --wait
	@echo ""
	@echo "annona 已启动："
	@echo "  应用:       http://localhost        (web:80 -> server:8080)"
	@echo "  Swagger:    http://localhost/swagger-ui/index.html"
	@echo "  S3/MinIO:   默认不启动（P0 annona-server 不读 S3）。想开："
	@echo "                docker compose -f docker/docker-compose.yml --env-file .env --profile s3 up -d"
	@echo "              目前镜像不可拉（阶段总结 §5 D13），P1a-05 选定后 un-gate"
	@echo "停止: make down"

down:
	$(COMPOSE) down -v

dev:
	@echo "本机开发模式（IDE 断点 + Vite 热重载）在三个终端分别跑："
	@echo ""
	@echo "  1) 起中间件："
	@echo "     $(COMPOSE_DEV) up -d"
	@echo ""
	@echo "  2) 起后端（Windows: .\\mvnw.cmd; POSIX: ./mvnw）："
	@echo "     SPRING_PROFILES_ACTIVE=dev ./mvnw -pl annona-server -am spring-boot:run"
	@echo ""
	@echo "  3) 起前端："
	@echo "     cd annona-web && pnpm dev"
	@echo ""
	@echo "Vite 已配 /api -> :8080 代理；浏览器打开 http://localhost:5173"

test:
	./mvnw -B -q clean verify
	cd annona-web && pnpm install --frozen-lockfile && pnpm typecheck && pnpm build

eval:
	@test -f scripts/rag-eval/eval.py \
		|| (echo "scripts/rag-eval/ 落地在 P1a-09；make eval 目前为空操作" && exit 0)
	@python3 scripts/rag-eval/eval.py

logs:
	$(COMPOSE) logs -f --tail=200

reset: down up

quickstart: setup up
	@echo "等 /actuator/health 转 UP（最多 90s）..."
	@for i in $$(seq 1 90); do \
		if curl -fsS http://localhost/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then \
			echo "healthy after $${i}s"; break; \
		fi; \
		sleep 1; \
	done
	@echo ""
	@echo "=============================================================="
	@echo " annona 已就绪"
	@echo "   入口      http://localhost"
	@echo "   identity  $${ANNONA_IDENTITY_MODE:-local}  (改 .env 可切 platform / none)"
	@echo "   model     $${ANNONA_MODEL_MODE:-byok}"
	@echo "   seed      'make seed' 将在 P5-05 落地 (annona-cli DataSeeder)"
	@echo "   停止      make down"
	@echo "   日志      make logs"
	@echo "=============================================================="
