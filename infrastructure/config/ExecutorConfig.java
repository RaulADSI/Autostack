package com.reiter.autostack.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class ExecutorConfig {

    @Bean
    public ScheduledExecutorService scheduledExecutorService() {
        // Levanta un pool de 2 hilos dedicados a tareas programadas en el fondo
        return Executors.newScheduledThreadPool(2);
    }
}