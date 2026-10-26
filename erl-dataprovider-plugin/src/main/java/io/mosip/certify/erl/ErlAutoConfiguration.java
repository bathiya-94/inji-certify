package io.mosip.certify.erl;

import io.mosip.certify.api.spi.DataProviderRegistration;
import io.mosip.certify.erl.repository.ErlLicenceRepository;
import io.mosip.certify.dmt.DmtPersistenceAutoConfiguration;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration(after = DmtPersistenceAutoConfiguration.class)
@ConditionalOnProperty(name = "dmt.erl.enabled", havingValue = "true")
@EnableConfigurationProperties(ErlProperties.class)
@EnableTransactionManagement(proxyTargetClass = true)
@EnableJpaRepositories(basePackageClasses = ErlLicenceRepository.class,
        entityManagerFactoryRef = "dmtEntityManagerFactory", transactionManagerRef = "dmtTransactionManager")
public class ErlAutoConfiguration {
    @Bean
    public ErlTemplateHelper erlTemplateHelper() { return new ErlTemplateHelper(Clock.systemUTC()); }
    @Bean(name = "erl")
    public ErlDataProviderPlugin erlDataProviderPlugin(ErlLicenceRepository repository, ErlProperties properties,
                                                      ErlTemplateHelper helper) {
        return new ErlDataProviderPlugin(repository, properties, helper);
    }
    @Bean
    public DataProviderRegistration erlRegistration(ErlDataProviderPlugin plugin, ErlTemplateHelper helper) {
        return new DataProviderRegistration("erl", plugin, ErlLookupRequest::parse, helper);
    }
}
