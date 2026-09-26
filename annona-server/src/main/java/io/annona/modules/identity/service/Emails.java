package io.annona.modules.identity.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * email 的归一化与基本校验（注册与登录共用同一口径）。
 *
 * <p>长度上限取 RFC 5321 的 254；格式只做「本地部分@域名.后缀」的宽松校验，不追求完整
 * RFC 5322——目标是挡住会引发后续故障的输入（超长会让 login_attempt.key 撞列长、
 * 无 @ 的纯噪音），不是当邮件系统。
 */
final class Emails {

    /** RFC 5321 对 path 的上限（含尖括号折算后的 254）；login_attempt.key 的列宽据此推导（V1）。 */
    static final int MAX_LENGTH = 254;

    private static final Pattern SIMPLE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private Emails() {
    }

    static String normalize(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    static boolean isValid(String email) {
        return email != null && email.length() <= MAX_LENGTH && SIMPLE.matcher(email).matches();
    }
}
