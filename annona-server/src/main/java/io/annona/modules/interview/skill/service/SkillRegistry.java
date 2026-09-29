package io.annona.modules.interview.skill.service;

import io.annona.modules.interview.skill.model.SkillDefinition;
import io.annona.shared.direction.service.SkillDirectionCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * 技能注册表（skill-questionbank-adr §决策 1）：启动扫描技能根目录下各子目录的 SKILL.md，
 * 解析结果按 front-matter key 索引；缺必填字段、key 重复、parent 悬空、目录为空 →
 * {@link IllegalStateException} 拒绝启动（报错带文件路径）。加载完成后只读（TreeMap 排序、
 * @PostConstruct 内写入对 refresh 后的其他线程可见），无热更新——内置技能变更伴随发版。
 *
 * <p>同时实现 {@link SkillDirectionCatalog} 供给内置方向播种；注册表的 key 集合与
 * direction 表的内置方向一一对应。
 */
@Service
@EnableConfigurationProperties(SkillProperties.class)
public class SkillRegistry implements SkillDirectionCatalog {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    /** 上游同款：单参考文件截断预算（参考正文只进提示词、不出产品面，超长段落尾部静默截断）。 */
    static final int MAX_REFERENCE_CHARS = 3000;
    /** 目录穿越防护（借上游 isSafeReferencePath）：白名单字符 + 显式禁 .. 与绝对路径。 */
    private static final Pattern SAFE_REF = Pattern.compile("[a-zA-Z0-9._/-]+");

    private final PathMatchingResourcePatternResolver resolver =
        new PathMatchingResourcePatternResolver();
    private final String root;
    private final Map<String, SkillDefinition> registry = new TreeMap<>();

    public SkillRegistry(SkillProperties properties) {
        this.root = properties.getRoot();
    }

    @PostConstruct
    void load() {
        List<Resource> resources;
        try {
            resources = List.of(resolver.getResources(root + "/*/SKILL.md"));
        } catch (IOException e) {
            throw new IllegalStateException("技能目录不可读：" + root, e);
        }
        for (Resource resource : resources) {
            String dirName = dirNameOf(resource);
            if (dirName == null || "_shared".equals(dirName)) {
                continue;
            }
            String resourceId = root + "/" + dirName + "/SKILL.md";
            SkillDefinition definition = SkillParser.parse(resourceId, read(resource, resourceId));
            if (registry.containsKey(definition.key())) {
                throw new IllegalStateException("技能 key 重复：" + definition.key()
                    + "（" + resourceId + " 与已加载条目冲突）");
            }
            registry.put(definition.key(), definition);
            loadMeta(dirName, definition.key());
        }
        verifyParents();
        if (registry.isEmpty()) {
            throw new IllegalStateException("技能目录下没有找到任何 SKILL.md：" + root);
        }
        log.info("技能注册表加载完成：{} 个内置技能（{}）", registry.size(), root);
    }

    /** meta 缺席宽容（上游同款），坏内容不宽容——category 字段错误要在启动期暴露。 */
    private void loadMeta(String dirName, String key) {
        String metaLocation = root + "/" + dirName + "/skill.meta.yml";
        Resource resource = resolver.getResource(metaLocation);
        if (!resource.exists()) {
            return;
        }
        SkillDefinition.SkillMeta meta = SkillParser.parseMeta(metaLocation, read(resource, metaLocation));
        SkillDefinition without = registry.get(key);
        registry.put(key, new SkillDefinition(without.key(), without.name(), without.description(),
            without.parent(), without.persona(), meta));
    }

    private void verifyParents() {
        for (SkillDefinition definition : registry.values()) {
            if (definition.parent() != null && !registry.containsKey(definition.parent())) {
                throw new IllegalStateException("技能 " + definition.key() + " 的 parent 悬空："
                    + definition.parent());
            }
        }
    }

    /**
     * 技能目录名 = URL 中紧邻 /SKILL.md 的上一段。不按根目录名（skills / 夹具目录）写死，
     * 根目录可配置才成立；不匹配的后缀（理论不可达，glob 保证）返回 null 由调用方跳过。
     */
    private String dirNameOf(Resource resource) {
        try {
            String url = resource.getURL().toString();
            String suffix = "/SKILL.md";
            if (!url.endsWith(suffix)) {
                return null;
            }
            String prefix = url.substring(0, url.length() - suffix.length());
            int slash = prefix.lastIndexOf('/');
            return slash < 0 ? null : prefix.substring(slash + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String read(Resource resource, String resourceId) {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("技能文件不可读：" + resourceId, e);
        }
    }

    public List<SkillDefinition> list() {
        return List.copyOf(registry.values());
    }

    public Optional<SkillDefinition> find(String key) {
        return Optional.ofNullable(registry.get(key));
    }

    /**
     * 参考知识正文解析：shared=true 优先公共池，否则技能本地 references/ → 技能根 →
     * 公共池兜底（上游双路回退口径）。找不到返回空串（组卷侧跳过该段，不炸主流程）；
     * 路径不安全直接拒绝——ref 来自仓内 meta 文件，非法即内容错误，静默吞掉会掩盖笔误。
     */
    public String resolveReference(String skillKey, String ref, boolean shared) {
        if (ref == null || ref.isBlank()) {
            return "";
        }
        if (!SAFE_REF.matcher(ref).matches() || ref.contains("..") || ref.startsWith("/")) {
            throw new IllegalArgumentException("参考文件路径不安全：" + ref);
        }
        List<String> candidates = shared
            ? List.of(root + "/_shared/references/" + ref,
                      root + "/" + skillKey + "/references/" + ref)
            : List.of(root + "/" + skillKey + "/references/" + ref,
                      root + "/" + skillKey + "/" + ref,
                      root + "/_shared/references/" + ref);
        for (String location : candidates) {
            Resource resource = resolver.getResource(location);
            if (resource.exists()) {
                String content = read(resource, location).strip();
                return content.length() > MAX_REFERENCE_CHARS
                    ? content.substring(0, MAX_REFERENCE_CHARS)
                    : content;
            }
        }
        log.warn("参考文件未找到：skill={} ref={}（已跳过该参考段）", skillKey, ref);
        return "";
    }

    @Override
    public List<BuiltinDirectionSpec> builtinDirections() {
        return list().stream()
            .map(definition -> new BuiltinDirectionSpec(definition.key(), definition.name()))
            .toList();
    }
}
