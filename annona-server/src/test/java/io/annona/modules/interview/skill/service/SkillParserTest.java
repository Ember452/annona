package io.annona.modules.interview.skill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.annona.modules.interview.skill.model.SkillDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** SKILL.md / skill.meta.yml 解析的 fail-fast 口径（P1b-01 验收："缺字段有明确报错"）。 */
class SkillParserTest {

    private static final String VALID = """
        ---
        key: java-backend
        name: Java 后端
        description: 综合考察口径
        ---

        # 考察范围

        - 集合与并发
        """;

    @Nested
    @DisplayName("合法文件")
    class Valid {

        @Test
        @DisplayName("解析出必填字段与 persona 正文")
        void parsesRequiredFieldsAndPersona() {
            SkillDefinition definition = SkillParser.parse("classpath:skills/java-backend/SKILL.md", VALID);
            assertThat(definition.key()).isEqualTo("java-backend");
            assertThat(definition.name()).isEqualTo("Java 后端");
            assertThat(definition.description()).isEqualTo("综合考察口径");
            assertThat(definition.parent()).isNull();
            assertThat(definition.persona()).startsWith("# 考察范围");
            assertThat(definition.meta().categories()).isEmpty();
        }

        @Test
        @DisplayName("parent 可选字段透传；CRLF 不破坏切分")
        void parsesParentAndToleratesCrlf() {
            String content = "---\r\nkey: a-b\r\nname: A\r\ndescription: d\r\nparent: java-backend\r\n---\r\n\r\n# 正文\r\n";
            SkillDefinition definition = SkillParser.parse("test", content);
            assertThat(definition.parent()).isEqualTo("java-backend");
            assertThat(definition.persona()).isEqualTo("# 正文");
        }

        @Test
        @DisplayName("meta：displayName/display/categories 齐全时全量解析")
        void parsesMeta() {
            String meta = """
                displayName: Java 后端
                display:
                  icon: "☕"
                categories:
                  - key: JAVA_CORE
                    label: 语言核心
                    priority: CORE
                    ref: java.md
                    shared: true
                  - key: OTHER
                    label: 其他
                """;
            SkillDefinition.SkillMeta parsed = SkillParser.parseMeta("test/skill.meta.yml", meta);
            assertThat(parsed.displayName()).isEqualTo("Java 后端");
            assertThat(parsed.display().icon()).isEqualTo("☕");
            assertThat(parsed.categories()).hasSize(2);
            assertThat(parsed.categories().get(0).priority()).isEqualTo("CORE");
            assertThat(parsed.categories().get(0).shared()).isTrue();
            assertThat(parsed.categories().get(1).priority()).isEqualTo("NORMAL");
        }

        @Test
        @DisplayName("meta 缺席时返回 empty，不算错误")
        void metaAbsentIsEmpty() {
            assertThat(SkillParser.parseMeta("test", null).categories()).isEmpty();
        }
    }

    @Nested
    @DisplayName("坏文件必须报错且点名字段")
    class Invalid {

        @Test
        @DisplayName("缺 front-matter")
        void missingFrontMatter() {
            assertThatThrownBy(() -> SkillParser.parse("skills/x/SKILL.md", "# 只有正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("front-matter").hasMessageContaining("skills/x/SKILL.md");
        }

        @Test
        @DisplayName("缺 description 字段")
        void missingDescription() {
            assertThatThrownBy(() -> SkillParser.parse("skills/x/SKILL.md",
                "---\nkey: x\nname: X\n---\n\n正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
        }

        @Test
        @DisplayName("key 非全小写-dashed（驼峰、下划线、大写都拒）")
        void badKeyFormat() {
            assertThatThrownBy(() -> SkillParser.parse("skills/x/SKILL.md",
                "---\nkey: JavaBackend\nname: X\ndescription: d\n---\n\n正文"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JavaBackend");
        }

        @Test
        @DisplayName("正文为空")
        void emptyPersona() {
            assertThatThrownBy(() -> SkillParser.parse("skills/x/SKILL.md",
                "---\nkey: x\nname: X\ndescription: d\n---\n\n   \n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("正文为空");
        }

        @Test
        @DisplayName("category 优先级非法")
        void badPriority() {
            String meta = "categories:\n  - key: A\n    label: 某维度\n    priority: MOST\n";
            assertThatThrownBy(() -> SkillParser.parseMeta("test", meta))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MOST");
        }
    }
}
