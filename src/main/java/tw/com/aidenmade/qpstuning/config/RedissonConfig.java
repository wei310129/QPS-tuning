package tw.com.aidenmade.qpstuning.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.redisson.config.SentinelServersConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${app.redis.mode:single}") String mode,
            @Value("${app.redis.address:redis://127.0.0.1:6379}") String address,
            @Value("${app.redis.sentinel.master-name:mymaster}") String masterName,
            @Value("${app.redis.sentinel.addresses:redis://127.0.0.1:26379}") String sentinelAddresses,
            @Value("${app.redis.password:}") String password,
            @Value("${app.redis.sentinel.password:}") String sentinelPassword
    ) {
        Config config = new Config();

        if ("sentinel".equalsIgnoreCase(mode)) {
            String[] addresses = Arrays.stream(sentinelAddresses.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toArray(String[]::new);

            SentinelServersConfig sentinelConfig = config.useSentinelServers()
                    .setMasterName(masterName)
                    .addSentinelAddress(addresses);

            if (!password.isBlank()) {
                sentinelConfig.setPassword(password);
            }

            if (!sentinelPassword.isBlank()) {
                sentinelConfig.setSentinelPassword(sentinelPassword);
            }
        } else {
            SingleServerConfig singleServerConfig = config.useSingleServer().setAddress(address);

            if (!password.isBlank()) {
                singleServerConfig.setPassword(password);
            }
        }

        return Redisson.create(config);
    }
}
