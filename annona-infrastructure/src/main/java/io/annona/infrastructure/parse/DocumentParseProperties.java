package io.annona.infrastructure.parse;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 解析线程池与超时（{@code annona.document-parse.*}，取值借 🅖 实测：
 * poolSize=2、timeout=2min——解析是重 CPU/IO 操作，小池避免挤占业务线程；
 * 超时防住损坏 PDF 的解析死循环）。
 */
@ConfigurationProperties(prefix = "annona.document-parse")
public class DocumentParseProperties {

    /** 解析线程池大小；解析任务每文档一个，2 个并行足够（消费侧本就是单消费者）。 */
    private int poolSize;

    /** 单文档解析超时；超时后取消解析任务并报 KB_DOC_PARSE_TIMEOUT。 */
    private Duration timeout;

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
