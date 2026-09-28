package io.annona.modules.retrieval.dto;

/**
 * 空命中的可解释原因（P1a-07）。
 *
 * <p>为什么要有这个枚举：AGENTS.md §1 的主张是"可解释优先于准确"，而"没找到"也是系统
 * 自动做的一个决定，必须能回答凭什么。三种空命中原因的处置完全不同——
 * 一篇就绪文档都没有、模型身份不匹配、真的没有相关内容——后端本来就分得清，
 * 不说出来就等于让用户自己猜。
 *
 * <p>{@link #MATCHED} 让前端不需要判空（有命中时 reason 恒为该值），
 * 避免出现"null 到底算没查还是查了没中"的第三种含义。
 */
public enum RetrievalMissReason {

    /** 该用户名下没有任何 READY 文档：先去知识库上传资料。 */
    NO_READY_DOC,

    /**
     * 有 READY 文档，但它们的 {@code embedding_model} 与当前配置的模型不一致：
     * 换过模型或评测语料由 fake 向量生成。提示用户对相关文档走"重建"。
     */
    MODEL_MISMATCH,

    /** 有可比对的文档，双通道都没有命中：资料里确实没写这件事。 */
    NO_MATCH,

    /** 有命中，本字段无诊断含义。 */
    MATCHED
}
