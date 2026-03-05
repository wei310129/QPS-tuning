package tw.com.aidenmade.qpstuning.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/tuning")
public class TuningController {

    @GetMapping("try-get")
    public ResponseEntity<String> getTuning() throws InterruptedException {
        log.info("Tuning");
        Thread.sleep(1000L);
        return ResponseEntity.ok("Tuning");
    }
}
