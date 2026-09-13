package au.com.dingwall.mark.bitbrush.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.domain.Persistable;

/**
 * Insert-only identity with a private bearer UUID and separate public authorship.
 */
@Entity
@Table(name = "users", uniqueConstraints = {
    @UniqueConstraint(name = "uk_users_username", columnNames = "username"),
    @UniqueConstraint(name = "uk_users_author_id", columnNames = "author_id")
})
public class User implements Persistable<String> {

    @Transient
    private boolean isNew = true;

    @Id
    @Column(name = "uuid", nullable = false)
    private String uuid;

    @Column(nullable = false)
    private String username;

    @Column(name = "author_id", nullable = false)
    private String authorId;

    @Column(name = "pin_hash", nullable = false)
    private String pinHash;

    @Column(name = "pin_backfilled", nullable = false)
    private boolean pinBackfilled;

    public User() {}

    @Override
    public String getId() { return uuid; }

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    private void markPersisted() { isNew = false; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }

    public String getPinHash() { return pinHash; }
    public void setPinHash(String pinHash) { this.pinHash = pinHash; }

    public boolean isPinBackfilled() { return pinBackfilled; }
    public void setPinBackfilled(boolean pinBackfilled) { this.pinBackfilled = pinBackfilled; }
}
