# ==============================================================================
# annona-server 多阶段构建：
#   Stage 1 (web-build)  前端 pnpm build 输出到 annona-server/src/main/resources/static/
#   Stage 2 (package)    Maven 打包（含 stage 1 生成的 static/）
#   Stage 3 (runtime)    最小 JRE 镜像运行 fat jar
#
# Build context 由仓库根传入；.dockerignore 已经把 target/ 与 node_modules/ 排除，
# stage 2 的 `COPY . .` 拿到的都是干净源码。
# ==============================================================================

# ---- Stage 1: 前端构建 -------------------------------------------------------
FROM node:24-slim AS web-build
WORKDIR /workspace

# corepack 让 package.json 的 packageManager 字段决定 pnpm 版本，避免"这里 pin 一个版本、
# 开发机上 pin 另一个"漂移。
RUN corepack enable

# 先只拷依赖清单，pnpm install 独立成一层，源码变更时命中缓存。
COPY annona-web/package.json annona-web/pnpm-lock.yaml annona-web/pnpm-workspace.yaml ./annona-web/
RUN cd annona-web && pnpm install --frozen-lockfile

# 再拷源码；vite.config.ts 的 build.outDir 指向 ../annona-server/src/main/resources/static，
# 需要先建出这个父目录，否则 vite 报"parent directory does not exist"。
COPY annona-web ./annona-web
RUN mkdir -p /workspace/annona-server/src/main/resources && \
    cd annona-web && pnpm build

# ---- Stage 2: Maven package --------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS package
WORKDIR /workspace

# stage 1 的 static 输出必须留在同一份"workspace"里，让 mvn package 打进 jar。
COPY --from=web-build /workspace /workspace
# .dockerignore 已排除 target/ 与 node_modules/，此层只拷源码与 pom。
COPY . .

# 复用宿主机或前一次 build 的 m2 缓存能加速；docker build 里没这层缓存，全量下 pom。
# 跳测试是因为 CI 已跑过 mvn verify；打镜像时不重复。
RUN mvn -B -q -DskipTests package -pl annona-server -am

# ---- Stage 3: 运行时 ---------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# healthcheck 需要 wget；alpine/debian slim 基础镜像里默认没装。
RUN apt-get update && \
    apt-get install -y --no-install-recommends wget && \
    rm -rf /var/lib/apt/lists/*

COPY --from=package /workspace/annona-server/target/annona-server.jar app.jar

EXPOSE 8080

# start-period 45s 覆盖 Boot 应用冷启 + Flyway 首次迁移的时间窗。
HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
