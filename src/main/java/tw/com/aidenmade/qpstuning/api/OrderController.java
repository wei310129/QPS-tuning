package tw.com.aidenmade.qpstuning.api;

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

    @GetMapping
    public String createOrder() throws InterruptedException {
        log.info("Create order {}", counter.incrementAndGet());
        Thread.sleep(1000L);
        return "Create order";
    }
}
