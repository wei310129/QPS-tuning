package tw.com.aidenmade.qpstuning.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Order(10)
@Slf4j
@Component
public class OrderRateLimitFilter extends OncePerRequestFilter {

    private final RedissonClient redissonClient;
    private volatile RRateLimiter limiter;

    public OrderRateLimitFilter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("/order".equals(request.getRequestURI()) && "GET".equalsIgnoreCase(request.getMethod()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        boolean allowed = getLimiter().tryAcquire(1);

        if (!allowed) {
            log.warn("RATE LIMITED uri={} method={}", request.getRequestURI(), request.getMethod());
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setContentType("text/plain; charset=UTF-8");
            response.getWriter().write("Too Many Requests");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private RRateLimiter getLimiter() {
        RRateLimiter current = limiter;
        if (current == null) {
            synchronized (this) {
                current = limiter;
                if (current == null) {
                    current = redissonClient.getRateLimiter("rate:order:global");
                    current.setRate(RateType.OVERALL, 100, 1, RateIntervalUnit.SECONDS);
                    limiter = current;
                }
            }
        }
        return current;
    }
}
