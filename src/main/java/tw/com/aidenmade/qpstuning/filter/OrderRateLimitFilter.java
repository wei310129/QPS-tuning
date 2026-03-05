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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
public class OrderRateLimitFilter extends OncePerRequestFilter {

    private final RRateLimiter limiter;

    public OrderRateLimitFilter(RedissonClient redissonClient) {
        // 全域 key：所有 instance 共用
        this.limiter = redissonClient.getRateLimiter("rate:order:global");

        // 設定：每 1 秒最多 100 次
        // RateType.OVERALL = 全體共享配額（你要的 global limit）
        this.limiter.setRate(RateType.OVERALL, 150, 1, RateIntervalUnit.SECONDS);
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

        boolean allowed = limiter.tryAcquire(1);

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
}