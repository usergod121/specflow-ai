package com.specflow.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("只放行本机页面")
class LocalOnlyTest {

    @Test
    @DisplayName("本机的 Host 放行，端口怎么写都行")
    void allowsLoopbackHost() {
        assertThat(LocalOnly.isLoopbackHost("127.0.0.1:8770")).isTrue();
        assertThat(LocalOnly.isLoopbackHost("127.0.0.1")).isTrue();
        assertThat(LocalOnly.isLoopbackHost("localhost:8770")).isTrue();
        assertThat(LocalOnly.isLoopbackHost("LOCALHOST")).isTrue();
        assertThat(LocalOnly.isLoopbackHost("[::1]:8770")).isTrue();
        assertThat(LocalOnly.isLoopbackHost("127.0.0.1:8770 ")).isTrue();
    }

    @Test
    @DisplayName("别的 Host 一律拒绝——尤其是「看着像本机」的那种")
    void rejectsForeignHost() {
        assertThat(LocalOnly.isLoopbackHost("evil.com")).isFalse();
        // DNS 重绑定就长这样：域名以 127.0.0.1 开头，但它不是本机
        assertThat(LocalOnly.isLoopbackHost("127.0.0.1.evil.com")).isFalse();
        assertThat(LocalOnly.isLoopbackHost("evil.com:8770")).isFalse();
        assertThat(LocalOnly.isLoopbackHost("0.0.0.0:8770")).isFalse();
        assertThat(LocalOnly.isLoopbackHost("192.168.1.5:8770")).isFalse();
        assertThat(LocalOnly.isLoopbackHost("")).isFalse();
        assertThat(LocalOnly.isLoopbackHost(null)).isFalse();
    }

    @Test
    @DisplayName("没有 Origin 放行——同源导航本来就不带它")
    void allowsMissingOrigin() {
        assertThat(LocalOnly.isLoopbackOrigin(null)).isTrue();
        assertThat(LocalOnly.isLoopbackOrigin("")).isTrue();
        assertThat(LocalOnly.isLoopbackOrigin("   ")).isTrue();
    }

    @Test
    @DisplayName("本机页面的 Origin 放行")
    void allowsLoopbackOrigin() {
        assertThat(LocalOnly.isLoopbackOrigin("http://127.0.0.1:8770")).isTrue();
        assertThat(LocalOnly.isLoopbackOrigin("http://localhost:8770")).isTrue();
        assertThat(LocalOnly.isLoopbackOrigin("http://127.0.0.1")).isTrue();
    }

    @Test
    @DisplayName("别的页面的 Origin 拒绝——这就是「你浏览别的网页时它偷改你代码」那条路")
    void rejectsForeignOrigin() {
        assertThat(LocalOnly.isLoopbackOrigin("https://evil.com")).isFalse();
        assertThat(LocalOnly.isLoopbackOrigin("http://127.0.0.1.evil.com")).isFalse();
        // 沙箱 iframe 和 file:// 页面会发字面量 null，那也不是本机页面
        assertThat(LocalOnly.isLoopbackOrigin("null")).isFalse();
        assertThat(LocalOnly.isLoopbackOrigin("这不是个 URI")).isFalse();
    }

    @Test
    @DisplayName("IPv6 的方括号形式不会把端口当成分隔符切错")
    void handlesIpv6Brackets() {
        assertThat(LocalOnly.isLoopbackHost("[::1]:8770")).isTrue();
        assertThat(LocalOnly.isLoopbackHost("[2001:db8::1]:8770")).isFalse();
    }
}
