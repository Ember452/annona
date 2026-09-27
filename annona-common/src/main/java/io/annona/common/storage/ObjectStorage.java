package io.annona.common.storage;

/**
 * 对象存储端口（knowledge-ingestion-adr §决策 8）：上传 / 回读 / 删除 / 存在性。
 *
 * <p>key 由调用方生成（{@code knowledge/{yyyy/MM/dd}/{uuid8}_{安全文件名}}，借 🅖
 * 布局；幂等性靠 key 设计保证，存储层不做去重）。IO 失败以运行时异常透传；
 * 上传后 DB 写失败的孤儿对象补偿在业务层（KnowledgeUploadService，借 🅖 compensateOrphanObject）。
 * 事务铁律：本端口的调用一律在数据库事务之外。
 */
public interface ObjectStorage {

    /**
     * @param key        对象 key（调用方生成，含扩展名）
     * @param content    文件字节
     * @param contentType MIME 类型（嗅探后的值，供后续回读方识别）
     */
    void put(String key, byte[] content, String contentType);

    /** @return 对象字节；不存在时抛运行时异常（调用方按业务错误码转换）。 */
    byte[] get(String key);

    /** 幂等删除：对象不存在不报错（删除级联与孤儿补偿都依赖该语义）。 */
    void delete(String key);

    boolean exists(String key);
}
