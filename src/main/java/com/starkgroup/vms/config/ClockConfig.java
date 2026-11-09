package com.starkgroup.vms.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single source of "now" for the whole application. Business time zone is Asia/Dhaka
 * (guideline 3.3, BR-08). Tests replace this with {@code Clock.fixed(...)}.
 */
@Configuration
public class ClockConfig {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Dhaka");

    @Bean
    public Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
