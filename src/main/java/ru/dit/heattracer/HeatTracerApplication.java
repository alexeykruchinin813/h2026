package ru.dit.heattracer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class HeatTracerApplication {

    public static void main(String[] args) {
        SpringApplication.run(HeatTracerApplication.class, args);
    }
}
