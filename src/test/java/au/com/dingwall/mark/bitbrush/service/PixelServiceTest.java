package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.BitbrushProperties;
import au.com.dingwall.mark.bitbrush.dto.CanvasPixelResponse;
import au.com.dingwall.mark.bitbrush.dto.ColorCountProjection;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PixelService business logic.
 *
 * Pure Mockito -- no Spring context loaded. Tests run in ~1ms each.
 * Follows the same pattern as BankingServiceTest: @ExtendWith(MockitoExtension.class)
 * with @Mock for all dependencies and manual constructor injection.
 *
 * Laravel equivalent: Like a Laravel PHPUnit unit test where you mock all
 * dependencies with Mockery and test the service class in isolation.
 *   - @Mock = Mockery::mock(PixelRepository::class)
 *   - @ExtendWith(MockitoExtension.class) = no equivalent; PHPUnit doesn't need this
 *   - when(...).thenReturn(...) = Mockery::mock()->shouldReceive()->andReturn()
 *   - verify(...) = Mockery::mock()->shouldHaveReceived()
 *   - Constructor injection = app()->make(PixelService::class) with mocked bindings
 */
// Enables @Mock annotations without Spring context. Laravel has no equivalent -- PHPUnit discovers mocks automatically.
@ExtendWith(MockitoExtension.class)
class PixelServiceTest {

    // Creates a Mockito mock. Laravel equivalent: Mockery::mock(PixelRepository::class)
    @Mock
    PixelRepository pixelRepository;

    // Creates a Mockito mock. Laravel equivalent: Mockery::mock(UserRepository::class)
    @Mock
    UserRepository userRepository;

    // Creates a Mockito mock. Laravel equivalent: Mockery::mock(SimpMessagingTemplate::class)
    @Mock
    SimpMessagingTemplate messagingTemplate;

    // Creates a Mockito mock. Laravel equivalent: Mockery::mock(BankingService::class)
    @Mock
    BankingService bankingService;

    // Creates a Mockito mock. Laravel equivalent: Mockery::mock(BitbrushProperties::class)
    @Mock
    BitbrushProperties bitbrushProperties;

    @Mock
    BitbrushProperties.Placement placement;

    // Not @Mock -- this is the class under test, constructed with real logic and mock dependencies
    private PixelService pixelService;

    private final List<String> palette = List.of("#000000", "#FF0000", "#00FF00");

    // Captures the argument passed to a mock method for detailed assertions. Laravel: Mockery's withArgs()
    @Captor
    ArgumentCaptor<List<Pixel>> pixelListCaptor;

    @BeforeEach
    void setUp() {
        // Manual constructor injection -- Spring's DI makes this natural. Laravel: app()->instance(Dep::class, $mock)
        pixelService = new PixelService(
                pixelRepository, userRepository, palette,
                bitbrushProperties, messagingTemplate, bankingService);
    }

    @Test
    void placePixels_validRequest_savesAndBroadcasts() {
        // Arrange: user exists and has sufficient balance
        when(userRepository.existsById("uuid-1")).thenReturn(true);
        when(bankingService.deductPoints("uuid-1", 1)).thenReturn(1);

        // Act: place a single pixel at (5, 10) with palette index 0 (black)
        pixelService.placePixels(new PixelPlacementRequest(
                List.of(new PixelCoordinate(5, 10)), 0, "uuid-1"));

        // Assert: pixel was persisted and broadcast fired
        // Laravel: $this->mock(PixelRepository::class)->shouldReceive('saveAll')->once()
        verify(pixelRepository).saveAll(anyList());
        // Verifies the mock was called. Laravel: $mock->shouldHaveReceived('convertAndSend')->once()
        verify(messagingTemplate).convertAndSend(eq("/topic/pixels"), any(PixelBroadcast.class));
    }

    @Test
    void placePixels_invalidPaletteIndex_throwsIllegalArgument() {
        // Tests programmatic validation -- @Max(215) in DTO handles Spring-level validation.
        // Palette has only 3 entries (indices 0-2), so 999 is out of range.
        assertThrows(IllegalArgumentException.class, () ->
                pixelService.placePixels(new PixelPlacementRequest(
                        List.of(new PixelCoordinate(0, 0)), 999, "uuid-1")));
    }

    @Test
    void placePixels_unknownUser_throwsUserNotFound() {
        // Arrange: user does not exist in the repository
        when(userRepository.existsById("unknown")).thenReturn(false);

        // Act/Assert: UserNotFoundException thrown before any deduction or save
        assertThrows(UserNotFoundException.class, () ->
                pixelService.placePixels(new PixelPlacementRequest(
                        List.of(new PixelCoordinate(0, 0)), 0, "unknown")));
    }

    @Test
    void placePixels_zeroBalance_throwsInsufficientBalance() {
        // Arrange: user exists but has zero balance
        when(userRepository.existsById("uuid-1")).thenReturn(true);
        when(bankingService.deductPoints("uuid-1", 1)).thenReturn(0);
        when(bitbrushProperties.placement()).thenReturn(placement);
        when(placement.earnRateSeconds()).thenReturn(3);

        // Act/Assert: InsufficientBalanceException thrown when deductPoints returns 0
        // Laravel: throw_if($balance === 0, new InsufficientBalanceException(...))
        assertThrows(InsufficientBalanceException.class, () ->
                pixelService.placePixels(new PixelPlacementRequest(
                        List.of(new PixelCoordinate(0, 0)), 0, "uuid-1")));

        // Verify: no pixels saved when balance is zero
        verify(pixelRepository, never()).saveAll(anyList());
    }

    @Test
    void placePixels_partialBalance_placesOnlyDeductedPixels() {
        // Drag-to-place partial placement: user requested 3 pixels but only had 2 points
        when(userRepository.existsById("uuid-1")).thenReturn(true);
        when(bankingService.deductPoints("uuid-1", 3)).thenReturn(2);

        // Act: request 3 pixels but only 2 will be placed
        pixelService.placePixels(new PixelPlacementRequest(
                List.of(new PixelCoordinate(0, 0), new PixelCoordinate(1, 1), new PixelCoordinate(2, 2)),
                0, "uuid-1"));

        // Assert: capture saveAll argument and verify only 2 pixels saved (not 3)
        // Captures the argument passed to a mock method for detailed assertions. Laravel: Mockery's withArgs()
        verify(pixelRepository).saveAll(pixelListCaptor.capture());
        List<Pixel> savedPixels = pixelListCaptor.getValue();
        assertEquals(2, savedPixels.size(),
                "Only the first 2 pixels should be placed when deductPoints returns 2");
    }

    @Test
    void registerUser_validRequest_savesUser() {
        // Act: register a new user
        pixelService.registerUser(new UserRegistrationRequest("uuid-reg", "testuser"));

        // Assert: user saved to repository
        // Laravel: $this->mock(UserRepository::class)->shouldReceive('save')->once()
        verify(userRepository).save(any(User.class));
    }

    @Test
    void registerUser_reservedNameYou_throwsIllegalArgument() {
        // "You" reserved for tooltip display -- case-insensitive check in service
        assertThrows(IllegalArgumentException.class, () ->
                pixelService.registerUser(new UserRegistrationRequest("uuid-you", "You")));
    }

    @Test
    void getPixelInfo_erasedPixel_returnsNull() {
        // Arrange: latest pixel at (5,5) has paletteIndex=0 (erased)
        Pixel eraserPixel = new Pixel();
        eraserPixel.setX(5);
        eraserPixel.setY(5);
        eraserPixel.setPaletteIndex(0);
        eraserPixel.setAuthorUuid("uuid-eraser");
        eraserPixel.setPlacedAt(Instant.now());
        when(pixelRepository.findFirstByXAndYOrderByPlacedAtDesc(5, 5))
                .thenReturn(Optional.of(eraserPixel));

        // Act
        PixelInfoResponse result = pixelService.getPixelInfo(5, 5);

        // Assert: erased pixel returns null (controller maps to 404)
        assertNull(result, "getPixelInfo should return null for erased pixel (paletteIndex=0)");
    }

    @Test
    void placePixels_eraserIndex_broadcastsErasedFlag() {
        // Arrange
        when(userRepository.existsById("uuid-1")).thenReturn(true);
        when(bankingService.deductPoints("uuid-1", 1)).thenReturn(1);

        // Act: place pixel with paletteIndex=0 (eraser)
        pixelService.placePixels(new PixelPlacementRequest(
                List.of(new PixelCoordinate(5, 10)), 0, "uuid-1"));

        // Assert: broadcast has erased=true and color="#000000"
        ArgumentCaptor<PixelBroadcast> broadcastCaptor = ArgumentCaptor.forClass(PixelBroadcast.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/pixels"), broadcastCaptor.capture());
        PixelBroadcast broadcast = broadcastCaptor.getValue();
        assertTrue(broadcast.erased(), "Broadcast for paletteIndex=0 should have erased=true");
        assertEquals("#000000", broadcast.color());
    }

    @Test
    void placePixels_normalIndex_broadcastsNotErased() {
        // Arrange
        when(userRepository.existsById("uuid-1")).thenReturn(true);
        when(bankingService.deductPoints("uuid-1", 1)).thenReturn(1);

        // Act: place pixel with paletteIndex=1 (normal color)
        pixelService.placePixels(new PixelPlacementRequest(
                List.of(new PixelCoordinate(5, 10)), 1, "uuid-1"));

        // Assert: broadcast has erased=false and color="#FF0000" (palette index 1 = #FF0000 in test palette)
        ArgumentCaptor<PixelBroadcast> broadcastCaptor = ArgumentCaptor.forClass(PixelBroadcast.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/pixels"), broadcastCaptor.capture());
        PixelBroadcast broadcast = broadcastCaptor.getValue();
        assertFalse(broadcast.erased(), "Broadcast for paletteIndex=1 should have erased=false");
        assertEquals("#FF0000", broadcast.color());
    }

    @Test
    void getStats_excludesErasedPixels() {
        // Arrange: repository already filters erased pixels, verify service maps correctly
        when(pixelRepository.countCurrentPixels()).thenReturn(5L);
        ColorCountProjection proj = mock(ColorCountProjection.class);
        when(proj.getPaletteIndex()).thenReturn(3);
        when(proj.getPixelCount()).thenReturn(5L);
        when(pixelRepository.findColorDistribution()).thenReturn(List.of(proj));

        // Act
        StatsResponse stats = pixelService.getStats();

        // Assert: no NPE or mapping error, correct values
        assertEquals(5L, stats.totalPixels());
        assertEquals(1, stats.colorDistribution().size());
        assertEquals(3, stats.colorDistribution().getFirst().paletteIndex());
    }

    @Test
    void getCurrentCanvasState_mapsPixelsToResponse() {
        // Arrange: a single pixel at (3, 7) with paletteIndex=1 (maps to "#FF0000" in test palette)
        Pixel pixel = new Pixel();
        pixel.setX(3);
        pixel.setY(7);
        pixel.setPaletteIndex(1);
        pixel.setAuthorUuid("uuid-canvas");
        pixel.setPlacedAt(Instant.now());
        when(pixelRepository.findCurrentCanvasState()).thenReturn(List.of(pixel));

        // Act: fetch canvas state
        // Palette resolution: backend stores index, API returns hex string
        List<CanvasPixelResponse> result = pixelService.getCurrentCanvasState();

        // Assert: one entry with resolved hex color
        assertEquals(1, result.size());
        assertEquals(3, result.getFirst().x());
        assertEquals(7, result.getFirst().y());
        assertEquals("#FF0000", result.getFirst().color(),
                "paletteIndex 1 should resolve to second entry in palette (#FF0000)");
    }
}
