package io.annona.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * annona 结构守门（对应 docs/annona-项目结构.md §10 七条规则）。
 *
 * <p>违反即 CI 失败；新增例外只能通过提 ADR 修改白名单，不允许代码里注释绕过。
 *
 * <p>P0-07 阶段大部分业务模块尚未建立，规则 1/2/3/4/6 属于<b>前置约束</b>——
 * 现在能过是因为没有类可违反，一旦 P1a/P1b/P1c 有代码就直接生效。
 * 规则 5（SPI 零 Spring）与规则 7（禁 Executors.newXxx）本批立即产生实际约束。
 */
@DisplayName("ArchUnit 结构规则（AGENTS.md §4 / 项目结构 §10）")
class ArchitectureTest {

    /** 主源类：从 classpath 扫 {@code io.annona} 根包，排除测试类。 */
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("io.annona");

    @Nested
    @DisplayName("规则 1–4：分层与模块边界")
    class Layering {

        @Test
        @DisplayName("modules/* 禁止 import infrastructure/*（实现类只走运行期装配）")
        void modulesShouldNotDependOnInfrastructure() {
            ArchRule rule = noClasses().that().resideInAPackage("io.annona.modules..")
                .should().dependOnClassesThat().resideInAPackage("io.annona.infrastructure..")
                .because("annona-server 的业务代码只 @Autowired SPI 接口；具体实现由 infrastructure 通过 @ConditionalOnProperty + AutoConfiguration.imports 注入。")
                .allowEmptyShould(true);
            rule.check(PRODUCTION_CLASSES);
        }

        @Test
        @DisplayName("modules 各子包之间禁止形成循环依赖（P1c 前作为跨模块禁止的近似）")
        void modulesShouldBeFreeOfCycles() {
            ArchRule rule = slices().matching("io.annona.modules.(*)..")
                .should().beFreeOfCycles()
                .as("模块间循环依赖禁止。注：严格的『任何跨模块 import 都要白名单』在 P1c-05 引入 planner→interview 白名单时升级为 noImportedFrom。")
                .allowEmptyShould(true);
            rule.check(PRODUCTION_CLASSES);
        }

        @Test
        @DisplayName("service/repository 禁止反向依赖 controller")
        void serviceAndRepositoryShouldNotDependOnController() {
            ArchRule rule = noClasses().that()
                .resideInAnyPackage("io.annona..service..", "io.annona..repository..")
                .should().dependOnClassesThat().resideInAPackage("io.annona..controller..")
                .because("Controller 在最上层，Service / Repository 反向依赖会破坏分层。")
                .allowEmptyShould(true);
            rule.check(PRODUCTION_CLASSES);
        }

        @Test
        @DisplayName("controller 禁止直接暴露 entity")
        void controllerShouldNotDependOnEntity() {
            ArchRule rule = noClasses().that().resideInAPackage("io.annona..controller..")
                .should().dependOnClassesThat().resideInAPackage("io.annona..entity..")
                .because("Entity → DTO/Response 的映射必须走 MapStruct，不允許把 JPA Entity 直接返回给前端。")
                .allowEmptyShould(true);
            rule.check(PRODUCTION_CLASSES);
        }
    }

    @Nested
    @DisplayName("规则 5：SPI 模块零 Spring / 零 Jakarta Persistence / 零 SDK")
    class SpiPurity {

        @Test
        @DisplayName("io.annona.spi.. 不得依赖 Spring")
        void spiShouldNotDependOnSpring() {
            ArchRule rule = noClasses().that().resideInAPackage("io.annona.spi..")
                .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "software.amazon.awssdk..",
                    "com.alibaba.dashscope..",
                    "org.apache.tika..")
                .because("annona-spi 是唯一发到 Maven Central 的 artifact，必须零 Spring / 零 SDK，"
                    + "第三方在自己的仓库依赖 annona-spi 即可写实现。");
            rule.check(PRODUCTION_CLASSES);
        }
    }

    @Nested
    @DisplayName("规则 6–7：planner 方向性与全局线程池禁令")
    class Guardrails {

        @Test
        @DisplayName("planner 禁止依赖 interview / voice / schedule（只能被它们调用）")
        void plannerShouldNotDependOnDownstreamModules() {
            ArchRule rule = noClasses().that().resideInAPackage("io.annona.modules.planner..")
                .should().dependOnClassesThat().resideInAnyPackage(
                    "io.annona.modules.interview..",
                    "io.annona.modules.voice..",
                    "io.annona.modules.schedule..")
                .because("planner 是被调用方；反向依赖会形成循环并破坏『决策可解释』的单向数据流。")
                .allowEmptyShould(true);
            rule.check(PRODUCTION_CLASSES);
        }

        @Test
        @DisplayName("全局禁止 Executors.newCachedThreadPool / newFixedThreadPool / newSingleThreadExecutor / newScheduledThreadPool")
        void noExecutorsFactoryMethods() {
            ArchRule rule = noClasses().should()
                .callMethod(Executors.class, "newCachedThreadPool")
                .orShould().callMethod(Executors.class, "newCachedThreadPool",
                    java.util.concurrent.ThreadFactory.class)
                .orShould().callMethod(Executors.class, "newFixedThreadPool", int.class)
                .orShould().callMethod(Executors.class, "newFixedThreadPool",
                    int.class, java.util.concurrent.ThreadFactory.class)
                .orShould().callMethod(Executors.class, "newSingleThreadExecutor")
                .orShould().callMethod(Executors.class, "newSingleThreadExecutor",
                    java.util.concurrent.ThreadFactory.class)
                .orShould().callMethod(Executors.class, "newScheduledThreadPool", int.class)
                .orShould().callMethod(Executors.class, "newScheduledThreadPool",
                    int.class, java.util.concurrent.ThreadFactory.class)
                .because("AGENTS.md §0 与 java.util.concurrent 的默认实现使用无界队列，OOM 风险；"
                    + "需要线程池必须显式配置 ThreadPoolExecutor（P0-05 会提供四类池 Bean）。")
                .allowEmptyShould(true);
            rule.check(PRODUCTION_CLASSES);
        }
    }
}
