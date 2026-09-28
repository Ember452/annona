package io.annona.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.annona.config.async.AnnonaThreadProperties;
import io.annona.config.properties.AnnonaStartupProperties;
import io.annona.config.properties.RetrievalProperties;
import io.annona.infrastructure.cache.RedisProperties;
import io.annona.infrastructure.llm.EmbeddingProperties;
import io.annona.infrastructure.parse.DocumentParseProperties;
import io.annona.infrastructure.storage.StorageProperties;
import io.annona.modules.identity.service.IdentityProperties;
import io.annona.modules.identity.service.SessionProperties;
import io.annona.modules.knowledge.listener.KnowledgeRecoveryProperties;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * 配置默认值单源门禁（AGENTS.md §4）。
 *
 * <p>要防的是这一类静默漂移：同一个键的默认值在 {@code application.yaml} 的
 * {@code ${VAR:default}} 里写一遍，又在 {@code @ConfigurationProperties} 字段初值里写一遍。
 * yaml 一旦列出该键，它就永远提供一个值，<b>类里的初值是死代码</b>；于是"改了类默认以为改了
 * 默认值"会毫无效果，而两处数值还可能各自漂移。
 *
 * <p>规则是<b>一个键恰好有一处默认值出处</b>，两个方向都查：
 * <ul>
 *   <li>yaml 有键 → 类字段不得自带初值（否则双份）；</li>
 *   <li>类字段带初值 → yaml 不得有同名键（同上，反向同理）；</li>
 *   <li>两处都没有 → 也是错：字段会静默拿到类型零值（port=0、prefix=null），
 *       而这类值要到运行期才炸。</li>
 * </ul>
 *
 * <p>例外只有一种合法形态：默认值<b>必须由代码计算</b>，yaml 表达不了——目前只有
 * {@code annona.thread-pool.cpu}（按 CPU 核数推导）。它走"类初值"这一侧，
 * 并由下面的 {@link #COMPUTED_DEFAULT_PREFIXES} 显式登记；新增例外必须改这个列表，
 * 改列表就是一次评审。
 *
 * <p>本测试不启动 Spring 上下文（纯反射 + yaml 解析），因此本机 {@code mvn verify} 就能跑，
 * 不依赖 PG/Redis/Docker。
 */
@DisplayName("配置默认值必须单源（AGENTS.md §4）")
class PropertiesDefaultSourceTest {

    /** 默认值由代码计算、yaml 无法表达的字段（camelCase 名，见 {@link #fieldKey}）。 */
    private static final List<String> COMPUTED_DEFAULT_PREFIXES = List.of("general", "aiIo", "cpu", "query");

    private static final List<Class<?>> PROPERTY_CLASSES = List.of(
        RedisProperties.class, EmbeddingProperties.class, DocumentParseProperties.class,
        StorageProperties.class, AnnonaThreadProperties.class, AnnonaStartupProperties.class,
        RetrievalProperties.class, IdentityProperties.class, SessionProperties.class,
        KnowledgeRecoveryProperties.class);

    private static Properties yaml;

    @BeforeAll
    static void loadYaml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yaml"));
        factory.afterPropertiesSet();
        yaml = factory.getObject();
        assertThat(yaml)
            .as("classpath 上读不到 application.yaml，本门禁无从判定")
            .isNotNull();
    }

    @Test
    @DisplayName("每个属性字段恰好有一处默认值出处")
    void everyFieldHasExactlyOneDefaultSource() {
        List<String> duplicated = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> computed = new ArrayList<>();

        for (Class<?> type : PROPERTY_CLASSES) {
            String prefix = prefixOf(type);
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                String key = fieldKey(prefix, field);
                boolean inYaml = yaml.containsKey(key);
                boolean hasInitializer = hasInitializer(type, field);
                boolean registered = COMPUTED_DEFAULT_PREFIXES.contains(field.getName())
                    && type == AnnonaThreadProperties.class;

                if (inYaml && hasInitializer) {
                    duplicated.add(key + "（yaml " + yaml.getProperty(key) + " 与类初值并存）");
                } else if (!inYaml && !hasInitializer) {
                    missing.add(key);
                } else if (registered && inYaml) {
                    computed.add(key);
                }
            }
        }

        // 允许登记过的计算型默认继续存在，但它们不得同时出现在 yaml 里
        assertThat(computed).as("登记的计算型默认不该另外出现在 yaml 中").isEmpty();
        assertThat(missing)
            .as("这些字段既没有 yaml 键也没有类初值，运行期会静默拿到类型零值；"
                + "补 yaml 键（首选，可被 env 覆盖）或补类初值并登记进 COMPUTED_DEFAULT_PREFIXES")
            .isEmpty();
        assertThat(duplicated)
            .as("这些字段有两处默认值，类初值永远不会生效；删掉类初值（默认值归 application.yaml）")
            .isEmpty();
    }

    @Test
    @DisplayName("登记例外仍然必要（列表里每一项都确实带类初值）")
    void registeredExceptionsAreStillEarned() {
        List<String> stale = new ArrayList<>();
        for (String name : COMPUTED_DEFAULT_PREFIXES) {
            try {
                Field field = AnnonaThreadProperties.class.getDeclaredField(name);
                if (!hasInitializer(AnnonaThreadProperties.class, field)) {
                    stale.add(name);
                }
            } catch (NoSuchFieldException e) {
                stale.add(name + "（字段已不存在）");
            }
        }
        assertThat(stale)
            .as("这些登记项已经不带类初值了，从 COMPUTED_DEFAULT_PREFIXES 删掉——"
                + "留着的后果是它以后可以在 yaml 与类里各写一份而不被发现")
            .isEmpty();
    }

    private static String prefixOf(Class<?> type) {
        var annotation = type.getAnnotation(
            org.springframework.boot.context.properties.ConfigurationProperties.class);
        assertThat(annotation).as(type + " 缺 @ConfigurationProperties").isNotNull();
        return annotation.prefix();
    }

    /**
     * 与 relaxed binding 等价的键拼法：{@code prefix + '.' + kebab(fieldName)}。
     *
     * <p>分隔符是点而不是连字符：{@link YamlPropertiesFactoryBean} 把嵌套 yaml 展平成
     * {@code annona.redis.host} 这样的 PropertyKey，拼错会把“yaml 已有默认值”误报成“缺失”。
     */
    private static String fieldKey(String prefix, Field field) {
        return prefix + "." + field.getName().replaceAll("([a-z0-9])([A-Z])", "$1-$2")
            .toLowerCase(Locale.ROOT);
    }

    /**
     * 用运行时观察判初值：新建一个未绑定实例，字段值不是类型零值就说明写了初值。
     *
     * <p><b>已知限制</b>：反射看不出 {@code = false} / {@code = 0} / {@code = "\0"} 这类
     * “初值等于零值”的写法（它们与没写初值无法区分）。这类重复得靠 review 抓；
     * 写出 {@code = ""} 是看得出的（空串非 null）。不为此去解源码或字节码：
     * 多一层模糊匹配带来的误报比漏报贵。
     */
    private static boolean hasInitializer(Class<?> type, Field field) {
        Object instance = newInstance(type);
        try {
            field.setAccessible(true);
            Object value = field.get(instance);
            if (value == null) {
                return false;
            }
            if (value instanceof Boolean flag) {
                return flag;
            }
            if (value instanceof Number number) {
                return number.doubleValue() != 0.0d || number.longValue() != 0L;
            }
            if (value instanceof Character chr) {
                return chr.charValue() != '\0';
            }
            return true;
        } catch (IllegalAccessException e) {
            throw new AssertionError("无法读取 " + type.getSimpleName() + "." + field.getName(), e);
        }
    }

    private static Object newInstance(Class<?> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            return fail(type + " 需要无参构造器才能做默认值检查：" + e.getMessage());
        }
    }
}
