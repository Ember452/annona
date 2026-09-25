/**
 * 安全层装配：过滤器链、密码编码器、Key 解密上下文、CSP。
 *
 * <p>P0-05 阶段本包<b>仅有 package-info</b>。真实落地点：
 * <ul>
 *   <li>P1a-01：{@code SecurityFilterChain}（session cookie 校验 + CSRF 排除 /api/stream/**）
 *       + scrypt {@code PasswordEncoder}（支持透明重哈希，见 identity ADR §后果 §3）</li>
 *   <li>P1b-10：KEK 解密上下文（{@code AnnonaKekContext}），负责从
 *       {@code annona.kek.secret} 读出主密钥并暴露给 {@code ApiKeyCipherService}</li>
 *   <li>P2-05：匿名共学只读接口的 rate limit filter（配合 {@code @RateLimit}）</li>
 *   <li>P3-01：WebSocket 握手的 origin 校验（防跨站 WS Hijacking）</li>
 * </ul>
 *
 * <p>注意：annona <b>不引入 Spring Security 全家桶</b>——只用其中的 crypto 部分
 * （scrypt 编码器）；filter chain 用 Servlet Filter 手写，避免"配置比代码多"的
 * 学习曲线。选它的理由见 docs/specs/2026-09-25-identity-credential-storage-adr。
 */
package io.annona.config.security;
