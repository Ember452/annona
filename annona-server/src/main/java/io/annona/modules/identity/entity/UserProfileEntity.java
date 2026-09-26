package io.annona.modules.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * 用户可编辑资料（V1 {@code user_profile}，与 app_user 1:1，主键即 user_id）。
 */
@Entity
@Table(name = "user_profile")
public class UserProfileEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "nickname")
    private String nickname;

    @Column(name = "timezone", nullable = false)
    private String timezone = "Asia/Shanghai";

    @Column(name = "theme_key", nullable = false)
    private String themeKey = "rainforest";

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getThemeKey() {
        return themeKey;
    }

    public void setThemeKey(String themeKey) {
        this.themeKey = themeKey;
    }
}
