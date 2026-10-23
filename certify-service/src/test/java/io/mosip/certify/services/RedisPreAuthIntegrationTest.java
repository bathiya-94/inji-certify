package io.mosip.certify.services;
import io.mosip.certify.core.dto.PreAuthCodeData;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.Assume;
import org.springframework.data.redis.cache.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import static org.junit.Assert.*;

public class RedisPreAuthIntegrationTest {
    @Test public void twoInstancesConsumeSerializedPrefixedEntryExactlyOnce() throws Exception {
        Assume.assumeTrue("Docker is required", DockerClientFactory.instance().isDockerAvailable());
        try (GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
            redis.start();
            LettuceConnectionFactory connection = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
            connection.afterPropertiesSet(); connection.start();
            try {
                RedisCacheManager manager = RedisCacheManager.builder(connection).cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
                    .prefixCacheNameWith("erl-test:").entryTtl(Duration.ofSeconds(30))).build();
                manager.afterPropertiesSet();
                VCICacheService first = instance(manager, connection), second = instance(manager, connection);
                for (int run = 0; run < 10; run++) {
                    String code = UUID.randomUUID().toString();
                    first.setPreAuthCodeData(code, PreAuthCodeData.builder().credentialConfigurationId("ERL")
                        .claims(Map.of("vehicle_number", "TEST", "issued_date", "2026-10-16T14:30:00")).build());
                    assertNotNull(second.getPreAuthCodeData(code));
                    CyclicBarrier barrier = new CyclicBarrier(2);
                    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                        Future<Boolean> a = executor.submit(() -> { barrier.await(); return first.claimPreAuthCode(code); });
                        Future<Boolean> b = executor.submit(() -> { barrier.await(); return second.claimPreAuthCode(code); });
                        assertEquals(1, (a.get(10, TimeUnit.SECONDS) ? 1 : 0) + (b.get(10, TimeUnit.SECONDS) ? 1 : 0));
                    }
                    assertNull(second.getPreAuthCodeData(code));
                    assertFalse(first.claimPreAuthCode(code));
                }
            } finally { connection.destroy(); }
        }
    }
    private VCICacheService instance(RedisCacheManager manager, LettuceConnectionFactory connection) {
        VCICacheService service = new VCICacheService();
        ReflectionTestUtils.setField(service, "cacheManager", manager);
        ReflectionTestUtils.setField(service, "redisConnectionFactory", connection);
        ReflectionTestUtils.setField(service, "cacheType", "redis");
        return service;
    }
}
