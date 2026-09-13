package au.com.dingwall.mark.bitbrush.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityRequestSizeFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {"/api/users", "/api/users/reconnect", "/api/users/recover"})
    void replaysAnUnknownLengthBodyAtTheLimitForIdentityPosts(String path) throws Exception {
        byte[] body = new byte[4_096];
        UnknownLengthRequest request = new UnknownLengthRequest("POST", path, body);
        CapturingFilterChain chain = new CapturingFilterChain();

        filter().doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.body).containsExactly(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users", "/api/users/reconnect", "/api/users/recover"})
    void replaysReadersForIdentityPosts(String path) throws Exception {
        byte[] body = "{\"username\":\"pixel artist\"}".getBytes(StandardCharsets.UTF_8);
        UnknownLengthRequest request = new UnknownLengthRequest("POST", path, body);
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ReaderCapturingFilterChain chain = new ReaderCapturingFilterChain();

        filter().doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.body).isEqualTo("{\"username\":\"pixel artist\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users", "/api/users/reconnect", "/api/users/recover"})
    void rejectsAnUnknownLengthBodyAboveTheLimitForIdentityPosts(String path) throws Exception {
        UnknownLengthRequest request = new UnknownLengthRequest("POST", path, new byte[4_097]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingFilterChain chain = new CapturingFilterChain();

        filter().doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"title\":\"Content Too Large\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users", "/api/users/reconnect", "/api/users/recover"})
    void rejectsDeclaredBodiesAboveTheLimitForIdentityPosts(String path) throws Exception {
        MockHttpServletRequest request = request("POST", path, new byte[4_097]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingFilterChain chain = new CapturingFilterChain();

        filter().doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"title\":\"Content Too Large\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/users;v=1", "/api/%75sers",
        "/api/users/reconnect;v=1", "/api/users/%72econnect",
        "/api/users/recover;v=1", "/api/users/%72ecover"
    })
    void rejectsOversizedBodiesForMvcEquivalentIdentityPaths(String path) throws Exception {
        MockHttpServletRequest request = request("POST", path, new byte[4_097]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingFilterChain chain = new CapturingFilterChain();

        filter().doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/bitbrush/api/users;v=1", "/bitbrush/api/%75sers",
        "/bitbrush/api/users/reconnect;v=1", "/bitbrush/api/users/%72econnect",
        "/bitbrush/api/users/recover;v=1", "/bitbrush/api/users/%72ecover"
    })
    void rejectsOversizedBodiesForContextRelativeMvcEquivalentIdentityPaths(String path) throws Exception {
        MockHttpServletRequest request = request("POST", path, new byte[4_097]);
        request.setContextPath("/bitbrush");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingFilterChain chain = new CapturingFilterChain();

        filter().doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/users", "POST /api/pixels"})
    void passesNonIdentityRequestsThroughUntouched(String requestLine) throws Exception {
        String[] parts = requestLine.split(" ");
        byte[] body = new byte[4_097];
        MockHttpServletRequest request = request(parts[0], parts[1], body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingFilterChain chain = new CapturingFilterChain();

        filter().doFilter(request, response, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.body).containsExactly(body);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private IdentityRequestSizeFilter filter() {
        return new IdentityRequestSizeFilter();
    }

    private MockHttpServletRequest request(String method, String path, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContent(body);
        return request;
    }

    private static class UnknownLengthRequest extends MockHttpServletRequest {

        UnknownLengthRequest(String method, String path, byte[] body) {
            super(method, path);
            setContent(body);
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }
    }

    private static class CapturingFilterChain implements FilterChain {

        private boolean invoked;
        private byte[] body;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
            invoked = true;
            body = request.getInputStream().readAllBytes();
        }
    }

    private static class ReaderCapturingFilterChain implements FilterChain {

        private String body;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws IOException {
            body = request.getReader().readLine();
        }
    }
}
