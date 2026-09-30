package io.annona.common.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 内容 hash 工具（SHA-256 十六进制）：文档/简历级做 (user_id, file_hash) 幂等键，
 * 分块级做 content_hash 差分。原在 knowledge 模块，因简历链（P1b-08）也需同一口径
 * 且不得跨模块 import，按 AGENTS §4"基础设施能力放 common"下沉至此（纯 JDK，无框架）。
 */
public final class ContentHashes {

    private ContentHashes() {
    }

    public static String sha256Hex(byte[] content) {
        return HexFormat.of().formatHex(digest().digest(content));
    }

    public static String sha256Hex(String text) {
        return HexFormat.of().formatHex(digest().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256，环境异常", e);
        }
    }
}
