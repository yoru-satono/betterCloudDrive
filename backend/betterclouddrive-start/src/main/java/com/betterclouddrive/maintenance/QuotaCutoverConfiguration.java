package com.betterclouddrive.maintenance;

import com.betterclouddrive.dal.repository.QuotaAccountingRepository;
import com.betterclouddrive.dal.repository.QuotaLegacyRepository;
import com.betterclouddrive.service.quota.QuotaCutoverService;
import com.betterclouddrive.start.config.FlywayConfig;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** No component scan: this CLI starts neither HTTP nor scheduled/Rabbit workers. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ConditionalOnProperty(name = "drive.quota.cutover", havingValue = "true")
@Import({FlywayConfig.class, QuotaAccountingRepository.class, QuotaLegacyRepository.class})
public class QuotaCutoverConfiguration {
    @Bean
    QuotaCutoverService quotaCutoverService(QuotaAccountingRepository accounting, QuotaLegacyRepository legacy,
                                          StringRedisTemplate redis, PlatformTransactionManager transactionManager) {
        return new QuotaCutoverService(accounting, legacy, redis, new TransactionTemplate(transactionManager));
    }
}
