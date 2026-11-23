package dev.hermit.common.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import org.junit.jupiter.api.Test;

class SiteAddressTest {

    @Test
    void sameSiteIgnoresCasePathDefaultPortAndTrailingDot() {
        assertThat(SiteAddress.of("https://OA.Corp.com/app/index?x=1")).isEqualTo("https://oa.corp.com:443");
        assertThat(SiteAddress.of("HTTPS://oa.corp.com:443")).isEqualTo("https://oa.corp.com:443");
        assertThat(SiteAddress.of("https://oa.corp.com./")).isEqualTo("https://oa.corp.com:443");
        assertThat(SiteAddress.of("http://oa.corp.com")).isEqualTo("http://oa.corp.com:80");
    }

    @Test
    void schemeHostAndPortEachMakeADifferentSite() {
        String site = SiteAddress.of("https://oa.corp.com");
        assertThat(SiteAddress.of("http://oa.corp.com")).isNotEqualTo(site);
        assertThat(SiteAddress.of("https://oa.corp.com:8443")).isNotEqualTo(site);
        assertThat(SiteAddress.of("https://erp.corp.com")).isNotEqualTo(site);
    }

    @Test
    void unusableAddressesHaveNoSite() {
        assertThat(SiteAddress.of((String) null)).isNull();
        assertThat(SiteAddress.of(" ")).isNull();
        assertThat(SiteAddress.of("/relative")).isNull();
        assertThat(SiteAddress.of("ftp://oa.corp.com")).isNull();
        assertThat(SiteAddress.of("http://exa mple")).isNull();
    }

    @Test
    void configuredBaseUrlWinsOverThePackage() {
        ConchDoc doc = ConchParser.parse(yaml("https://oa.corp.com", "https://oa-test.corp.com"));
        assertThat(SiteAddress.effective(doc, null)).isEqualTo("https://oa.corp.com:443");
        assertThat(SiteAddress.effective(doc, " ")).isEqualTo("https://oa.corp.com:443");
        assertThat(SiteAddress.effective(doc, "http://10.0.0.8:8080/oa")).isEqualTo("http://10.0.0.8:8080");
        // 包里能看到的只有顶层地址和配置默认值。
        assertThat(SiteAddress.declared(doc))
                .containsExactly("https://oa.corp.com:443", "https://oa-test.corp.com:443");
    }

    private static String yaml(String baseUrl, String configDefault) {
        return """
                conch: site_pkg
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: %s
                title: 站点
                description: 站点地址测试。
                config:
                  base_url: { type: url, label: 地址, required: false, default: "%s" }
                actions:
                  show:
                    title: 看
                    description: 返回常量。
                    idempotent: true
                    mcp: { expose: true, read_only: true }
                    steps:
                      - set: { message: seen }
                    output:
                      message: { type: string, description: 文本, value: seen }
                    examples:
                      - input: {}
                        output: { message: seen }
                    notes: 无
                    related: []
                """.formatted(baseUrl, configDefault);
    }
}
