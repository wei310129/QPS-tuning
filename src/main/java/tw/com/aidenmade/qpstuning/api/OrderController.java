package tw.com.aidenmade.qpstuning.api;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RestController
@RequestMapping("/order")
public class OrderController {

    private final AtomicInteger counter = new AtomicInteger();
    private final ObservationRegistry observationRegistry;

    public OrderController(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @GetMapping
    public String createOrder() throws InterruptedException {
        int seq = counter.incrementAndGet();
        log.info("Create order {}", seq);

        // 自訂 span：標出「business-logic 這段」在 trace 中佔多久
        // Grafana Tempo 會顯示這個子 span 在整個 HTTP span 內的位置
        Observation obs = Observation.start("order.processing", observationRegistry);
        try (Observation.Scope ignored = obs.openScope()) {
            Thread.sleep(500L);
        } catch (InterruptedException e) {
            obs.error(e);
            Thread.currentThread().interrupt();
        } finally {
            obs.stop();
        }

        return "Create order";
    }
}
