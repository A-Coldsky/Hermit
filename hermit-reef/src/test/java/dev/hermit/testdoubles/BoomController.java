package dev.hermit.testdoubles;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BoomController {

    @GetMapping("/api/admin/boom")
    public void boom() {
        throw new IllegalStateException("boom-internal-sentinel");
    }
}
