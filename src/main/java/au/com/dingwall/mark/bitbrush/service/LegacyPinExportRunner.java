package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.PinProperties;
import au.com.dingwall.mark.bitbrush.model.User;
import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@Component
@DependsOnDatabaseInitialization
public class LegacyPinExportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyPinExportRunner.class);

    private final PinProperties properties;
    private final UserRepository users;
    private final PinCredentialService credentials;
    private final SecureExportPublisher publisher;
    private final String machineId;

    public LegacyPinExportRunner(PinProperties properties, UserRepository users, PinCredentialService credentials,
                                 SecureExportPublisher publisher, @Value("${FLY_MACHINE_ID:local}") String machineId) {
        this.properties = properties;
        this.users = users;
        this.credentials = credentials;
        this.publisher = publisher;
        this.machineId = machineId;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String configuredPath = properties.backfillExportPath();
        if (configuredPath == null || configuredPath.isBlank()) return;

        int rowCount = 0;
        byte[] content = null;
        try {
            Path target = Path.of(configuredPath);
            if (!target.isAbsolute()) throw new IllegalArgumentException("PIN export path must be absolute");

            List<User> backfilled = users.findAllByPinBackfilledTrueOrderByUsernameAsc();
            rowCount = backfilled.size();
            if (backfilled.isEmpty()) return;

            StringBuilder tsv = new StringBuilder();
            for (User user : backfilled) {
                String username = user.getUsername();
                if (username.indexOf('\t') >= 0 || username.indexOf('\r') >= 0 || username.indexOf('\n') >= 0) {
                    throw new IllegalArgumentException("PIN export username contains a TSV delimiter");
                }
                tsv.append(username).append('\t').append(credentials.deriveLegacyPin(user.getAuthorId())).append('\n');
            }
            content = tsv.toString().getBytes(StandardCharsets.UTF_8);
            publisher.publish(target, content);
            log.info("Legacy PIN export published path={} rows={} machine={}", configuredPath, rowCount, machineId);
        } catch (IOException | RuntimeException exception) {
            log.error("Legacy PIN export failed path={} rows={} machine={}", configuredPath, rowCount, machineId);
            // Startup renders exception chains. Repository/provider diagnostics must not
            // turn the protected plaintext-at-rest exception into a credential log.
            throw new IllegalStateException("Unable to safely publish legacy PIN export");
        } finally {
            if (content != null) Arrays.fill(content, (byte) 0);
        }
    }
}
