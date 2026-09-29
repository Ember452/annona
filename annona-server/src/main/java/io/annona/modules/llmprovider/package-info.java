/**
 * llmprovider 模块（P1b-10）：用户自带 Key（BYOK）的配置存储（AES/GCM 三列密文）、
 * 掩码视图与连通性测试。
 *
 * <p>归属裁决（llmprovider-metering-adr）：加解密实现在 infra.crypto（配置+密码学边界），
 * 本模块只经 {@code ApiKeyCipher} 端口消费；探测同走 common 端口。明文 Key 在本模块
 * 仅存在于请求处理栈帧内。
 *
 * <p>允许依赖：{@code io.annona.common}、{@code io.annona.spi}。禁止依赖其他业务模块。
 */
package io.annona.modules.llmprovider;
