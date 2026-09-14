package au.com.dingwall.mark.bitbrush.repository;

import au.com.dingwall.mark.bitbrush.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
class UserRepositoryTest {
    @Autowired UserRepository userRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void savedUserRetainsPrivateCredentialAndSeparatePublicAuthor() {
        userRepository.saveAndFlush(user("private-1", "Artist", "author_first", "encoded-first", true));
        entityManager.clear();
        User saved = userRepository.findById("private-1").orElseThrow();
        assertThat(saved.getUsername()).isEqualTo("Artist");
        assertThat(saved.getAuthorId()).isEqualTo("author_first");
        assertTrue("encoded-first".equals(saved.getPinHash()), "Stored credential changed");
        assertTrue(saved.isPinBackfilled());
        assertFalse(saved.isNew());
    }

    @Test
    void lookupsAreExactCaseAndKeepIdentifierNamespacesSeparate() {
        userRepository.saveAndFlush(user("private-1", "Artist", "author_first", "hash", true));
        userRepository.saveAndFlush(user("private-2", "artist", "author_second", "hash", false));
        assertThat(userRepository.findByUsername("Artist").orElseThrow().getAuthorId()).isEqualTo("author_first");
        assertThat(userRepository.findByUsername("artist").orElseThrow().getAuthorId()).isEqualTo("author_second");
        assertThat(userRepository.findByUsername("ARTIST")).isEmpty();
        assertThat(userRepository.findByAuthorId("author_first").orElseThrow().getUsername()).isEqualTo("Artist");
        assertThat(userRepository.findById("author_first")).isEmpty();
        assertThat(userRepository.findByAuthorId("private-1")).isEmpty();
        assertTrue(userRepository.existsByUsername("Artist"));
        assertFalse(userRepository.existsByUsername("ARTIST"));
        assertTrue(userRepository.existsByAuthorId("author_first"));
        assertFalse(userRepository.existsByAuthorId("private-1"));
    }

    @Test
    void backfillExportFindsOnlyBackfilledUsersInUsernameOrder() {
        userRepository.saveAndFlush(user("private-1", "Zed", "author_first", "hash", true));
        userRepository.saveAndFlush(user("private-2", "Amy", "author_second", "hash", true));
        userRepository.saveAndFlush(user("private-3", "Bob", "author_third", "hash", false));
        assertThat(userRepository.findAllByPinBackfilledTrueOrderByUsernameAsc())
            .extracting(User::getUsername).containsExactly("Amy", "Zed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "authorId", "pinHash"})
    void identityFieldsCannotBeNull(String field) {
        User incomplete = user("private-1", "Artist", "author_first", "hash", false);
        new BeanWrapperImpl(incomplete).setPropertyValue(field, null);
        assertThrows(DataIntegrityViolationException.class, () -> userRepository.saveAndFlush(incomplete));
    }

    @ParameterizedTest
    @ValueSource(strings = {"uuid", "pin_backfilled"})
    void databaseRejectsNullIdentifierAndBackfillFlag(String field) {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO users(uuid, username, author_id, pin_hash, pin_backfilled) VALUES (?, ?, ?, ?, ?)",
            field.equals("uuid") ? null : "private-null-test", "NullFieldTest", "author_null_test", "fixture-hash",
            field.equals("pin_backfilled") ? null : false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "authorId"})
    void uniqueIdentityFieldsAreEnforced(String field) {
        userRepository.saveAndFlush(user("private-1", "Artist", "author_first", "hash", false));
        User duplicate = user("private-2", "Another", "author_second", "another-hash", false);
        new BeanWrapperImpl(duplicate).setPropertyValue(field, field.equals("username") ? "Artist" : "author_first");
        assertThrows(DataIntegrityViolationException.class, () -> userRepository.saveAndFlush(duplicate));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void newlyConstructedDuplicateUuidCannotOverwriteCommittedIdentity() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status ->
            userRepository.saveAndFlush(user("duplicate-private-id", "original_identity", "author_original", "original-hash", true)));
        assertThrows(DataIntegrityViolationException.class, () -> transaction.executeWithoutResult(status ->
            userRepository.saveAndFlush(user("duplicate-private-id", "replacement_identity", "author_replacement", "replacement-hash", false))));
        transaction.executeWithoutResult(status -> {
            User original = userRepository.findById("duplicate-private-id").orElseThrow();
            assertThat(original.getUsername()).isEqualTo("original_identity");
            assertThat(original.getAuthorId()).isEqualTo("author_original");
            assertTrue("original-hash".equals(original.getPinHash()), "Existing credential changed");
            assertTrue(original.isPinBackfilled());
            userRepository.delete(original);
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void databaseConstraintDiagnosticsDoNotExposePrivateValues() {
        String privateId = java.util.UUID.randomUUID().toString();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status ->
            userRepository.saveAndFlush(user(privateId, "DatabasePrivacy", "author_db_original", "test-hash", false)));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
            "org.hibernate.engine.jdbc.spi.SqlExceptionHelper");
        boolean previousAdditive = logger.isAdditive();
        var capture = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        capture.start();
        logger.setAdditive(false);
        logger.addAppender(capture);
        try {
            boolean rejected = false;
            try {
                transaction.executeWithoutResult(status ->
                    userRepository.saveAndFlush(user(privateId, "DatabaseDuplicate", "author_db_duplicate", "other-hash", false)));
            } catch (DataIntegrityViolationException expected) {
                rejected = true;
            }
            assertTrue(rejected, "The database duplicate insert was not rejected");
            boolean errorLeak = capture.list.stream().anyMatch(event ->
                event.getLevel() == ch.qos.logback.classic.Level.ERROR && event.getFormattedMessage().indexOf(privateId) >= 0);
            assertFalse(errorLeak, "Database exception logger exposed a private credential at ERROR");
            assertFalse(capture.list.stream().anyMatch(event -> event.getFormattedMessage().indexOf(privateId) >= 0),
                "Database exception logger exposed a private credential");
        } finally {
            logger.detachAppender(capture);
            logger.setAdditive(previousAdditive);
            capture.stop();
            transaction.executeWithoutResult(status -> userRepository.deleteById(privateId));
        }
    }

    private User user(String uuid, String username, String authorId, String hash, boolean backfilled) {
        User user = new User();
        user.setUuid(uuid);
        user.setUsername(username);
        user.setAuthorId(authorId);
        user.setPinHash(hash);
        user.setPinBackfilled(backfilled);
        return user;
    }
}
