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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * annona 结构守门（对应 docs/annona-项目结构.md §10）。
 *
 * <p>违反即 CI 失败；新增例外只能通过提 ADR 后修改本文件的规则（白名单以规则形式
 * 住在代码里，本仓没有 {@code archunit-whitelist.properties}——那个文件只是旧文档里的
 * 设想，从未实现；把它当作已有机制会让人以为改个配置就能放行）。
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
                .because("annona-server 的业务代码只 @Autowired 端口接口；具体实现由 infrastructure 的"
                    + " @Component + @ConditionalOnProperty 经启动类的组件扫描在运行期注入（本仓无"
                    + " AutoConfiguration.imports 文件）。")
                .allowEmptyShould(true);
            rule.check(PRODUCTION_CLASSES);
        }

        @Test
        @DisplayName("modules 各子包之间禁止形成循环依赖（只拦成环；跨模块边界的正面约束见规则 8）")
        void modulesShouldBeFreeOfCycles() {
            ArchRule rule = slices().matching("io.annona.modules.(*)..")
                .should().beFreeOfCycles()
                .as("模块间循环依赖禁止。注：本规则只拦『成环』，不拦『新增单向跨模块依赖』——"
                    + "任何模块 import planner 都不会成环，所以真正的边界由规则 8 锁死。")
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

        @Test
        @DisplayName("modules/* 禁止直接依赖 shared.direction.repository（读方向只经 DirectionQueryService）")
        void modulesShouldNotDependOnDirectionRepository() {
            ArchRule rule = noClasses().that().resideInAPackage("io.annona.modules..")
                .should().dependOnClassesThat().resideInAPackage("io.annona.shared.direction.repository..")
                .because("direction ADR 修订 2 遗留义务：方向可见性口径（ACTIVE + 内置或本人）只在 "
                    + "DirectionQueryService 维护，业务模块直接碰 repository 会绕过 owner 命名空间约定。")
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

    @Nested
    @DisplayName("规则 8：跨模块白名单（唯一同步边 interview/orchestrator → planner/advisor）")
    class CrossModuleWhitelist {

        /**
         * 为什么需要这条：AGENTS §4 把 {@code orchestrator → planner/advisor} 定为需要
         * “白名单 + ADR”的例外②，但规则 4 只拦成环：任何模块 import planner 都不会成环，
         * 于是“白名单”事实上无人守（09-30 审查发现；注释里承诺的 noImportedFrom 升级也从未发生）。
         */
        @Test
        @DisplayName("除 interview/orchestrator 外，任何模块不得依赖 planner")
        void onlyOrchestratorMayDependOnPlanner() {
            // that() 必须同时排除 planner 自身：planner 也在 io.annona.modules.. 里，
            // 不排除就会把它的 164 条**包内**依赖当违规（首次实跑即抓到此写法错误）
            ArchRule rule = noClasses().that().resideInAPackage("io.annona.modules..")
                .and().resideOutsideOfPackages(
                    "io.annona.modules.interview.orchestrator..",
                    "io.annona.modules.planner..")
                .should().dependOnClassesThat().resideInAPackage("io.annona.modules.planner..")
                .because("全仓唯一同步跨模块调用边是 interview/orchestrator → planner/advisor"
                    + "（例外②，需 planner-decision-kernel-adr）；其余跨模块读走 shared 只读端口，"
                    + "写走领域事件。要新增边先走 ADR，不要改本规则。")
                .allowEmptyShould(true);
            rule.check(PRODUCTION_CLASSES);
        }

        @Test
        @DisplayName("orchestrator 只能依赖 planner 的 advisor 入口（不得伸进规则链/guard/留痕内部）")
        void orchestratorMayOnlyDependOnPlannerAdvisor() {
            ArchRule rule = noClasses().that()
                .resideInAPackage("io.annona.modules.interview.orchestrator..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.annona.modules.planner.rule..",
                    "io.annona.modules.planner.guard..",
                    "io.annona.modules.planner.trace..",
                    "io.annona.modules.planner.reputation..",
                    "io.annona.modules.planner.service..",
                    "io.annona.modules.planner.mastery..")
                .because("例外②只放行 advisor 这一个入口；伸进规则链或留痕内部会把决策实现细节"
                    + "顶到调用方，面板与组卷会因同一个改动各改一半。")
                .allowEmptyShould(true);
            rule.check(PRODUCTION_CLASSES);
        }

        /**
         * 防空转：上面那条规则写成“禁止”，若白名单边哪天被拆掉，它会因为“无人违反”而静默通过。
         * 这条正面断言保证该边确实存在，两边夹中间才算真的机检（本仓假绿复盘的同款教训）。
         */
        @Test
        @DisplayName("白名单边确实在用（防止规则空转的假通过）")
        void whitelistedEdgeIsLive() {
            boolean used = PRODUCTION_CLASSES.stream()
                .filter(c -> c.getPackageName()
                    .startsWith("io.annona.modules.interview.orchestrator"))
                .flatMap(c -> c.getDirectDependenciesFromSelf().stream())
                .anyMatch(dep -> dep.getTargetClass().getPackageName()
                    .startsWith("io.annona.modules.planner"));
            assertThat(used)
                .as("orchestrator → planner/advisor 已不再是实际调用点：先查最近改动，"
                    + "而不是顺手删掉本断言或放宽规则 8")
                .isTrue();
        }
    }
}
