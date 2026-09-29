package io.annona.modules.interview.skill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.annona.modules.interview.skill.model.SkillDefinition;
import io.annona.shared.direction.service.SkillDirectionCatalog;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 注册表的启动扫描与 fail-fast（P1b-01 验收："目录放一个 .md 即被识别；缺字段有明确报错"）。
 * 根目录可注入，测试用 src/test/resources 下的专用夹具，与主资源 skills/ 互不干扰。
 */
class SkillRegistryTest {

    private SkillRegistry registryWith(String root) {
        SkillProperties properties = new SkillProperties();
        properties.setRoot(root);
        return new SkillRegistry(properties);
    }

    @Nested
    @DisplayName("正常加载")
    class Load {

        @Test
        @DisplayName("扫描全部技能目录并按 key 排序；meta 与 parent 正确关联")
        void loadsAllAndSorts() {
            SkillRegistry registry = registryWith("classpath:skill-fixtures-ok");
            registry.load();
            List<SkillDefinition> all = registry.list();
            assertThat(all).extracting(SkillDefinition::key)
                .containsExactly("java-backend", "java-backend-ali");
            assertThat(registry.find("java-backend")).isPresent();
            assertThat(registry.find("java-backend").get().meta().displayName())
                .isEqualTo("Java 后端");
            assertThat(registry.find("java-backend-ali").get().parent()).isEqualTo("java-backend");
        }

        @Test
        @DisplayName("目录放一个新 .md 即被识别——清单由目录驱动而非代码")
        void catalogDrivenByDirectory() {
            SkillRegistry registry = registryWith("classpath:skill-fixtures-ok");
            registry.load();
            assertThat(registry.builtinDirections())
                .extracting(SkillDirectionCatalog.BuiltinDirectionSpec::key)
                .containsExactlyInAnyOrder("java-backend", "java-backend-ali");
        }

        @Test
        @DisplayName("参考解析：shared 优先公共池，本地优先技能目录，缺失返回空串，不安全路径拒绝")
        void referenceResolution() {
            SkillRegistry registry = registryWith("classpath:skill-fixtures-ok");
            registry.load();
            assertThat(registry.resolveReference("java-backend", "java.md", true))
                .contains("公共参考池样例");
            assertThat(registry.resolveReference("java-backend", "local.md", false))
                .contains("技能本地参考");
            assertThat(registry.resolveReference("java-backend", "no-such.md", true)).isEmpty();
            assertThatThrownBy(() -> registry.resolveReference("java-backend", "../evil.md", true))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> registry.resolveReference("java-backend", "/abs.md", false))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("坏目录必须拒绝启动")
    class FailFast {

        @Test
        @DisplayName("key 重复报出冲突目录")
        void duplicateKeyFails() {
            SkillRegistry registry = registryWith("classpath:skill-fixtures-dup");
            assertThatThrownBy(registry::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key 重复").hasMessageContaining("dup-key");
        }

        @Test
        @DisplayName("parent 悬空点名缺失的父技能")
        void orphanParentFails() {
            SkillRegistry registry = registryWith("classpath:skill-fixtures-orphan-parent");
            assertThatThrownBy(registry::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parent 悬空").hasMessageContaining("no-such-parent");
        }

        @Test
        @DisplayName("目录里没有任何 SKILL.md")
        void emptyDirectoryFails() {
            SkillRegistry registry = registryWith("classpath:skill-fixtures-empty");
            assertThatThrownBy(registry::load)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("没有找到任何");
        }
    }
}
