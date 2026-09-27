package io.annona.modules.knowledge.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 内容 hash 工具（SHA-256 十六进制，借 🅖 FileHashService 的算法与输出口径）：
 * 文档级做 (user_id, file_hash) 幂等键，分块级做 content_hash 差分记录。
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
