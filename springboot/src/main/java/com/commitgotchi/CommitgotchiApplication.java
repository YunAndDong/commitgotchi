package com.commitgotchi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CommitgotchiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommitgotchiApplication.class, args);
    }
}
