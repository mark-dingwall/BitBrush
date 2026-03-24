package au.com.dingwall.mark.bitbrush.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Append-only log of pixel placements on the shared canvas.
 * No unique constraint on (x, y) — each placement is a new row.
 *
 * Authorship is stored as authorUuid only; username is always looked up
 * from the USERS table to avoid transitive dependency (3NF).
 */
@Entity
@Table(name = "pixels", indexes = @Index(name = "idx_pixel_xy", columnList = "x, y"))
public class Pixel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int x;

    @Column(nullable = false)
    private int y;

    @Column(nullable = false)
    private int paletteIndex;

    @Column(nullable = false)
    private String authorUuid;

    @Column(nullable = false)
    private Instant placedAt;

    public Pixel() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public int getX() { return x; }
    public void setX(int x) { this.x = x; }

    public int getY() { return y; }
    public void setY(int y) { this.y = y; }

    public int getPaletteIndex() { return paletteIndex; }
    public void setPaletteIndex(int paletteIndex) { this.paletteIndex = paletteIndex; }

    public String getAuthorUuid() { return authorUuid; }
    public void setAuthorUuid(String authorUuid) { this.authorUuid = authorUuid; }

    public Instant getPlacedAt() { return placedAt; }
    public void setPlacedAt(Instant placedAt) { this.placedAt = placedAt; }
}
