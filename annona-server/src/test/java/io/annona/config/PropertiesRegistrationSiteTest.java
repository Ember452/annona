package io.annona.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * 属性注册点门禁（AGENTS.md §4「约定 → 机检」元规则；违反一次即配卡口）。
 *
 * <p>要防的是这一类只在真上下文里才暴露的装配错配：主类没有
 * {@code @ConfigurationPropertiesScan}，所以每个 {@code @ConfigurationProperties} 都必须由某个
 * {@code @EnableConfigurationProperties}（或带该注解的 {@code @Bean} 方法，或类自身的
 * {@code @Component} 自注册）拿到 bean。若某个属性类的
 * <b>注册点全部落在带 {@code @ConditionalOnWebApplication(SERVLET)} 的配置上</b>，
 * {@code webEnvironment=NONE} 的上下文（docker IT 的主流形态）就拿不到这个 bean——而消费它的
 * {@code @Service}/{@code @Component} 不受那道门控，于是整个 ApplicationContext 起不来，
 * 一崩就是几十个 IT 连坐（CI run #115：{@code VoiceProperties} 只注册在 web 门控的
 * {@code VoiceWebSocketConfig} 上，voice service 缺 bean，61 个 IT 全部报错）。
 *
 * <p>规则刻意只卡"全部注册点都是 web 门控"这一条：属性类按门控装配是正常的（provider=none
 * 时通道 bean 与属性一起缺席），只有"跟着 web 走"才会与不受门控的消费方脱节。
 *
 * <p>本测试不启动 Spring 上下文（纯反射扫包），所以本机 {@code mvnw verify} 就能跑，
 * 不需要 PG/Redis/Docker——这条约束的证伪点不能只在 CI 上等。
 */
@Tag("slice")
@DisplayName("属性注册点不得只挂在 web 门控配置上（P3 批 2 CI run #115 教训）")
class PropertiesRegistrationSiteTest {

    private static final String BASE_PACKAGE = "io.annona";

    /** 属性类 → 它的注册点类集合（@EnableConfigurationProperties 的持有者）。 */
    private static Map<Class<?>, Set<Class<?>>> registrationSites;

    @BeforeAll
    static void scanRegistrations() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));
        List<Class<?>> propertyClasses = load(scanner);

        scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(EnableConfigurationProperties.class));
        List<Class<?>> holders = load(scanner);

        Map<Class<?>, Set<Class<?>>> sites = new HashMap<>();
        for (Class<?> holder : holders) {
            for (Class<?> enabled : holder.getAnnotation(EnableConfigurationProperties.class).value()) {
                sites.computeIfAbsent(enabled, k -> new LinkedHashSet<>()).add(holder);
            }
        }
        registrationSites = sites;

        // 反空转：持有者与属性类都必须真扫到，否则下面的断言会绿得没有意义
        // （下限取历史值，只防扫描失效，不承诺计数）
        assertThat(holders)
            .as("没扫到任何 @EnableConfigurationProperties 持有者，门禁在空跑").hasSizeGreaterThanOrEqualTo(8);
        assertThat(propertyClasses)
            .as("没扫到任何 @ConfigurationProperties 类，门禁在空跑").hasSizeGreaterThanOrEqualTo(10);
    }

    private static List<Class<?>> load(ClassPathScanningCandidateComponentProvider scanner) {
        List<Class<?>> found = new ArrayList<>();
        for (var definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            try {
                found.add(Class.forName(definition.getBeanClassName(), false,
                    Thread.currentThread().getContextClassLoader()));
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                throw new AssertionError("扫描到的类无法加载：" + definition.getBeanClassName(), e);
            }
        }
        return found;
    }

    @Test
    @DisplayName("每个属性类都要有注册点，且至少一个不受 web 门控")
    void everyPropertiesClassHasNonWebGatedRegistrationSite() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));

        List<String> unregistered = new ArrayList<>();
        List<String> webOnly = new ArrayList<>();
        for (Class<?> type : load(scanner)) {
            Set<Class<?>> sites = registrationSites.get(type);
            if (sites == null || sites.isEmpty()) {
                // @Component 自注册的属性类本身就是普通 bean，不随任何条件配置缺席（identity 先例）
                if (!componentStereotyped(type) && !registeredByBeanMethod(type)) {
                    unregistered.add(type.getSimpleName());
                }
                continue;
            }
            boolean anyUngated = sites.stream()
                .noneMatch(holder -> holder.isAnnotationPresent(ConditionalOnWebApplication.class));
            if (!anyUngated) {
                webOnly.add(type.getSimpleName() + " 只由 " + sites + " 注册（全部带 web 门控）");
            }
        }

        assertThat(unregistered)
            .as("这些 @ConfigurationProperties 既无 @EnableConfigurationProperties 也无 @Bean 方法注册，"
                + "上下文里不会有对应 bean")
            .isEmpty();
        assertThat(webOnly)
            .as("这些属性类的注册点全带 @ConditionalOnWebApplication：webEnvironment=NONE 的上下文"
                + "（docker IT 主流形态）拿不到 bean，而不受门控的 @Service 消费方会让整个上下文起不来"
                + "——把注册点移到不受 web 门控的持有者上（VoiceInterviewService/SkillQueryService 先例）")
            .isEmpty();
    }

    /**
     * 反空转的第二半：本次事故的当事类必须在扫描里真的拿到过一个不受门控的注册点。
     * 只查"全部门控"的话，若哪天注册点整个消失，上面的循环会把该类归到 unregistered 里——
     * 这条断言把 voice 的教训钉成具体事实，读失败信息的人不需要再猜是哪一类问题。
     */
    @Test
    @DisplayName("VoiceProperties 的注册点不受 web 门控（本批事故的定点回归）")
    void voicePropertiesSiteIsNotWebGated() {
        Set<Class<?>> sites = registrationSites.get(io.annona.modules.voice.config.VoiceProperties.class);
        assertThat(sites)
            .as("VoiceProperties 必须有自己的注册点")
            .isNotEmpty();
        assertThat(sites)
            .as("VoiceProperties 不得只由 @ConditionalOnWebApplication 的配置注册")
            .anyMatch(holder -> !holder.isAnnotationPresent(ConditionalOnWebApplication.class));
    }

    /** @Bean 方法上直接标 @ConfigurationProperties 也算注册点（Boot 支持的另一种绑法）。 */
    private static boolean registeredByBeanMethod(Class<?> propertiesType) {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(
            org.springframework.context.annotation.Configuration.class));
        for (Class<?> config : load(scanner)) {
            for (Method method : config.getDeclaredMethods()) {
                var bean = method.getAnnotation(org.springframework.context.annotation.Bean.class);
                if (bean != null && method.isAnnotationPresent(ConfigurationProperties.class)
                    && method.getReturnType().equals(propertiesType)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 属性类自身带 {@code @Component}（含其 meta 注解）时，组件扫描就是它的注册点。 */
    private static boolean componentStereotyped(Class<?> type) {
        return org.springframework.core.annotation.AnnotationUtils
            .findAnnotation(type, org.springframework.stereotype.Component.class) != null;
    }
}
