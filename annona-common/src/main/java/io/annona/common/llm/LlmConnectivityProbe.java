package io.annona.common.llm;

/**
 * Provider 连通性探测端口（llmprovider-metering-adr）：用候选配置（baseUrl+Key+model）
 * 发一次最小请求验证可用性。端口在 common（无外部实现方需求，纯内部解耦——业务模块
 * 不得碰 HTTP/SDK），实现在 infrastructure.llm。
 *
 * <p>契约：实现<b>永不抛网络异常</b>——一切失败翻译成带安全文案的 failure 结果
 * （原始报错只进日志：连通性失败信息会原样进前端，供应商报错常带 Key 片段）。
 */
public interface LlmConnectivityProbe {

    /**
     * @param ok      是否连通且鉴权通过
     * @param message 给用户看的结论（安全文案，不含上游原文与任何 Key 材料）
     */
    record ProbeResult(boolean ok, String message) {
    }

    /**
     * 探测一次。
     *
     * @param baseUrl OpenAI 兼容根地址（可空 = 实现的内置默认）
     * @param apiKey  明文 Key——<b>调用方保证只在栈内存在</b>，实现不得写入日志/异常
     * @param model   目标模型名
     */
    ProbeResult probe(String baseUrl, String apiKey, String model);
}
