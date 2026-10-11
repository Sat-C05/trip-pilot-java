package com.trippilot;

import com.trippilot.config.TripPilotProperties;
import com.trippilot.research.HarnessProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
@EnableConfigurationProperties({TripPilotProperties.class, HarnessProperties.class, com.trippilot.model.ModelRuntimeProperties.class})
public class TripPilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(TripPilotApplication.class, args);
    }
}
