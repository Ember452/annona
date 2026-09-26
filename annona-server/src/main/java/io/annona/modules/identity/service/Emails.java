package io.annona.modules.identity.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * email 的归一化与基本校验（注册、登录与 identity provider 共用同一口径）。
 *
 * <p>长度上限取 RFC 5321 的 254；格式只做「本地部分@域名.后缀」的宽松校验，不追求完整
 * RFC 5322——目标是挡住会引发后续故障的输入（超长会让 login_attempt.key 撞列长、
 * 无 @ 的纯噪音），不是当邮件系统。
 *
 * <p>{@code public} 而非包级私有：{@code modules.identity.provider} 的 platform 实现也要在
 * 建号前做同一口径校验（绝不用垃圾值落库）。
 */
public final class Emails {

    /** RFC 5321 对 path 的上限（含尖括号折算后的 254）；login_attempt.key 的列宽据此推导（V1）。 */
    public static final int MAX_LENGTH = 254;

    private static final Pattern SIMPLE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private Emails() {
    }

    public static String normalize(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String email) {
        return email != null && email.length() <= MAX_LENGTH && SIMPLE.matcher(email).matches();
    }
}
