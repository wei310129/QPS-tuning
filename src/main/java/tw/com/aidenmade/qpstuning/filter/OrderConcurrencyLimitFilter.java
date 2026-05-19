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

    private final RSemaphore semaphore;

    public OrderConcurrencyLimitFilter(RedissonClient redissonClient) {
        this.semaphore = redissonClient.getSemaphore("conc:order:global");
        // 只在第一次初始化成功（已存在就不改）
        this.semaphore.trySetPermits(100, Duration.ofSeconds(1L));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("/order".equals(request.getRequestURI()) && "GET".equalsIgnoreCase(request.getMethod()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        boolean acquired = semaphore.tryAcquire();

        if (!acquired) {
            log.warn("CONCURRENCY LIMITED uri={} method={}", request.getRequestURI(), request.getMethod());
            response.setStatus(503); // 或 429，看你語意
            response.setContentType("text/plain; charset=UTF-8");
            response.getWriter().write("Server is busy");
            return;
        }

        try {
            chain.doFilter(request, response);
        } finally {
            semaphore.release();
        }
    }
}
