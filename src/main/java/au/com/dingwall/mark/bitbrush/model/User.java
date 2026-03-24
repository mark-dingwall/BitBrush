package au.com.dingwall.mark.bitbrush.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * UUID-to-username mapping for pixel authorship.
 * UUID is set explicitly — no @GeneratedValue.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "uuid", nullable = false)
    private String uuid;

    @Column(nullable = false, unique = true)
    private String username;

    public User() {}

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
}
