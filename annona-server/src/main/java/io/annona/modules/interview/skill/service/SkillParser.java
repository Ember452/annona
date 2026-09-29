package io.annona.modules.interview.skill.service;

import io.annona.modules.interview.skill.model.SkillDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * SKILL.md 与 skill.meta.yml 解析（借 🅖 InterviewSkillService 的正则切分 + SnakeYAML，
 * 改为 Map 手工提取——报错能点名到字段与文件）。纯静态逻辑，无 Spring 依赖。
 *
 * <p>fail-fast 口径（skill-questionbank-adr §决策 1）：front-matter 缺失、必填字段
 * （key/name/description）为空、key 非"全小写-dashed"、正文为空、category 优先级非法
 * → {@link IllegalArgumentException}，消息带资源路径；注册表在启动期整体失败。
 * CRLF 先归一：Windows 检出不会破坏切分。
 */
public final class SkillParser {

    private static final Pattern FRONT_MATTER =
        Pattern.compile("(?s)^---[ \\t]*\\n(.*?)\\n---[ \\t]*\\n?(.*)$");
    /** 与 direction key 同一口径（DirectionKeys：全小写字母数字、dash 分段）。 */
    private static final Pattern KEY_FORMAT = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final List<String> PRIORITIES = List.of("ALWAYS_ONE", "CORE", "NORMAL");

    private SkillParser() {
    }

    /** @param resourceId 仅用于报错定位（如 classpath:skills/java-backend/SKILL.md）。 */
    public static SkillDefinition parse(String resourceId, String content) {
        String normalized = content.replace("\r\n", "\n");
        Matcher matcher = FRONT_MATTER.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(resourceId + ": 缺少 front-matter（文件应以 --- 开头）");
        }
        Map<String, Object> yaml = loadYaml(resourceId, matcher.group(1));
        String key = requiredText(resourceId, yaml, "key");
        if (!KEY_FORMAT.matcher(key).matches()) {
            throw new IllegalArgumentException(resourceId + ": key 需为全小写-dashed，实际：" + key);
        }
        String name = requiredText(resourceId, yaml, "name");
        String description = requiredText(resourceId, yaml, "description");
        String parent = optionalText(yaml.get("parent"));
        String persona = matcher.group(2).strip();
        if (persona.isEmpty()) {
            throw new IllegalArgumentException(resourceId + ": 正文为空（persona 是出题考法的唯一来源）");
        }
        return new SkillDefinition(key, name, description, parent, persona,
            SkillDefinition.SkillMeta.empty());
    }

    /** 解析 skill.meta.yml；文件缺席（传 null/空白）返回 empty，不视为错误（上游同款宽容）。 */
    public static SkillDefinition.SkillMeta parseMeta(String resourceId, String yamlText) {
        if (yamlText == null || yamlText.isBlank()) {
            return SkillDefinition.SkillMeta.empty();
        }
        Map<String, Object> map = loadYaml(resourceId, yamlText);
        String displayName = optionalText(map.get("displayName"));
        SkillDefinition.Display display = toDisplay(resourceId, map.get("display"));
        List<SkillDefinition.Category> categories = new ArrayList<>();
        if (map.get("categories") instanceof List<?> items) {
            for (Object item : items) {
                Map<String, Object> cat = asMap(item);
                if (cat == null) {
                    continue;
                }
                String priority = cat.get("priority") == null ? "NORMAL"
                    : optionalText(cat.get("priority"));
                if (!PRIORITIES.contains(priority)) {
                    throw new IllegalArgumentException(
                        resourceId + ": category " + optionalText(cat.get("key"))
                            + " 的 priority 非法：" + priority);
                }
                categories.add(new SkillDefinition.Category(
                    requiredText(resourceId + " (categories)", cat, "key"),
                    requiredText(resourceId + " (categories)", cat, "label"),
                    priority, optionalText(cat.get("ref")),
                    Boolean.TRUE.equals(cat.get("shared"))));
            }
        }
        return new SkillDefinition.SkillMeta(displayName, display, List.copyOf(categories));
    }

    private static SkillDefinition.Display toDisplay(String resourceId, Object raw) {
        Map<String, Object> map = asMap(raw);
        if (map == null) {
            return null;
        }
        return new SkillDefinition.Display(optionalText(map.get("icon")),
            optionalText(map.get("gradient")), optionalText(map.get("iconBg")),
            optionalText(map.get("iconColor")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String resourceId, String yamlText) {
        Object loaded = new Yaml().load(yamlText);
        if (!(loaded instanceof Map)) {
            throw new IllegalArgumentException(resourceId + ": front-matter 不是键值映射");
        }
        return (Map<String, Object>) loaded;
    }

    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String requiredText(String resourceId, Map<String, Object> yaml, String field) {
        String value = optionalText(yaml.get(field));
        if (value == null) {
            throw new IllegalArgumentException(resourceId + ": 缺少必填字段 " + field);
        }
        return value;
    }

    private static String optionalText(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).strip();
        return value.isEmpty() ? null : value;
    }
}
