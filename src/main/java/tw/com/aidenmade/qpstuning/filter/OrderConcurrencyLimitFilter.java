package tw.com.aidenmade.qpstuning.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

@Order(20)
@Slf4j
@Component
public class OrderConcurrencyLimitFilter extends OncePerRequestFilter {

    private final RedissonClient redissonClient;
    private volatile RSemaphore semaphore;

    public OrderConcurrencyLimitFilter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("/order".equals(request.getRequestURI()) && "GET".equalsIgnoreCase(request.getMethod()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        RSemaphore currentSemaphore = getSemaphore();
        boolean acquired = currentSemaphore.tryAcquire();

        if (!acquired) {
            log.warn("CONCURRENCY LIMITED uri={} method={}", request.getRequestURI(), request.getMethod());
            response.setStatus(503);
            response.setContentType("text/plain; charset=UTF-8");
            response.getWriter().write("Server is busy");
            return;
        }

        try {
            chain.doFilter(request, response);
        } finally {
            currentSemaphore.release();
        }
    }

    private RSemaphore getSemaphore() {
        RSemaphore current = semaphore;
        if (current == null) {
            synchronized (this) {
                current = semaphore;
                if (current == null) {
                    current = redissonClient.getSemaphore("conc:order:global");
                    current.trySetPermits(100, Duration.ofSeconds(1L));
                    semaphore = current;
                }
            }
        }
        return current;
    }
}
