package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VenueSuggestionServiceTest {
    static ValidatorFactory validation;
    VenueSuggestionStore store;
    VenueSuggestionService service;
    UUID city = UUID.randomUUID(), district = UUID.randomUUID();
    @BeforeAll static void validation() { validation = Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void closeValidation() { validation.close(); }
    @BeforeEach void setUp() {
        store = mock(VenueSuggestionStore.class);
        service = new VenueSuggestionService(store, validation.getValidator(), " One@example.test,one@example.test,Two@example.test ");
    }
    @Test void fixedServerRecipientsAreNormalizedDeduplicatedAndNameIsTrimmed() {
        var request = request("  Canlı   Müzik  "); service.accept(request);
        verify(store).accept(eq(request), eq("Canlı Müzik"), anyString(), anyString(),
                eq(List.of("one@example.test", "two@example.test")));
    }
    @Test void normalizedCaseAndWhitespaceShareDailyDeduplicationKey() {
        service.accept(request("  İSTANBUL  Mekan ")); service.accept(request("istanbul mekan"));
        var key = ArgumentCaptor.forClass(String.class);
        verify(store, times(2)).accept(any(), any(), any(), key.capture(), any());
        assertThat(key.getAllValues().getFirst()).isEqualTo(key.getAllValues().getLast());
    }
    @ParameterizedTest @ValueSource(strings = {"", " ", "a", "Mekan\nSahte açıklama", "Mekan\rBcc: x@example.test", "Mekan\u202E", "Mekan\u0000"})
    void invalidOrControlNamesNeverReachStorage(String name) {
        assertThatThrownBy(() -> service.accept(request(name))).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(store);
    }
    @Test void nameIsBoundedByUnicodeCodePoints() {
        assertThat(VenueSuggestionNormalizer.name("🎵".repeat(100))).hasSize(200);
        assertThatThrownBy(() -> service.accept(request("🎵".repeat(101)))).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.accept(request("x".repeat(401)))).isInstanceOf(SoundConnectException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"", "x", "a@example.test,", "a@example.test\r\nBcc:b@example.test"})
    void invalidRecipientConfigurationFailsClosed(String recipients) {
        assertThatThrownBy(() -> new VenueSuggestionService(store, validation.getValidator(), recipients)).isInstanceOf(IllegalStateException.class);
    }
    @Test void storageOrCommitFailureNeverReturnsAccepted() {
        doThrow(new DataAccessResourceFailureException("offline")).when(store).accept(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.accept(request("Mekan"))).isInstanceOf(ServiceUnavailableRetryException.class);
    }
    private VenueSuggestionRequest request(String name) {
        return new VenueSuggestionRequest(UUID.randomUUID(), name, city, district, VenueSuggestionRequest.LiveMusic.UNKNOWN);
    }
}
