package dev.hermit.crab.http;

import java.time.Duration;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundHttpConfigTest {

    @Test
    void connectionConfigSetsConnectTimeoutAndTwoSecondValidation() {
        ConnectionConfig engine = OutboundHttp.connectionConfig(Duration.ofMillis(400), Duration.ofSeconds(15));
        assertThat(engine.getConnectTimeout().toMilliseconds()).isEqualTo(400);
        assertThat(engine.getSocketTimeout().toMilliseconds()).isEqualTo(15_000);
        assertThat(engine.getValidateAfterInactivity().toMilliseconds()).isEqualTo(2_000);

        ConnectionConfig other = OutboundHttp.connectionConfig(OutboundHttp.OTHER_CONNECT, null);
        assertThat(other.getConnectTimeout().toMilliseconds()).isEqualTo(10_000);
        assertThat(other.getSocketTimeout()).isNull();
        assertThat(other.getValidateAfterInactivity().toMilliseconds()).isEqualTo(2_000);
    }
}
