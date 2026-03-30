package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.BitbrushProperties;
import au.com.dingwall.mark.bitbrush.dto.CanvasPixelResponse;
import au.com.dingwall.mark.bitbrush.dto.PixelBroadcast;
import au.com.dingwall.mark.bitbrush.dto.PixelCoordinate;
import au.com.dingwall.mark.bitbrush.dto.PixelInfoResponse;
import au.com.dingwall.mark.bitbrush.dto.PixelPlacementRequest;
import au.com.dingwall.mark.bitbrush.dto.StatsResponse;
import au.com.dingwall.mark.bitbrush.dto.UserRegistrationRequest;
import au.com.dingwall.mark.bitbrush.exception.InsufficientBalanceException;
import au.com.dingwall.mark.bitbrush.exception.UserNotFoundException;
import au.com.dingwall.mark.bitbrush.model.Pixel;
import au.com.dingwall.mark.bitbrush.model.User;
import au.com.dingwall.mark.bitbrush.repository.PixelRepository;
import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Business logic for the shared pixel canvas.
 *
 * Responsibilities: retrieving current canvas state, placing pixels (with
 * palette validation), and registering users.
 */
@Service
public class PixelService {

    private static final Logger log = LoggerFactory.getLogger(PixelService.class);

    private final PixelRepository pixelRepository;
    private final UserRepository userRepository;
    private final List<String> colorPalette;
    private final BitbrushProperties bitbrushProperties;
    private final SimpMessagingTemplate messagingTemplate;
    private final BankingService bankingService;

    public PixelService(
            PixelRepository pixelRepository,
            UserRepository userRepository,
            List<String> colorPalette,
            BitbrushProperties bitbrushProperties,
            SimpMessagingTemplate messagingTemplate,
            BankingService bankingService) {
        this.pixelRepository = pixelRepository;
        this.userRepository = userRepository;
        this.colorPalette = colorPalette;
        this.bitbrushProperties = bitbrushProperties;
        this.messagingTemplate = messagingTemplate;
        this.bankingService = bankingService;
    }

    /**
     * Returns the current state of the canvas: one entry per coordinate,
     * showing the most recently placed pixel at each (x, y) position.
     */
    public List<CanvasPixelResponse> getCurrentCanvasState() {
        log.debug("Fetching current canvas state");
        return pixelRepository.findCurrentCanvasState().stream()
                .map(p -> new CanvasPixelResponse(p.getX(), p.getY(), colorPalette.get(p.getPaletteIndex())))
                .toList();
    }

    /**
     * Places one or more pixels on the canvas.
     * Validates palette index programmatically and verifies the author UUID
     * exists in the User registry before persisting.
     *
     * Uses atomic multi-point deduction: deducts up to N points for N pixels.
     * If balance is insufficient for all pixels, only the first {@code deducted}
     * pixels are placed (partial placement for drag batches). If balance is 0,
     * throws InsufficientBalanceException (402 via GlobalExceptionHandler).
     */
    public void placePixels(PixelPlacementRequest request) {
        if (request.paletteIndex() >= colorPalette.size()) {
            throw new IllegalArgumentException("paletteIndex out of range");
        }

        if (!userRepository.existsById(request.authorUuid())) {
            throw new UserNotFoundException(request.authorUuid());
        }

        int deducted = bankingService.deductPoints(request.authorUuid(), request.pixels().size());
        if (deducted == 0) {
            throw new InsufficientBalanceException(
                    bitbrushProperties.placement().earnRateSeconds());
        }

        // Only place the first `deducted` pixels (partial placement for drag batches)
        List<PixelCoordinate> pixelsToPlace = request.pixels().subList(0, deducted);

        List<Pixel> pixels = pixelsToPlace.stream()
                .map(coord -> {
                    Pixel pixel = new Pixel();
                    pixel.setX(coord.x());
                    pixel.setY(coord.y());
                    pixel.setPaletteIndex(request.paletteIndex());
                    pixel.setAuthorUuid(request.authorUuid());
                    pixel.setPlacedAt(Instant.now());
                    return pixel;
                })
                .toList();

        pixelRepository.saveAll(pixels);
        log.debug("Saved {} pixels by uuid={} (requested={}, deducted={})",
                pixels.size(), request.authorUuid(), request.pixels().size(), deducted);

        boolean isEraser = request.paletteIndex() == 0;
        String hexColor = colorPalette.get(request.paletteIndex());
        for (PixelCoordinate coord : pixelsToPlace) {
            messagingTemplate.convertAndSend("/topic/pixels",
                    new PixelBroadcast(coord.x(), coord.y(), hexColor, request.authorUuid(), isEraser));
        }
        log.debug("Broadcast {} pixels to /topic/pixels", pixels.size());
    }

    /**
     * Returns true if a user with the given UUID already exists in the database.
     */
    public boolean userExists(String uuid) {
        return userRepository.existsById(uuid);
    }

    /**
     * Registers a UUID-to-username mapping for pixel authorship.
     */
    public void registerUser(UserRegistrationRequest req) {
        log.debug("Registering user: uuid={}", req.uuid());
        if (req.username().equalsIgnoreCase("You")) {
            throw new IllegalArgumentException("Username 'You' is reserved");
        }
        User user = new User();
        user.setUuid(req.uuid());
        user.setUsername(req.username());
        userRepository.save(user);
    }

    /**
     * Returns pixel info for the most recently placed pixel at (x, y).
     *
     * Includes the author's username, placement timestamp, author UUID,
     * and all current (x, y) positions by that author (for canvas highlight overlay).
     *
     * @return PixelInfoResponse or null if no pixel has been placed at (x, y)
     */
    public PixelInfoResponse getPixelInfo(int x, int y) {
        log.debug("Getting pixel info at ({}, {})", x, y);
        Optional<Pixel> optionalPixel = pixelRepository.findFirstByXAndYOrderByPlacedAtDesc(x, y);
        if (optionalPixel.isEmpty()) {
            return null;
        }

        Pixel latest = optionalPixel.get();
        if (latest.getPaletteIndex() == 0) {
            return null;
        }
        String username = userRepository.findById(latest.getAuthorUuid())
                .map(User::getUsername)
                .orElse("unknown");

        List<PixelInfoResponse.AuthorPixelCoordinate> authorPixels =
                pixelRepository.findCurrentPixelsByAuthor(latest.getAuthorUuid()).stream()
                        .map(p -> new PixelInfoResponse.AuthorPixelCoordinate(p.getX(), p.getY()))
                        .toList();

        return new PixelInfoResponse(
                latest.getAuthorUuid(),
                username,
                latest.getPlacedAt(),
                authorPixels
        );
    }

    /**
     * Returns aggregate canvas statistics: total pixel count and per-color distribution.
     */
    public StatsResponse getStats() {
        log.debug("Fetching canvas stats");
        long totalPixels = pixelRepository.countCurrentPixels();

        List<StatsResponse.ColorCount> colorDistribution =
                pixelRepository.findColorDistribution().stream()
                        .map(p -> new StatsResponse.ColorCount(p.getPaletteIndex(), p.getPixelCount()))
                        .toList();

        return new StatsResponse(totalPixels, colorDistribution);
    }
}
