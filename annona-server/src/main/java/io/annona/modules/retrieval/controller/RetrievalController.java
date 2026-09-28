package io.annona.modules.retrieval.controller;

import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.retrieval.dto.EvalRunRequest;
import io.annona.modules.retrieval.dto.RetrievalRequest;
import io.annona.modules.retrieval.dto.RetrievalResponse;
import io.annona.modules.retrieval.service.RetrievalEvalService;
import io.annona.modules.retrieval.service.RetrievalQueryService;
import io.annona.spi.dto.Principal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索端点（P1a-07 验收要求的"检索测试接口"，同时是 P1a-08 问答复用的检索入口）。
 *
 * <p>鉴权口径：{@code /api/**} 由 SessionAuthFilter 强制登录（未登录 1004），本类只从
 * Principal 取 userId；跨用户可见性在检索 SQL 的 {@code user_id} 谓词里关掉，
 * 不依赖调用方传对参数。
 */
@RestController
@RequestMapping("/api/retrieval")
public class RetrievalController {

    private final RetrievalQueryService queryService;
    private final RetrievalEvalService evalService;

    public RetrievalController(RetrievalQueryService queryService, RetrievalEvalService evalService) {
        this.queryService = queryService;
        this.evalService = evalService;
    }

    /**
     * {@code POST /api/retrieval/query}——一次检索。
     *
     * <p>请求：{@link RetrievalRequest}{@code {query, topK?, mode?}}；{@code mode} 取
     * {@code BOTH | SEMANTIC | KEYWORD}，是给 P1a-09 评测跑对照组用的。
     * <p>响应：{@code Result<RetrievalResponse>}，命中项只含 {@code docId/chunkId/score}——
     * 正文与偏移由消费方按 chunkId 走知识库的分块预览端点回查（那条路径已有属主校验）。
     * <p>错误码：2401 空查询、1001 mode 非法、2400 检索失败；无命中<b>不是</b>错误，
     * 走 {@code diagnostics.reason}。
     *
     * @param principal 当前登录用户
     * @param request   查询体
     * @return 命中列表 + 耗时 + 空命中诊断
     */
    @PostMapping("/query")
    public Result<RetrievalResponse> query(@CurrentPrincipal Principal principal,
        @RequestBody RetrievalRequest request) {
        return Result.success(queryService.search(principal.id(), request));
    }

    /**
     * {@code POST /api/retrieval/eval-run}——把一轮评测的头部数字写进
     * {@code retrieval_eval_run}（仅 {@code scripts/rag-eval} 使用）。
     *
     * <p>请求：{@link EvalRunRequest}；响应：{@code Result<String>}（落库行 id）。
     * <p>错误码：1001 入参不合法（mode 非法、provider 缺失、比率越界、字段超长）；
     * 鉴权同检索端点（未登录 1004）。
     *
     * <p>为什么允许客户端上报而不服务端重算：指标的定义在脚本里（它才知道标注与分桶），
     * 服务端再算一遍就是两处真相；本端点的职责是“这轮真跑过、数字是多少”的留痕，
     * 因此校验从严但不做二次计算。
     */
    @PostMapping("/eval-run")
    public Result<String> evalRun(@CurrentPrincipal Principal principal,
        @RequestBody EvalRunRequest request) {
        return Result.success(evalService.record(principal.id(), request));
    }
}
