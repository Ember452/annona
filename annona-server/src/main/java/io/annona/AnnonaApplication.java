package io.annona;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 年轮 annona 启动类。{@code @EnableScheduling}：knowledge 入库链的恢复调度
 * （PENDING/在途超时回收，P1a-05）是首个定时任务。
 */
@EnableScheduling
@SpringBootApplication
public class AnnonaApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnnonaApplication.class, args);
    }

}
