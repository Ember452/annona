package io.annona.modules.interview.skill.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 技能注册表配置。默认值只在这里的 yaml 出口（application.yaml ${ANNONA_SKILL_ROOT:...}），
 * 字段不给初值——同一默认值两处出处是死代码（AGENTS §4 配置键规则）。
 */
@ConfigurationProperties(prefix = "annona.interview.skill")
public class SkillProperties {

    /** 技能根目录（Spring 资源语法，classpath: 或 file: 前缀均可）；子目录 = 一个技能，_shared 是公共参考池。 */
    private String root;

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }
}
