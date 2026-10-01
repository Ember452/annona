package io.annona.config.properties;

import io.annona.modules.planner.mastery.MasteryParams;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 训练决策层配置（{@code annona.planner.*}，P1c）。默认值唯一出处在 application.yaml
 * （PropertiesDefaultSourceTest 机检）——算法常量非"代码计算得出"，按 AGENTS §4 首选 yaml
 * 单源以便 P1c-08/A-B 校准与自部署调整，字段不写初值。取值依据见 {@link MasteryParams} 与
 * 设计文档 §6.2/§6.4。
 */
@ConfigurationProperties(prefix = "annona.planner")
public class PlannerProperties {

    // —— 掌握度模型（§6.2）——
    private double halfLifeDays;
    private double gainK;
    private double followUpWeight;
    private double lrBase;
    private double lrDecay;
    private int lrSampleCap;
    private int confidenceDivisor;
    private double neutralQualityWeight;

    // —— 规则与保护阈值（§6.4）——
    private double reviewRatio;
    private double capRatio;
    private int minSample;
    private int baselineSessions;
    private int windowDays;
    private int rejectDisableCount;
    private double weakScoreThreshold;
    private double forgettingFloor;

    /** 组装掌握度模型的参数视图（mastery 子包据此保持无 Spring 依赖）。 */
    public MasteryParams toMasteryParams() {
        return new MasteryParams(halfLifeDays, gainK, followUpWeight, lrBase, lrDecay,
            lrSampleCap, confidenceDivisor, neutralQualityWeight);
    }

    public double getHalfLifeDays() {
        return halfLifeDays;
    }

    public void setHalfLifeDays(double halfLifeDays) {
        this.halfLifeDays = halfLifeDays;
    }

    public double getGainK() {
        return gainK;
    }

    public void setGainK(double gainK) {
        this.gainK = gainK;
    }

    public double getFollowUpWeight() {
        return followUpWeight;
    }

    public void setFollowUpWeight(double followUpWeight) {
        this.followUpWeight = followUpWeight;
    }

    public double getLrBase() {
        return lrBase;
    }

    public void setLrBase(double lrBase) {
        this.lrBase = lrBase;
    }

    public double getLrDecay() {
        return lrDecay;
    }

    public void setLrDecay(double lrDecay) {
        this.lrDecay = lrDecay;
    }

    public int getLrSampleCap() {
        return lrSampleCap;
    }

    public void setLrSampleCap(int lrSampleCap) {
        this.lrSampleCap = lrSampleCap;
    }

    public int getConfidenceDivisor() {
        return confidenceDivisor;
    }

    public void setConfidenceDivisor(int confidenceDivisor) {
        this.confidenceDivisor = confidenceDivisor;
    }

    public double getNeutralQualityWeight() {
        return neutralQualityWeight;
    }

    public void setNeutralQualityWeight(double neutralQualityWeight) {
        this.neutralQualityWeight = neutralQualityWeight;
    }

    public double getReviewRatio() {
        return reviewRatio;
    }

    public void setReviewRatio(double reviewRatio) {
        this.reviewRatio = reviewRatio;
    }

    public double getCapRatio() {
        return capRatio;
    }

    public void setCapRatio(double capRatio) {
        this.capRatio = capRatio;
    }

    public int getMinSample() {
        return minSample;
    }

    public void setMinSample(int minSample) {
        this.minSample = minSample;
    }

    public int getBaselineSessions() {
        return baselineSessions;
    }

    public void setBaselineSessions(int baselineSessions) {
        this.baselineSessions = baselineSessions;
    }

    public int getWindowDays() {
        return windowDays;
    }

    public void setWindowDays(int windowDays) {
        this.windowDays = windowDays;
    }

    public int getRejectDisableCount() {
        return rejectDisableCount;
    }

    public void setRejectDisableCount(int rejectDisableCount) {
        this.rejectDisableCount = rejectDisableCount;
    }

    public double getWeakScoreThreshold() {
        return weakScoreThreshold;
    }

    public void setWeakScoreThreshold(double weakScoreThreshold) {
        this.weakScoreThreshold = weakScoreThreshold;
    }

    public double getForgettingFloor() {
        return forgettingFloor;
    }

    public void setForgettingFloor(double forgettingFloor) {
        this.forgettingFloor = forgettingFloor;
    }
}
