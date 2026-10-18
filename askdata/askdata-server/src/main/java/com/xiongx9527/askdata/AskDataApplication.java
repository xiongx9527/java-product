package com.xiongx9527.askdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 问数（askdata）单体后端启动类。
 * 端口 8000，元数据库 H2（./data/askdata），前端 dist 由同一 jar 托管。
 */
@SpringBootApplication
public class AskDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(AskDataApplication.class, args);
    }
}
