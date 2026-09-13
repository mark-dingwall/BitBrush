package au.com.dingwall.mark.bitbrush.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdentityRequestSizeFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_BYTES = 4_096;
    private static final int BUFFER_BYTES = MAX_BODY_BYTES + 1;
    private static final List<PathPattern> IDENTITY_PATHS = List.of(
        PathPatternParser.defaultInstance.parse("/api/users"),
        PathPatternParser.defaultInstance.parse("/api/users/reconnect"),
        PathPatternParser.defaultInstance.parse("/api/users/recover")
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isIdentityPost(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            writePayloadTooLarge(response);
            return;
        }

        byte[] body = readBoundedBody(request);
        if (body.length > MAX_BODY_BYTES) {
            writePayloadTooLarge(response);
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private boolean isIdentityPost(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        PathContainer path = PathContainer.parsePath(request.getRequestURI());
        return IDENTITY_PATHS.stream().anyMatch(pattern -> pattern.matches(path));
    }

    private byte[] readBoundedBody(HttpServletRequest request) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        int offset = 0;
        try (ServletInputStream input = request.getInputStream()) {
            while (offset < buffer.length) {
                int count = input.read(buffer, offset, buffer.length - offset);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    continue;
                }
                offset += count;
            }
        }
        return Arrays.copyOf(buffer, offset);
    }

    private void writePayloadTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Content Too Large\",\"status\":413,"
            + "\"detail\":\"Request body exceeds the maximum allowed size\"}");
    }

    private static class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static class CachedServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream input;

        CachedServletInputStream(byte[] body) {
            this.input = new ByteArrayInputStream(body);
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Non-blocking reads are not supported");
        }

        @Override
        public int read() {
            return input.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return input.read(bytes, offset, length);
        }
    }
}
