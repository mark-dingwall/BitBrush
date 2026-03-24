package au.com.dingwall.mark.bitbrush.repository;

import au.com.dingwall.mark.bitbrush.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence layer for UUID-to-username mappings.
 * findById(uuid) is sufficient — no custom queries needed.
 */
public interface UserRepository extends JpaRepository<User, String> {
}
