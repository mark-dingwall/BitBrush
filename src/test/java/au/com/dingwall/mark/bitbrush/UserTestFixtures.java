package au.com.dingwall.mark.bitbrush;

import au.com.dingwall.mark.bitbrush.model.User;
import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import au.com.dingwall.mark.bitbrush.service.PinCredentialCodec;

import java.util.UUID;

/** Complete persisted identities for tests whose subject is not account creation. */
public final class UserTestFixtures {
    private UserTestFixtures() {}

    public static User persist(UserRepository users, String uuid, String username) {
        var codec = new PinCredentialCodec(new byte[32], 32, 1, 1, 16);
        User user = new User();
        user.setUuid(uuid);
        user.setUsername(username);
        user.setAuthorId("author_" + UUID.randomUUID().toString().replace("-", ""));
        user.setPinHash(codec.hash(codec.canonicalize("T!s7")));
        user.setPinBackfilled(false);
        return users.saveAndFlush(user);
    }
}
