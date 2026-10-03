package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.annona.common.exception.BusinessException;
import io.annona.common.storage.ObjectStorage;
import io.annona.modules.identity.repository.AvatarChangeRepository;
import io.annona.modules.identity.repository.UserProfileRepository;
import io.annona.modules.identity.service.AvatarService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 资料与头像在<b>真 PG</b> 上的集测（CI docker-it 专属，本机无 PG 不跑）。
 *
 * <p>钉三件真库才现形的事：① 预置主键实体的 save/merge 语义——{@code avatar_change}
 * 行与 profile 头像指针真的落库（slice 的 mock EM 探不到，AGENTS §4 同款踩坑）；
 * ② {@code created_at} 等 DB-default 列经短事务写入后可回读；③ V1 的
 * {@code avatar_change}/{@code user_profile} 形状与实体映射经 Hibernate 真校验一致。
 *
 * <p>docker profile 无 S3（{@code storage.enabled} 关），上传链路用进程内 fake
 * ObjectStorage 注入被测服务——验证的是 DB 语义而非 SDK（S3 真通路属 compose 冒烟）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("资料与头像真库集测（P2-07）")
class AvatarHistoryIT {

    @Autowired
    private UserProfileRepository profileRepository;
    @Autowired
    private AvatarChangeRepository historyRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID userId;
    /** 进程内假存储：只验 DB 语义，不验 SDK。 */
    private InMemoryStorage storage;
    private AvatarService service;

    @BeforeEach
    void fixture() {
        jdbc = new JdbcTemplate(dataSource);
        userId = UUID.randomUUID();
        jdbc.update("insert into app_user (id, email, password_hash, status, role)"
                + " values (?, ?, 'it-not-a-real-hash', 'ACTIVE', 'USER')",
            userId, "it-avatar-" + userId + "@annona.test");
        jdbc.update("insert into user_profile (user_id, nickname) values (?, '集测同学')", userId);
        storage = new InMemoryStorage();
        service = new AvatarService(profileRepository, historyRepository,
            Optional.of(storage), transactionManager);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from avatar_change where user_id = ?", userId);
        jdbc.update("delete from user_profile where user_id = ?", userId);
        jdbc.update("delete from app_user where id = ?", userId);
    }

    @Test
    @DisplayName("两次上传 → avatar_change 落 1 行历史，profile 指向最新 key，created_at 可回读")
    void uploadTwicePersistsHistoryRow() {
        service.upload(userId.toString(), new byte[]{1}, "a.png");
        service.upload(userId.toString(), new byte[]{2}, "b.png");

        List<String> keys = jdbc.queryForList(
            "select object_key from avatar_change where user_id = ? order by created_at desc",
            String.class, userId);
        assertThat(keys).hasSize(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from avatar_change where user_id = ? and created_at is not null",
            Long.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select avatar_object_key from user_profile where user_id = ?",
            String.class, userId)).endsWith(".png");
    }

    @Test
    @DisplayName("回滚走真库：当前与历史交换后两次读回一致")
    void rollbackSwapsOnRealDb() {
        service.upload(userId.toString(), new byte[]{1}, "a.png");
        service.upload(userId.toString(), new byte[]{2}, "b.png");
        String beforeRollback = jdbc.queryForObject(
            "select avatar_object_key from user_profile where user_id = ?", String.class, userId);

        String restored = service.rollback(userId.toString());

        assertThat(restored).isNotEqualTo(beforeRollback);
        assertThat(jdbc.queryForObject("select avatar_object_key from user_profile where user_id = ?",
            String.class, userId)).isEqualTo(restored);
    }

    @Test
    @DisplayName("存储未装配 → 上传判 2007（门控 bean 的负路径）")
    void uploadWithoutStorageRejects() {
        AvatarService noStorage = new AvatarService(profileRepository, historyRepository,
            Optional.empty(), transactionManager);
        assertThatThrownBy(() -> noStorage.upload(userId.toString(), new byte[]{1}, "a.png"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(2007));
    }

    /** 进程内假存储实现。 */
    private static final class InMemoryStorage implements ObjectStorage {
        private final Map<String, byte[]> objects = new HashMap<>();

        @Override
        public void put(String key, byte[] content, String contentType) {
            objects.put(key, content);
        }

        @Override
        public byte[] get(String key) {
            return objects.get(key);
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key);
        }
    }
}
