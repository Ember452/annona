package io.annona.shared.direction.dto;

/**
 * 即时新建方向（origin 固定 USER_CUSTOM，归属当前用户）。
 *
 * @param name 展示名，必填 1–128 字符
 * @param key  全小写-dashed 标识，可选；缺省由名称推导（纯 ASCII 名 slug 化，含中文落 custom-随机段）
 */
public record CreateDirectionRequest(String name, String key) {
}
