package io.annona.modules.llmprovider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.crypto.ApiKeyCipher;
import io.annona.common.crypto.ApiKeyCipher.EncryptedApiKey;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.llm.LlmConnectivityProbe;
import io.annona.modules.llmprovider.dto.ProviderView;
import io.annona.modules.llmprovider.dto.SaveProviderRequest;
import io.annona.modules.llmprovider.entity.LlmProviderConfigEntity;
import io.annona.modules.llmprovider.repository.LlmProviderConfigRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Provider 编排：加密落库、掩码出参、密文保留语义与探测翻译（加密本体在 infra 单测）。 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmProviderServiceTest {

    @Mock
    private LlmProviderConfigRepository repository;
    @Mock
    private ApiKeyCipher cipher;
    @Mock
    private LlmConnectivityProbe probe;

    private LlmProviderService service;

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PLAIN = "sk-sup3rsecret-key-9527";

    @BeforeEach
    void setUp() {
        when(cipher.encrypt(anyString())).thenReturn(new EncryptedApiKey(
            new byte[12], "cipher-bytes".getBytes(StandardCharsets.UTF_8), "v1", "sk-s…27"));
        service = new LlmProviderService(repository, cipher, probe);
    }

    private static SaveProviderRequest request(String apiKey, String purpose) {
        return new SaveProviderRequest("deepseek", null, purpose, apiKey, "deepseek-chat", true);
    }

    @Test
    @DisplayName("新建：Key 即刻密文化，视图只含掩码（响应结构上不存在明文槽位）")
    void createEncryptsImmediately() {
        when(repository.existsByUserIdAndProviderKeyAndPurpose(any(), any(), any()))
            .thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProviderView view = service.create(USER, request(PLAIN, "chat"));

        assertThat(view.maskedApiKey()).isEqualTo("sk-s…27");
        assertThat(view.toString()).doesNotContain(PLAIN);
        var saved = org.mockito.ArgumentCaptor.forClass(LlmProviderConfigEntity.class);
        verify(repository).save(saved.capture());
        assertThat(new String(saved.getValue().getApiKeyCipher(), StandardCharsets.UTF_8))
            .doesNotContain(PLAIN);
    }

    @Test
    @DisplayName("同 (user, provider, purpose) 冲突 → 2901，不加密不落库")
    void duplicateRejected() {
        when(repository.existsByUserIdAndProviderKeyAndPurpose(any(), any(), any()))
            .thenReturn(true);

        assertThatThrownBy(() -> service.create(USER, request(PLAIN, "chat")))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode())
                    .isEqualTo(ErrorCode.PROVIDER_KEY_DUPLICATE.getCode()));
        verify(cipher, never()).encrypt(anyString());
    }

    @Test
    @DisplayName("非法 purpose → 1001")
    void invalidPurpose() {
        assertThatThrownBy(() -> service.create(USER, request(PLAIN, "magic")))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
    }

    @Test
    @DisplayName("编辑留空 apiKey：密文三列原样保留（掩码不变）")
    void updateKeepsCipherWithoutNewKey() {
        var entity = existing();
        when(repository.findByIdAndUserId(entity.getId(), USER)).thenReturn(Optional.of(entity));
        var originalCipher = entity.getApiKeyCipher();

        service.update(USER, entity.getId(), request(null, "chat"));

        assertThat(entity.getApiKeyCipher()).isSameAs(originalCipher);
        verify(cipher, never()).encrypt(anyString());
    }

    @Test
    @DisplayName("连通性失败：probe 的安全文案原样进 2902（探测用解密后的明文）")
    void testFailureTranslates() {
        var entity = existing();
        when(repository.findByIdAndUserId(entity.getId(), USER)).thenReturn(Optional.of(entity));
        when(cipher.decrypt(any())).thenReturn(PLAIN);
        when(probe.probe(any(), anyString(), any()))
            .thenReturn(new LlmConnectivityProbe.ProbeResult(false, "鉴权失败：请检查 API Key 是否有效"));

        assertThatThrownBy(() -> service.testConnection(USER, entity.getId()))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getCode()).isEqualTo(ErrorCode.PROVIDER_TEST_FAILED.getCode());
                assertThat(e.getMessage()).contains("鉴权失败");
            });
    }

    private LlmProviderConfigEntity existing() {
        var entity = new LlmProviderConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(USER);
        entity.setProviderKey("deepseek");
        entity.setPurpose("chat");
        entity.setApiKeyNonce(new byte[12]);
        entity.setApiKeyCipher("old".getBytes(StandardCharsets.UTF_8));
        entity.setKekVersion("v1");
        entity.setApiKeyMasked("sk-s…27");
        entity.setDefaultModel("deepseek-chat");
        entity.setEnabled(true);
        entity.setUpdatedAt(java.time.Instant.now());
        return entity;
    }
}
