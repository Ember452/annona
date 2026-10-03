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

    /** 当前头像的对象 key（非 URL）；字节在 ObjectStorage，展示经代理端点回读。 */
    @Column(name = "avatar_object_key")
    private String avatarObjectKey;

    @Column(name = "bio")
    private String bio;

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

    public String getAvatarObjectKey() {
        return avatarObjectKey;
    }

    public void setAvatarObjectKey(String avatarObjectKey) {
        this.avatarObjectKey = avatarObjectKey;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
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
