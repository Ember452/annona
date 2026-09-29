---
key: java-concurrency
name: Java 并发编程
description: 线程安全、锁机制、并发容器与内存模型的专项考察口径
---

# 考察范围

- 内存模型：happens-before、可见性/有序性/原子性、final 语义
- 锁：synchronized 锁升级、AQS 队列、ReentrantLock 公平与非公平、读写锁
- 工具：CountDownLatch/CyclicBarrier/Semaphore 的适用分界、CompletableFuture 编排
- 容器：ConcurrentHashMap、CopyOnWriteArrayList、阻塞队列选型

# 难度分布与出题口径

- 难度 1–2 考语义（volatile 能保证什么、不能保证什么），要求给出反例
- 难度 3 考机制（AQS 入队与唤醒路径、锁升级触发条件）
- 难度 4–5 考诊断（死锁/活锁排查、线程池打满的事故链）

# 评分侧重

- 能用 happens-before 解释结论的加分；只会说"加锁就行"的不给高分
- 事故类题目要求有定位步骤，顺序混乱扣分

# 追问方向

- 从工具用法追到实现机制，再追到内存模型依据；事故类追问定位步骤的完整性
