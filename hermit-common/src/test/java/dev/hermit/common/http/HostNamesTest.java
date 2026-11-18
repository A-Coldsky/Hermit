package dev.hermit.common.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

class HostNamesTest {

    @Test
    void stripsEveryTrailingDotAndKeepsTheExplicitPort() {
        URI ip = HostNames.withoutTrailingDot(URI.create("http://127.0.0.1.:8080/a%20b?q=a%2Bb#f"));
        assertThat(ip.getHost()).isEqualTo("127.0.0.1");
        assertThat(ip.getPort()).isEqualTo(8080);
        assertThat(ip.getRawPath()).isEqualTo("/a%20b");
        assertThat(ip.getRawQuery()).isEqualTo("q=a%2Bb");
        assertThat(ip.getRawFragment()).isEqualTo("f");

        URI name = HostNames.withoutTrailingDot(URI.create("https://hr.corp./mcp"));
        assertThat(name.getHost()).isEqualTo("hr.corp");
        assertThat(name.getPort()).isEqualTo(-1);
        assertThat(name.getRawPath()).isEqualTo("/mcp");

        URI user = HostNames.withoutTrailingDot(URI.create("http://user:secret@hr.corp.:9/x"));
        assertThat(user.getHost()).isEqualTo("hr.corp");
        assertThat(user.getPort()).isEqualTo(9);
        assertThat(user.getRawUserInfo()).isEqualTo("user:secret");

        assertThat(HostNames.withoutTrailingDot(URI.create("http://[::1]/")).getHost()).isEqualTo("[::1]");
        assertThat(HostNames.stripTrailingDots("LocalHost...")).isEqualTo("LocalHost");
        assertThat(HostNames.withoutTrailingDot(URI.create("http://my_host.corp/a"))).isEqualTo(URI.create("http://my_host.corp/a"));
    }

    @Test
    void inspectReportsUnderscoreBeforePunycodeAndWarnsForSingleLabelHttps() {
        HostNames.Finding underscore = HostNames.inspect("http://my_host.corp/a");
        assertThat(underscore.problem()).isEqualTo(HostNames.Problem.UNDERSCORE);
        assertThat(underscore.host()).isEqualTo("my_host.corp");
        assertThat(underscore.message()).isEqualTo("主机名 my_host.corp 含下划线，Java 客户端无法连接；请改用 IP 或 DNS 别名");

        HostNames.Finding mixed = HostNames.inspect("https://my_host.例子/");
        assertThat(mixed.problem()).isEqualTo(HostNames.Problem.UNDERSCORE);

        HostNames.Finding idn = HostNames.inspect("https://例子.测试/");
        assertThat(idn.problem()).isEqualTo(HostNames.Problem.NON_ASCII);
        assertThat(idn.message()).isEqualTo(
                "主机名 例子.测试 含非 ASCII 字符，Java 客户端无法连接；请改成 xn--fsqu00a.xn--0zwm56d");

        HostNames.Finding encoded = HostNames.inspect("http://%E4%BE%8B%E5%AD%90.%E6%B5%8B%E8%AF%95/");
        assertThat(encoded.problem()).isEqualTo(HostNames.Problem.NON_ASCII);
        assertThat(encoded.message()).contains("xn--fsqu00a.xn--0zwm56d");

        HostNames.Finding single = HostNames.inspect("https://hr./");
        assertThat(single.problem()).isEqualTo(HostNames.Problem.SINGLE_LABEL_HTTPS);
        assertThat(single.host()).isEqualTo("hr");
        assertThat(single.message()).isEqualTo("单标签主机名 hr 不会发送 SNI，虚拟主机可能返回别的证书");

        assertThat(HostNames.inspect("http://hr/")).isNull();
        assertThat(HostNames.inspect("https://hr.corp/")).isNull();
        assertThat(HostNames.inspect("https://{{config.host}}/a")).isNull();
        assertThat(HostNames.inspect("/login")).isNull();
        assertThat(HostNames.inspect("https://hr /")).isNull();
        assertThat(HostNames.inspect("https://[::1]/")).isNull();
        assertThat(HostNames.inspect("https://127.0.0.1/")).isNull();
    }
}
