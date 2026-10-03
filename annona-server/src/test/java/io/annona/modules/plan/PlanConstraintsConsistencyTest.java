package io.annona.modules.plan;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.plan.entity.PlanTaskEntity;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V17 CHECK 约束与实体常量的单一口径机检（AGENTS §4"约定→机检升级"元规则）：
 * 代码改取值域而 SQL 没跟上（或反之）会在真库上以约束冲突的形式延迟暴露，slice 测
 * 的 mock 探不到——这里把 {@code chk_plan_task_*} 与 {@code PlanTaskEntity} 常量直接比对。
 * 同族先例：PropertiesDefaultSourceTest。
 */
@DisplayName("plan_task 取值域：实体常量与 V17 CHECK 清单一致")
class PlanConstraintsConsistencyTest {

    private static final String V17_PATH = "/db/migration/V17__plan_module.sql";

    @Test
    @DisplayName("category/priority/status/source 四个取值域逐一对齐")
    void entityConstantsMatchSqlChecks() {
        String sql = readV17();

        assertThat(valuesOf(sql, "chk_plan_task_category"))
            .containsExactlyInAnyOrderElementsOf(PlanTaskEntity.CATEGORIES);
        assertThat(valuesOf(sql, "chk_plan_task_priority"))
            .containsExactlyInAnyOrderElementsOf(PlanTaskEntity.PRIORITIES);
        assertThat(valuesOf(sql, "chk_plan_task_status"))
            .containsExactlyInAnyOrderElementsOf(
                Set.of(PlanTaskEntity.STATUS_PENDING, PlanTaskEntity.STATUS_DONE));
        assertThat(valuesOf(sql, "chk_plan_task_source"))
            .containsExactlyInAnyOrderElementsOf(
                Set.of(PlanTaskEntity.SOURCE_AI, PlanTaskEntity.SOURCE_MANUAL));
    }

    /** 抽取 {@code CONSTRAINT <name> CHECK (col IN ('a', 'b', ...))} 的字面量集合。 */
    private static Set<String> valuesOf(String sql, String constraintName) {
        Matcher matcher = Pattern
            .compile(constraintName + " CHECK \\(\\w+ IN \\(([^)]*)\\)\\)")
            .matcher(sql);
        assertThat(matcher.find()).as("V17 缺少约束 %s（改约束名须同步本测试）", constraintName).isTrue();
        Set<String> values = new LinkedHashSet<>();
        for (String part : matcher.group(1).split(",")) {
            values.add(part.trim().replace("'", ""));
        }
        return values;
    }

    private static String readV17() {
        try (InputStream in = PlanConstraintsConsistencyTest.class.getResourceAsStream(V17_PATH)) {
            return new String(Objects.requireNonNull(in, "V17 不在 classpath：" + V17_PATH)
                .readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取 V17 失败", e);
        }
    }
}
