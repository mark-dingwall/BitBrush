package au.com.dingwall.mark.bitbrush.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ClientIpResolverTest {

    @Test
    void usesTheSingleFlyClientIpLiteralInProduction() {
        MockHttpServletRequest request = request("10.0.0.1");
        request.addHeader("Fly-Client-IP", "2001:db8::1");

        InetAddress resolved = resolver("prod").resolve(request);

        assertThat(resolved.getHostAddress()).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void ignoresFlyClientIpOutsideProduction() {
        MockHttpServletRequest request = request("192.0.2.9");
        request.addHeader("Fly-Client-IP", "2001:db8::1");

        InetAddress resolved = resolver("test").resolve(request);

        assertThat(resolved.getHostAddress()).isEqualTo("192.0.2.9");
    }

    @Test
    void rejectsMissingFlyClientIpInProduction() {
        assertThatIllegalArgumentException().isThrownBy(() -> resolver("prod").resolve(request("10.0.0.1")));
    }

    @Test
    void rejectsMultipleFlyClientIpHeadersInProduction() {
        MockHttpServletRequest request = request("10.0.0.1");
        request.addHeader("Fly-Client-IP", "198.51.100.7");
        request.addHeader("Fly-Client-IP", "203.0.113.8");

        assertThatIllegalArgumentException().isThrownBy(() -> resolver("prod").resolve(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "198.51.100.7, 203.0.113.8",
        "fe80::1%eth0",
        "localhost",
        "not-an-ip",
        "",
        " 203.0.113.7 "
    })
    void rejectsUntrustedOrNonLiteralFlyClientIpValuesInProduction(String value) {
        MockHttpServletRequest request = request("10.0.0.1");
        request.addHeader("Fly-Client-IP", value);

        assertThatIllegalArgumentException().isThrownBy(() -> resolver("prod").resolve(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "127.0.0.1, 198.51.100.7", "fe80::1%lo", "999.999.999.999"})
    void rejectsNonNumericRemoteAddressesOutsideProduction(String remoteAddress) {
        assertThatIllegalArgumentException().isThrownBy(() -> resolver("test").resolve(request(remoteAddress)));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @ValueSource(strings = {"", "1..2.3", "1.2.3.x", "1.2.3.99999999999999999999", "2001:db8::g", "2001:::1"})
    void malformedNumericAddressesCannotBecomeThrottleKeys(String address) {
        // Catches accepting malformed/overflowing literals or propagating unsafe parser diagnostics.
        assertThatIllegalArgumentException().isThrownBy(() -> resolver("test").resolve(request(address)))
            .withMessage("Client IP must be a numeric address literal");
    }

    private ClientIpResolver resolver(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new ClientIpResolver(environment);
    }

    private MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
