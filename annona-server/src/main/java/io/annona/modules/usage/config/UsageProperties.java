package io.annona.modules.usage.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 计量配置（{@code annona.usage.*}，默认值唯一出处 = application.yaml 的 env 映射）。
 * modelPrices 不给类初值：yaml 空映射即"单价未配置"，成本列显示占位文案而不是假数
 * （默认值出处规则，AGENTS §4；PropertiesDefaultSourceTest 扫得到本类）。
 */
@ConfigurationProperties(prefix = "annona.usage")
public class UsageProperties {

    /** 每用户每日 token 上限；0/负数 = 不限（自部署默认宽松，托管再收紧）。 */
    private long dailyTokenLimit;

    /** 模型单价（每 1K token，单位由部署方自定），key=模型名。类初值唯一出处（无 env 映射，
     * 部署侧 yaml 手改；PropertiesDefaultSourceTest 按单源规则判定）。 */
    private Map<String, Double> modelPrices = Map.of();

    public long getDailyTokenLimit() {
        return dailyTokenLimit;
    }

    public void setDailyTokenLimit(long dailyTokenLimit) {
        this.dailyTokenLimit = dailyTokenLimit;
    }

    public Map<String, Double> getModelPrices() {
        return modelPrices == null ? Map.of() : modelPrices;
    }

    public void setModelPrices(Map<String, Double> modelPrices) {
        this.modelPrices = modelPrices;
    }
}
