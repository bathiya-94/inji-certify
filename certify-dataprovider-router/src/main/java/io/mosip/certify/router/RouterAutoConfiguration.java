package io.mosip.certify.router;
import java.util.List;
import io.mosip.certify.api.spi.DataProviderRegistration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
@AutoConfiguration
@ConditionalOnProperty(name = "mosip.certify.integration.data-provider-plugin", havingValue = "RouterDataProviderPlugin")
@EnableConfigurationProperties(RoutingProperties.class)
public class RouterAutoConfiguration {
    @Bean
    @Primary
    public RouterDataProviderPlugin routerDataProviderPlugin(RoutingProperties properties,
                                                            List<DataProviderRegistration> registrations) {
        return new RouterDataProviderPlugin(properties, registrations);
    }
}
