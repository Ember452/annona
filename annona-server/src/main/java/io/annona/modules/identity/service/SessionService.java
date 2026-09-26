package io.annona.modules.identity.service;

import io.annona.common.session.SessionStore;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 会话读写侧：解析令牌 → userId（命中即滑动续期），以及登出删除。 */
@Service
public class SessionService {

    private final SessionStore sessionStore;
    private final SessionProperties props;

    public SessionService(SessionStore sessionStore, SessionProperties props) {
        this.sessionStore = sessionStore;
        this.props = props;
    }

    public Optional<String> resolveAndSlide(String token) {
        Optional<String> userId = sessionStore.readUserId(token);
        userId.ifPresent(id -> sessionStore.touch(token, props.getTtl()));
        return userId;
    }

    public void logout(String token) {
        sessionStore.delete(token);
    }
}
