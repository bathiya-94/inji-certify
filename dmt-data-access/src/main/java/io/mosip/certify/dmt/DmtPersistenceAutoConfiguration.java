package io.mosip.certify.dmt;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfiguration(before = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@ConditionalOnProperty(name = "dmt.datasource.enabled", havingValue = "true")
public class DmtPersistenceAutoConfiguration {
    @Bean
    @ConfigurationProperties("dmt.datasource")
    public DataSourceProperties dmtDataSourceProperties() { return new DataSourceProperties(); }

    @Bean
    @ConfigurationProperties("dmt.datasource.hikari")
    public HikariDataSource dmtDataSource(@Qualifier("dmtDataSourceProperties") DataSourceProperties properties) {
        HikariDataSource source = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        source.setPoolName("dmt");
        source.setReadOnly(true);
        source.setMaximumPoolSize(5);
        source.setConnectionTimeout(5000);
        source.setValidationTimeout(2000);
        return source;
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean dmtEntityManagerFactory(
            @Qualifier("dmtDataSource") DataSource dataSource,
            @Value("${dmt.jpa.entity-packages:io.mosip.certify.erl.entity}") String[] packages) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPersistenceUnitName("dmt");
        factory.setPackagesToScan(packages);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.dialect", "org.hibernate.dialect.MySQLDialect",
                "hibernate.hbm2ddl.auto", "none", "jakarta.persistence.query.timeout", 5000));
        return factory;
    }

    @Bean
    public PlatformTransactionManager dmtTransactionManager(
            @Qualifier("dmtEntityManagerFactory") EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }
}
