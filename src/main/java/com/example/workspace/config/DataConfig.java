package com.example.workspace.config;

import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class DataConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean CommandLineRunner seedResources(ResourceRepository resources) {
        return args -> {
            if (resources.count() > 0) return;
            resources.save(new BookableResource("ALPHA", "Conference Alpha", ResourceType.ROOM,
                    "HQ", 2, 10, true, false));
            resources.save(new BookableResource("BOARD", "Boardroom", ResourceType.ROOM,
                    "HQ", 3, 16, true, true));
            resources.save(new BookableResource("DESK-01", "Focus desk 01", ResourceType.DESK,
                    "HQ", 1, 1, true, false));
            resources.save(new BookableResource("PROJECTOR-01", "Portable projector", ResourceType.EQUIPMENT,
                    "HQ", 0, 1, true, false));
            resources.save(new BookableResource("BETA", "Conference Beta (maintenance)", ResourceType.ROOM,
                    "HQ", 1, 6, false, false));
        };
    }
}
