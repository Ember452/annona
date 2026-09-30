package io.annona.modules.llmprovider.service;

import io.annona.common.crypto.ApiKeyCipher;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.llm.LlmConnectivityProbe;
import io.annona.modules.llmprovider.dto.ProviderTestResponse;
import io.annona.modules.llmprovider.dto.ProviderView;
import io.annona.modules.llmprovider.dto.SaveProviderRequest;
import io.annona.modules.llmprovider.entity.LlmProviderConfigEntity;
import io.annona.modules.llmprovider.repository.LlmProviderConfigRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider 配置编排（P1b-10）：CRUD + 连通性测试。明文 Key 只存在于方法参数与
 * decrypt 返回值两处栈帧——实体无明文字段、视图只有掩码，泄漏面被类型结构限制。
 *
 * <p>事务边界：写路径全部是本地 DB 事务；{@link #testConnection} 不标事务——
 * 探测 HTTP 没理由占着连接，读实体走 repository 自动短事务。
 *
 * <p>加密同步执行（对计划"cpuExecutor"的偏离，已记 metering ADR）：单条 Key 微秒级，
 * 抛池再 join 纯属为异步而异步；批量轮换重加密（annona reencrypt，P 后期）才需要池。
 */
@Service
public class LlmProviderService {

    /** 与 V10 chk_provider_purpose 同枚举；改一边必须改另一边（机检候选：迁移注释互指）。 */
    public static final Set<String> PURPOSES =
        Set.of("chat", "embedding", "rerank", "tts", "asr", "evaluator");

    private final LlmProviderConfigRepository repository;
    private final ApiKeyCipher cipher;
    private final LlmConnectivityProbe probe;

    public LlmProviderService(LlmProviderConfigRepository repository, ApiKeyCipher cipher,
                              LlmConnectivityProbe probe) {
        this.repository = repository;
        this.cipher = cipher;
        this.probe = probe;
    }

    @Transactional(readOnly = true)
    public List<ProviderView> list(UUID userId) {
        return repository.findByUserIdOrderByProviderKeyAscPurposeAsc(userId).stream()
            .map(LlmProviderService::toView)
            .toList();
    }

    /** 新建配置；(user, provider, purpose) 冲突 → 2901。 */
    @Transactional
    public ProviderView create(UUID userId, SaveProviderRequest request) {
        validate(request);
        if (repository.existsByUserIdAndProviderKeyAndPurpose(
            userId, request.providerKey(), request.purpose())) {
            throw new BusinessException(ErrorCode.PROVIDER_KEY_DUPLICATE);
        }
        var encrypted = cipher.encrypt(requireApiKey(request));
        LlmProviderConfigEntity entity = new LlmProviderConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        apply(entity, request);
        entity.setApiKeyNonce(encrypted.nonce());
        entity.setApiKeyCipher(encrypted.ciphertext());
        entity.setKekVersion(encrypted.kekVersion());
        entity.setApiKeyMasked(encrypted.masked());
        entity.setUpdatedAt(Instant.now());
        return toView(repository.save(entity));
    }

    /**
     * 编辑配置；{@code apiKey} 留空 = 保留原密文三列不动（掩码也不变——前端只回显掩码，
     * 没有明文可重算）。provider/purpose 改动撞已有行 → 2901。
     */
    @Transactional
    public ProviderView update(UUID userId, UUID id, SaveProviderRequest request) {
        validate(request);
        LlmProviderConfigEntity entity = owned(userId, id);
        if (!entity.getProviderKey().equals(request.providerKey())
            || !entity.getPurpose().equals(request.purpose())) {
            if (repository.existsByUserIdAndProviderKeyAndPurpose(
                userId, request.providerKey(), request.purpose())) {
                throw new BusinessException(ErrorCode.PROVIDER_KEY_DUPLICATE);
            }
        }
        apply(entity, request);
        String apiKey = request.apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            var encrypted = cipher.encrypt(apiKey);
            entity.setApiKeyNonce(encrypted.nonce());
            entity.setApiKeyCipher(encrypted.ciphertext());
            entity.setKekVersion(encrypted.kekVersion());
            entity.setApiKeyMasked(encrypted.masked());
        }
        entity.setUpdatedAt(Instant.now());
        return toView(entity);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        repository.delete(owned(userId, id));
    }

    /** 连通性测试（不落账：探测请求不计入用户配额与用量账，见 metering ADR）。 */
    public ProviderTestResponse testConnection(UUID userId, UUID id) {
        LlmProviderConfigEntity entity = owned(userId, id);
        String plain = cipher.decrypt(new ApiKeyCipher.EncryptedApiKey(
            entity.getApiKeyNonce(), entity.getApiKeyCipher(),
            entity.getKekVersion(), entity.getApiKeyMasked()));
        var result = probe.probe(entity.getBaseUrl(), plain, entity.getDefaultModel());
        if (!result.ok()) {
            throw new BusinessException(ErrorCode.PROVIDER_TEST_FAILED, result.message());
        }
        return new ProviderTestResponse(true, result.message());
    }

    private LlmProviderConfigEntity owned(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND));
    }

    private static void apply(LlmProviderConfigEntity entity, SaveProviderRequest request) {
        entity.setProviderKey(request.providerKey().strip());
        entity.setBaseUrl(blankToNull(request.baseUrl()));
        entity.setPurpose(request.purpose());
        entity.setDefaultModel(blankToNull(request.defaultModel()));
        entity.setEnabled(request.enabled() == null || request.enabled());
    }

    private static void validate(SaveProviderRequest request) {
        if (request.providerKey() == null || request.providerKey().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "provider 标识不能为空");
        }
        if (!PURPOSES.contains(request.purpose())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "用途不合法：" + request.purpose() + "（可选 " + PURPOSES + "）");
        }
    }

    private static String requireApiKey(SaveProviderRequest request) {
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "新建配置必须提供 API Key");
        }
        return request.apiKey();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ProviderView toView(LlmProviderConfigEntity entity) {
        return new ProviderView(entity.getId().toString(), entity.getProviderKey(),
            entity.getBaseUrl(), entity.getPurpose(), entity.getApiKeyMasked(),
            entity.getDefaultModel(), entity.isEnabled());
    }
}
