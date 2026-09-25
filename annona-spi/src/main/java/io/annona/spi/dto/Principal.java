package io.annona.spi.dto;

import java.util.Set;

/**
 * 已认证的调用方主体。跨模块只读契约，仅承载"谁在调用"，不含角色之外的权限细节。
 *
 * @param id          用户主键（{@code app_user.id}）或平台侧 unionId
 * @param displayName 面向 UI 展示的名字
 * @param roles       粗粒度角色集（{@code USER / ADMIN} 等），细粒度授权由模块自行判定
 */
public record Principal(String id, String displayName, Set<String> roles) {
}
