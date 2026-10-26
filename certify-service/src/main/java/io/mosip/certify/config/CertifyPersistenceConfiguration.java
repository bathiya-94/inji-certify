package io.mosip.certify.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.orm.jpa.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit primary persistence unit when a secondary pool disables Boot's defaults. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "dmt.datasource.enabled", havingValue = "true")
@EnableConfigurationProperties({JpaProperties.class, HibernateProperties.class})
public class CertifyPersistenceConfiguration {
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties certifyDataSourceProperties() { return new DataSourceProperties(); }
    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource certifyDataSource(@Qualifier("certifyDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
    @Bean(name = "entityManagerFactory")
    @Primary
    public LocalContainerEntityManagerFactoryBean certifyEntityManagerFactory(
            @Qualifier("dataSource") DataSource dataSource, JpaProperties properties, HibernateProperties hibernate) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPersistenceUnitName("certify");
        factory.setPackagesToScan("io.mosip.certify.entity", "io.mosip.kernel.keymanagerservice.entity");
        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setDatabasePlatform(properties.getDatabasePlatform());
        adapter.setShowSql(properties.isShowSql());
        factory.setJpaVendorAdapter(adapter);
        factory.setJpaPropertyMap(hibernate.determineHibernateProperties(properties.getProperties(), new HibernateSettings()));
        return factory;
    }
    @Bean(name = "transactionManager")
    @Primary
    public PlatformTransactionManager certifyTransactionManager(@Qualifier("entityManagerFactory") EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }
}
