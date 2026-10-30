package dev.hermit.testdoubles;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BoomController {

    @GetMapping("/api/admin/boom")
    public void boom() {
        throw new IllegalStateException("boom-internal-sentinel");
    }

    /** Required query param used to prove missing and mistyped parameters stay 400. */
    @GetMapping("/api/admin/probe-required")
    public Map<String, Integer> probeRequired(@RequestParam("widget") int widget) {
        return Map.of("widget", widget);
    }
}
