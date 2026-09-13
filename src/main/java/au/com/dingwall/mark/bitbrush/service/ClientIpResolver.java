package au.com.dingwall.mark.bitbrush.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

@Component
public class ClientIpResolver {

    private static final String FLY_CLIENT_IP = "Fly-Client-IP";

    private final Environment environment;

    public ClientIpResolver(Environment environment) {
        this.environment = environment;
    }

    public InetAddress resolve(HttpServletRequest request) {
        if (environment.matchesProfiles("prod")) {
            List<String> flyClientIps = Collections.list(request.getHeaders(FLY_CLIENT_IP));
            if (flyClientIps.size() != 1) {
                throw invalidAddress();
            }
            return parseNumericLiteral(flyClientIps.getFirst());
        }
        return parseNumericLiteral(request.getRemoteAddr());
    }

    private InetAddress parseNumericLiteral(String value) {
        if (value == null || value.isEmpty() || value.indexOf('%') >= 0) {
            throw invalidAddress();
        }
        if (value.indexOf(':') >= 0) {
            return parseIpv6(value);
        }
        return parseIpv4(value);
    }

    private InetAddress parseIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            throw invalidAddress();
        }
        byte[] address = new byte[4];
        for (int i = 0; i < octets.length; i++) {
            try {
                if (octets[i].isEmpty() || !octets[i].chars().allMatch(Character::isDigit)) {
                    throw invalidAddress();
                }
                int octet = Integer.parseInt(octets[i]);
                if (octet > 255) {
                    throw invalidAddress();
                }
                address[i] = (byte) octet;
            } catch (NumberFormatException exception) {
                throw invalidAddress();
            }
        }
        try {
            return InetAddress.getByAddress(address);
        } catch (UnknownHostException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private InetAddress parseIpv6(String value) {
        if (!value.matches("[0-9A-Fa-f:.]+")) {
            throw invalidAddress();
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException exception) {
            throw invalidAddress();
        }
    }

    private IllegalArgumentException invalidAddress() {
        return new IllegalArgumentException("Client IP must be a numeric address literal");
    }
}
