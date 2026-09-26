package io.annona.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Emails：归一化与格式/长度校验（注册与登录同口径）")
class EmailsTest {

    @Test
    @DisplayName("normalize 去首尾空白并小写；null 透传")
    void normalizeTrimsAndLowercases() {
        assertThat(Emails.normalize("  Foo@Bar.EXAMPLE ")).isEqualTo("foo@bar.example");
        assertThat(Emails.normalize(null)).isNull();
    }

    @Test
    @DisplayName("常规邮箱通过；缺 @、带空格、无域名后缀、超 RFC 254 上限的都拒绝")
    void validatesFormatAndLength() {
        assertThat(Emails.isValid("user@example.com")).isTrue();
        assertThat(Emails.isValid("user+tag@example.co.jp")).isTrue();
        assertThat(Emails.isValid("not-an-email")).isFalse();
        assertThat(Emails.isValid("a b@example.com")).isFalse();
        assertThat(Emails.isValid("a@b")).isFalse();
        // 250 个 a + @example.test = 263 > 254
        assertThat(Emails.isValid("a".repeat(250) + "@example.test")).isFalse();
        assertThat(Emails.isValid("")).isFalse();
        assertThat(Emails.isValid(null)).isFalse();
    }
}
