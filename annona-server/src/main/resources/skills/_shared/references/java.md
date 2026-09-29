# Java 参考要点（公共参考池样例）

- 集合：ArrayList 扩容 1.5 倍；HashMap 桶数组 2 的幂、链表长度 ≥8 且表长 ≥64 才树化
- 并发：volatile 保证可见性与禁止指令重排，不保证原子性；i++ 需要 AtomicInteger 或锁
- JVM：对象优先在 TLAB 内分配；Full GC 的常见触发为晋升失败/担保失败/元空间不足
- 异常：Error 与 Exception 分层；受检异常强制声明，运行时异常默认回滚事务
