package db.migration;

import au.com.dingwall.mark.bitbrush.config.PinPepperDecoder;
import au.com.dingwall.mark.bitbrush.service.PinCredentialCodec;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.UUID;

/** Rotates exposed legacy bearer IDs while retaining their public attribution. */
public class V3__Backfill_user_pin_credentials extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        // Validate before obtaining a JDBC connection or inspecting any legacy identity.
        byte[] pepper = PinPepperDecoder.decode(context.getConfiguration().getPlaceholders().get("pin-pepper"));
        PinCredentialCodec codec;
        try {
            codec = new PinCredentialCodec(pepper, 19_456, 2, 1, 32);
        } finally {
            Arrays.fill(pepper, (byte) 0);
        }

        Connection connection = context.getConnection();
        try (PreparedStatement users = connection.prepareStatement(
                "SELECT uuid FROM users WHERE author_id IS NULL ORDER BY uuid");
             PreparedStatement collision = connection.prepareStatement(
                "SELECT 1 FROM users WHERE uuid = ? OR author_id = ?");
             PreparedStatement update = connection.prepareStatement(
                "UPDATE users SET uuid = ?, author_id = ?, pin_hash = ?, pin_backfilled = TRUE WHERE uuid = ?");
             ResultSet rows = users.executeQuery()) {
            while (rows.next()) {
                String authorId = rows.getString("uuid");
                String privateUuid;
                do {
                    privateUuid = UUID.randomUUID().toString();
                    collision.setString(1, privateUuid);
                    collision.setString(2, privateUuid);
                    try (ResultSet existing = collision.executeQuery()) {
                        if (!existing.next()) break;
                    }
                } while (true);

                String hash = codec.hash(codec.canonicalize(codec.deriveLegacyPin(authorId)));
                update.setString(1, privateUuid);
                update.setString(2, authorId);
                update.setString(3, hash);
                update.setString(4, authorId);
                update.executeUpdate();
            }
        }
    }
}
