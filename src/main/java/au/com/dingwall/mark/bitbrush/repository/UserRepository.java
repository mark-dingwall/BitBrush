package au.com.dingwall.mark.bitbrush.repository;

import au.com.dingwall.mark.bitbrush.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/**
 * Persistence layer for private credentials and public authorship.
 */
public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByUsername(String username);
    Optional<User> findByAuthorId(String authorId);
    boolean existsByUsername(String username);
    boolean existsByAuthorId(String authorId);
    List<User> findAllByPinBackfilledTrueOrderByUsernameAsc();
}
