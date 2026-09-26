package io.annona.shared.direction.service;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * direction.key 的生成与校验（纯逻辑，无 Spring 依赖，同 identity 的 {@code Emails} 定位）。
 *
 * <p>key 形如 {@code java-concurrency}：全小写字母/数字、段间单连字符、≤64（V1 列宽），
 * owner 内唯一（{@code uq_direction_owner_key}），不是全局唯一。
 *
 * <p>名称是自由文本且以中文为主：纯 ASCII 名称 slug 化；<b>含任一非 ASCII 字符</b>则
 * 不强行转写（"Java 并发" 逐词截断会丢语义并互相撞名），统一落 {@code custom-<8位hex>}——
 * 展示靠 name，key 只需 owner 内稳定唯一。
 */
public final class DirectionKeys {

    /** V1 direction.key 列宽。 */
    public static final int MAX_LENGTH = 64;

    private static final Pattern KEY_FORMAT = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final String CUSTOM_PREFIX = "custom-";

    private DirectionKeys() {
    }

    /**
     * 生成 key：显式提供则校验后原样采用；否则按名称推导（slug 或 custom-随机段）。
     *
     * @throws IllegalArgumentException 显式 key 为空/格式非法/超长
     */
    public static String generate(String name, String providedKey) {
        if (providedKey != null && !providedKey.isBlank()) {
            return validate(providedKey.trim());
        }
        String slug = slugify(name);
        return slug.isEmpty()
            ? CUSTOM_PREFIX + UUID.randomUUID().toString().substring(0, 8)
            : slug;
    }

    /** 校验显式提供的 key，合法则原样返回。 */
    public static String validate(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_LENGTH
            || !KEY_FORMAT.matcher(key).matches()) {
            throw new IllegalArgumentException("方向标识需为全小写字母/数字、段间单连字符，最长 64 字符");
        }
        return key;
    }

    /**
     * ASCII 名称 → slug（小写、非字母数字转单连字符、去首尾、截断到列宽）。
     * 含非 ASCII 或推导结果为空时返回空串，由调用方落 custom- 前缀。
     */
    private static String slugify(String name) {
        if (name == null || name.isBlank() || !isAscii(name)) {
            return "";
        }
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        slug = trimDashes(slug);
        if (slug.length() > MAX_LENGTH) {
            slug = trimDashes(slug.substring(0, MAX_LENGTH));
        }
        return slug;
    }

    private static boolean isAscii(String value) {
        return value.chars().allMatch(c -> c < 128);
    }

    private static String trimDashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }
}
