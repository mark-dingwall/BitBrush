package au.com.dingwall.mark.bitbrush.repository;

import au.com.dingwall.mark.bitbrush.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Repository slice test for User entity persistence (IDEN-01, IDEN-02).
 *
 * Uses @DataJpaTest -- only the JPA layer is loaded (in-memory H2).
 * No controllers, no services, no WebSocket infrastructure.
 * Each test runs in a @Transactional context that auto-rolls back.
 *
 * Laravel equivalent: This is like testing an Eloquent model with RefreshDatabase,
 * except @DataJpaTest loads ONLY the database layer -- not the entire application.
 * In Laravel, even a simple model test boots middleware, routes, and service providers.
 * Spring's test slicing means this test runs in ~200ms, not ~2s.
 *   - @DataJpaTest = @RefreshDatabase but with surgical precision
 *   - @Transactional auto-rollback = RefreshDatabase's transaction wrapping
 *   - assertThat(saved.getUuid()) = $this->assertDatabaseHas('users', ['uuid' => ...])
 */
@DataJpaTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void savedUserHasAllFields() {
        // Arrange: create and save a user
        User user = new User();
        user.setUuid("test-uuid");
        user.setUsername("testuser");
        userRepository.save(user);

        // Act: reload from the database by primary key
        // Laravel: User::create([...]) + $this->assertDatabaseHas('users', [...])
        User reloaded = userRepository.findById("test-uuid").orElseThrow();

        // Assert: all fields persisted correctly
        assertThat(reloaded.getUuid()).isEqualTo("test-uuid");
        assertThat(reloaded.getUsername()).isEqualTo("testuser");
    }

    @Test
    void findByIdReturnsEmptyForNonexistentUuid() {
        // Laravel: User::find('nonexistent') returns null; JPA returns Optional.empty()
        assertThat(userRepository.findById("nonexistent")).isEmpty();
    }

    @Test
    void uniqueUsernameConstraintEnforced() {
        // Arrange: save first user with username "sharedname"
        User first = new User();
        first.setUuid("uuid-first");
        first.setUsername("sharedname");
        userRepository.saveAndFlush(first);

        // Act/Assert: second user with same username but different UUID should violate unique constraint
        User second = new User();
        second.setUuid("uuid-second");
        second.setUsername("sharedname");

        // Must use saveAndFlush() -- @DataJpaTest's @Transactional defers SQL until flush.
        // Without explicit flush, the unique constraint is never checked before rollback.
        // Laravel: RefreshDatabase also wraps in transactions, but Eloquent typically flushes immediately.
        assertThrows(DataIntegrityViolationException.class, () ->
                userRepository.saveAndFlush(second));
    }

    @Test
    void existsByIdReturnsTrueForExistingUser() {
        // Arrange: save a user
        User user = new User();
        user.setUuid("test-uuid");
        user.setUsername("existsuser");
        userRepository.save(user);

        // Assert: existsById returns true for saved user, false for unknown UUID
        // PixelService uses existsById() to validate author UUID before pixel placement
        assertThat(userRepository.existsById("test-uuid")).isTrue();
        assertThat(userRepository.existsById("nonexistent")).isFalse();
    }
}
