package io.annona.common.exception;

/**
 * annona 业务错误码。HTTP 状态码固定 200，业务失败靠本枚举的 {@code code} 区分。
 *
 * <p>分段规则（与 annona 模块一一对应，新增模块时按顺序续段，不复用历史段号）：
 * <ul>
 *   <li>1000–1099：通用（请求/权限/系统兜底）</li>
 *   <li>1100–1199：AI 与模型调用</li>
 *   <li>1200–1299：限流与配额</li>
 *   <li>2000+：按业务模块分段（一个模块一个百位段，与模块一一对应）</li>
 * </ul>
 *
 * <p>P0-03 只落通用与 AI 两组；其余模块的错误码在各自阶段任务落地时追加，
 * 禁止提前预留空位（YAGNI）。
 */
public enum ErrorCode {

    // ========== 通用 1000–1099 ==========
    INTERNAL_ERROR(1000, "服务器内部错误"),
    BAD_REQUEST(1001, "请求参数错误"),
    NOT_FOUND(1002, "资源不存在"),
    METHOD_NOT_ALLOWED(1003, "请求方法不支持"),
    UNAUTHORIZED(1004, "未授权"),
    FORBIDDEN(1005, "禁止访问"),
    DATA_CONFLICT(1006, "数据冲突或重复提交，请刷新后重试"),

    // ========== AI 与模型 1100–1199 ==========
    AI_SERVICE_UNAVAILABLE(1100, "AI 服务暂时不可用，请稍后重试"),
    AI_SERVICE_TIMEOUT(1101, "AI 服务响应超时"),
    AI_SERVICE_ERROR(1102, "AI 服务调用失败"),
    AI_API_KEY_INVALID(1103, "AI 服务密钥无效"),
    AI_STREAM_FAILED(1104, "AI 流式响应失败"),
    AI_STREAM_INTERRUPTED(1105, "AI 流式响应中断"),

    // ========== 限流与配额 1200–1299 ==========
    RATE_LIMIT_EXCEEDED(1200, "请求过于频繁，请稍后再试"),

    // ========== identity 2000–2099（P1a-01） ==========
    EMAIL_ALREADY_REGISTERED(2001, "该邮箱已被注册"),
    INVALID_CREDENTIALS(2002, "邮箱或密码不正确"),
    ACCOUNT_LOCKED(2003, "登录失败次数过多，账号已临时锁定，请稍后再试"),
    SESSION_EXPIRED(2004, "登录状态已失效，请重新登录"),
    AVATAR_TOO_LARGE(2005, "头像图片过大（上限 5MB）"),
    AVATAR_TYPE_NOT_SUPPORTED(2006, "头像仅支持 jpg/png/webp"),
    AVATAR_STORAGE_NOT_CONFIGURED(2007, "对象存储未配置，无法上传头像"),
    AVATAR_NO_HISTORY(2008, "没有可回滚的历史头像"),

    // ========== direction 2100–2199（P1a-03，shared 主数据） ==========
    DIRECTION_NOT_FOUND(2100, "方向不存在"),
    DIRECTION_KEY_DUPLICATE(2101, "同名方向已存在"),
    DIRECTION_LIMIT_REACHED(2102, "自定义方向已达上限（200），请先归档或合并"),

    // ========== study 2200–2299（P1a-04，学习行为采集） ==========
    STUDY_SESSION_NOT_FOUND(2200, "学习会话不存在"),
    STUDY_SESSION_ALREADY_FINISHED(2201, "学习会话已结束"),
    STUDY_SESSION_INVALID_RANGE(2202, "时间范围无效"),

    // ========== knowledge 2300–2399（P1a-05，知识库入库） ==========
    KB_DOC_NOT_FOUND(2300, "知识库文档不存在"),
    KB_DOC_TOO_LARGE(2301, "文件超过大小上限（50MB）"),
    KB_DOC_TYPE_NOT_SUPPORTED(2302, "暂不支持该文件类型（支持 PDF / DOCX / TXT / MD）"),
    KB_DOC_PARSE_FAILED(2303, "文档解析失败"),
    KB_DOC_PARSE_TIMEOUT(2304, "文档解析超时"),
    KB_DOC_TEXT_EMPTY(2305, "文档解析后没有可用文本"),
    KB_DOC_STORAGE_NOT_CONFIGURED(2306, "对象存储未配置，无法上传文档"),
    KB_DOC_ENQUEUE_FAILED(2307, "文档已保存但处理任务投递失败，可稍后重试处理"),
    KB_DOC_STATE_CONFLICT(2308, "文档正在处理中，请稍后再试"),
    KB_EMBEDDING_NOT_CONFIGURED(2310, "向量化模型未配置，无法处理文档"),
    KB_EMBEDDING_FAILED(2311, "向量化失败，请稍后重试"),

    // ========== retrieval 2400–2499（P1a-07，混合检索） ==========
    RETRIEVAL_FAILED(2400, "检索暂时不可用，请稍后重试"),
    RETRIEVAL_QUERY_BLANK(2401, "请输入要检索的问题"),

    // ========== qa 2500–2599（P1a-08，流式问答） ==========
    QA_SESSION_NOT_FOUND(2500, "问答会话不存在"),
    QA_MODEL_NOT_CONFIGURED(2502, "问答模型未配置，请先在环境中配置 chat 模型"),

    // ========== questionbank 2600–2699（P1b-02，知识库出题） ==========
    QB_GENERATION_TASK_IN_FLIGHT(2600, "该方向已有出题任务进行中，请等待完成后再试"),
    QB_GENERATION_FAILED(2601, "题目生成失败，请稍后重试"),
    QB_DIRECTION_KB_NOT_READY(2602, "该方向绑定的知识库文档尚未就绪，无法出题"),
    QB_QUESTION_NOT_FOUND(2603, "题目不存在"),
    QB_QUESTION_CAPACITY_INSUFFICIENT(2604, "题目容量不足，无法按当前条件开始面试"),

    // ========== interview 2700–2799（P1b-01 技能注册表；会话段 P1b-04/05 续接） ==========
    SKILL_NOT_FOUND(2700, "技能不存在"),
    SESSION_NOT_FOUND(2701, "面试会话不存在或已过期"),
    SESSION_ALREADY_COMPLETED(2702, "该面试已交卷，请从面试中心查看"),
    SESSION_SLOT_MISMATCH(2703, "作答位置与会话进度不一致，请刷新后重试"),

    // ========== usage 2800–2899（P1b-10，计量与配额） ==========
    QUOTA_EXCEEDED(2800, "今日模型用量已达上限，明天再来；可在设置里查看用量详情"),

    // ========== llmprovider 2900–2999（P1b-10，Provider 配置与 Key） ==========
    PROVIDER_NOT_FOUND(2900, "Provider 配置不存在"),
    PROVIDER_KEY_DUPLICATE(2901, "该 Provider 的此用途已配置，请直接编辑现有条目"),
    PROVIDER_TEST_FAILED(2902, "连通性测试失败，请检查 Base URL 与 Key"),
    PROVIDER_KEY_DECRYPT_FAILED(2903, "密钥解密失败，联系管理员检查 KEK 轮换状态"),

    // ========== evaluation 3000–3099（P1b-06/07，面试评估链） ==========
    EVALUATION_NOT_FOUND(3000, "该面试尚无评估报告（未交卷或评估未开始）"),

    // ========== resume 3100–3199（P1b-08，简历上传与异步分析） ==========
    RESUME_NOT_FOUND(3100, "简历不存在"),
    RESUME_TOO_LARGE(3101, "简历文件过大"),
    RESUME_TYPE_NOT_SUPPORTED(3102, "简历文件格式不支持（仅 pdf/docx/txt/md）"),
    RESUME_STORAGE_NOT_CONFIGURED(3103, "对象存储未配置，无法上传简历"),
    RESUME_ENQUEUE_FAILED(3104, "分析任务投递失败，请稍后重试"),

    // ========== planner/decision 3200–3299（P1c-06/07，可解释决策面板） ==========
    DECISION_NOT_FOUND(3200, "该决策留痕不存在或无权限查看"),
    DECISION_ALREADY_REJECTED(3201, "这条决策你已经驳回过了"),

    // ========== plan 3300–3399（P2-06，计划与任务） ==========
    PLAN_NOT_FOUND(3300, "计划不存在"),
    PLAN_TASK_NOT_FOUND(3301, "计划任务不存在"),
    PLAN_SPLIT_UNAVAILABLE(3302, "任务拆分服务暂不可用");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
