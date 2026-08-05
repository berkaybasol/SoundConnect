package com.berkayb.soundconnect.modules.backline.catalog.service;

import com.berkayb.soundconnect.modules.backline.catalog.dto.BacklineCategoryRequestCreateRequest;
import com.berkayb.soundconnect.modules.backline.catalog.dto.BacklineCategoryReviewRequest;
import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategory;
import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategoryRequest;
import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryRequestStatus;
import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryRequestType;
import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryReviewDecision;
import com.berkayb.soundconnect.modules.backline.catalog.repository.BacklineCategoryRepository;
import com.berkayb.soundconnect.modules.backline.catalog.repository.BacklineCategoryRequestRepository;
import com.berkayb.soundconnect.modules.backline.catalog.support.BacklineCatalogTimeProvider;
import com.berkayb.soundconnect.modules.backline.catalog.support.BacklineCategoryNames;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("service")
class BacklineCatalogServiceTest {

    @Mock private BacklineCategoryRepository categoryRepository;
    @Mock private BacklineCategoryRequestRepository requestRepository;
    @Mock private StudioProfileRepository studioProfileRepository;
    @Mock private BacklineCatalogTimeProvider timeProvider;

    private BacklineCatalogService service;
    private UUID ownerId;
    private StudioProfile studio;

    @BeforeEach
    void setUp() {
        service = new BacklineCatalogService(
                categoryRepository,
                requestRepository,
                studioProfileRepository,
                new BacklineCategoryNames(),
                timeProvider
        );
        ownerId = UUID.randomUUID();
        User owner = new User();
        owner.setId(ownerId);
        studio = new StudioProfile();
        studio.setId(UUID.randomUUID());
        studio.setName("Stüdyo Atlas");
        studio.setUser(owner);
    }

    @Test
    void submitRootRequestIsCanonicalAndExactlyIdempotent() {
        UUID clientRequestId = UUID.randomUUID();
        BacklineCategoryRequestCreateRequest command = new BacklineCategoryRequestCreateRequest(
                clientRequestId,
                BacklineCategoryRequestType.ROOT_CATEGORY,
                "  Yeni   Kategori  ",
                null,
                List.of("  Birinci Alt  ", "İkinci Alt"),
                "  Sahne   ekipmanları için  "
        );
        AtomicReference<BacklineCategoryRequest> savedRequest = new AtomicReference<>();

        when(studioProfileRepository.findByUserIdForUpdate(ownerId)).thenReturn(Optional.of(studio));
        when(requestRepository.findByStudioProfileIdAndClientRequestId(studio.getId(), clientRequestId))
                .thenAnswer(ignored -> Optional.ofNullable(savedRequest.get()));
        when(categoryRepository.findByParentIsNullAndNormalizedName(anyString())).thenReturn(Optional.empty());
        when(requestRepository.saveAndFlush(any(BacklineCategoryRequest.class))).thenAnswer(invocation -> {
            BacklineCategoryRequest request = invocation.getArgument(0);
            request.setId(UUID.randomUUID());
            request.setCreatedAt(LocalDateTime.of(2026, 7, 21, 10, 30));
            savedRequest.set(request);
            return request;
        });

        var first = service.submitRequest(ownerId, command);
        var replay = service.submitRequest(ownerId, command);

        assertThat(first.id()).isEqualTo(replay.id());
        assertThat(first.studioName()).isEqualTo("Stüdyo Atlas");
        assertThat(first.requestedName()).isEqualTo("Yeni Kategori");
        assertThat(first.proposedChildren()).extracting(child -> child.name())
                .containsExactly("Birinci Alt", "İkinci Alt");
        assertThat(first.requesterNote()).isEqualTo("Sahne ekipmanları için");
        assertThat(savedRequest.get().getRequestPayloadHash()).hasSize(64);
        assertThat(first.createdAtUtc()).isEqualTo(Instant.parse("2026-07-21T10:30:00Z"));
        verify(requestRepository).saveAndFlush(any(BacklineCategoryRequest.class));
    }

    @Test
    void reusedCategoryRequestKeyWithDifferentPayloadIsRejected() {
        UUID clientRequestId = UUID.randomUUID();
        BacklineCategoryRequest existing = BacklineCategoryRequest.create(
                studio,
                ownerId,
                clientRequestId,
                "0".repeat(64),
                BacklineCategoryRequestType.ROOT_CATEGORY,
                "Existing",
                "existing",
                null,
                null,
                List.of()
        );
        existing.setId(UUID.randomUUID());
        BacklineCategoryRequestCreateRequest command = new BacklineCategoryRequestCreateRequest(
                clientRequestId,
                BacklineCategoryRequestType.ROOT_CATEGORY,
                "Different",
                null,
                List.of(),
                null
        );

        when(studioProfileRepository.findByUserIdForUpdate(ownerId)).thenReturn(Optional.of(studio));
        when(requestRepository.findByStudioProfileIdAndClientRequestId(studio.getId(), clientRequestId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submitRequest(ownerId, command))
                .isInstanceOf(SoundConnectException.class)
                .satisfies(error -> assertThat(((SoundConnectException) error).getErrorType())
                        .isEqualTo(ErrorType.DATA_INTEGRITY_CONFLICT));

        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void deepPublicCatalogPageFailsBeforeRepositoryAccess() {
        assertThatThrownBy(() -> service.listPublicCategories(1001, 20))
                .isInstanceOf(SoundConnectException.class)
                .satisfies(error -> assertThat(((SoundConnectException) error).getErrorType())
                        .isEqualTo(ErrorType.VALIDATION_ERROR));

        verify(categoryRepository, never()).findByParentIsNullAndActiveTrue(any());
    }

    @Test
    void deepOwnerRequestPageFailsBeforeProfileOrRequestRepositoryAccess() {
        assertThatThrownBy(() -> service.listOwnerRequests(ownerId, 1001, 20))
                .isInstanceOf(SoundConnectException.class)
                .satisfies(error -> assertThat(((SoundConnectException) error).getErrorType())
                        .isEqualTo(ErrorType.VALIDATION_ERROR));

        verify(studioProfileRepository, never()).findByUserId(any());
        verify(requestRepository, never()).findByStudioProfileId(any(), any());
    }

    @Test
    void foreignRequestWithdrawalReturnsNotFoundWithoutLockingTheForeignRequest() {
        UUID foreignRequestId = UUID.randomUUID();
        when(requestRepository.findOwnedByIdForUpdate(foreignRequestId, ownerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdrawRequest(ownerId, foreignRequestId))
                .isInstanceOf(SoundConnectException.class)
                .satisfies(error -> assertThat(((SoundConnectException) error).getErrorType())
                        .isEqualTo(ErrorType.BACKLINE_CATEGORY_REQUEST_NOT_FOUND));

        verify(requestRepository).findOwnedByIdForUpdate(foreignRequestId, ownerId);
        verify(requestRepository, never()).findByIdForUpdate(any());
        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void approvingRootRequestCreatesStableRootAndChildrenAtomically() {
        UUID reviewerId = UUID.randomUUID();
        BacklineCategoryRequest request = BacklineCategoryRequest.create(
                studio,
                ownerId,
                UUID.randomUUID(),
                "a".repeat(64),
                BacklineCategoryRequestType.ROOT_CATEGORY,
                "Yeni Kategori",
                "yeni kategori",
                null,
                null,
                List.of(
                        new BacklineCategoryRequest.CategoryCandidate("Alt Bir", "alt bir"),
                        new BacklineCategoryRequest.CategoryCandidate("Alt İki", "alt iki")
                )
        );
        request.setId(UUID.randomUUID());
        Instant decisionTime = Instant.parse("2026-07-21T10:00:00Z");

        when(requestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(categoryRepository.findRootForUpdate("yeni kategori")).thenReturn(Optional.empty());
        when(categoryRepository.findMaximumRootSortOrder()).thenReturn(4);
        when(categoryRepository.findMaximumChildSortOrder(any(UUID.class))).thenReturn(-1);
        when(categoryRepository.findChildForUpdate(any(UUID.class), anyString())).thenReturn(Optional.empty());
        when(categoryRepository.saveAndFlush(any(BacklineCategory.class))).thenAnswer(invocation -> {
            BacklineCategory category = invocation.getArgument(0);
            category.setId(UUID.randomUUID());
            return category;
        });
        when(requestRepository.saveAndFlush(request)).thenReturn(request);
        when(timeProvider.now()).thenReturn(decisionTime);

        var response = service.reviewRequest(
                reviewerId,
                request.getId(),
                new BacklineCategoryReviewRequest(BacklineCategoryReviewDecision.APPROVE, "Uygun")
        );

        assertThat(response.status()).isEqualTo(BacklineCategoryRequestStatus.APPROVED);
        assertThat(response.resolvedRootCategoryId()).isNotNull();
        assertThat(response.resolvedCategoryId()).isEqualTo(response.resolvedRootCategoryId());
        assertThat(response.proposedChildren())
                .allSatisfy(child -> assertThat(child.resolvedCategoryId()).isNotNull());
        ArgumentCaptor<BacklineCategory> categories = ArgumentCaptor.forClass(BacklineCategory.class);
        verify(categoryRepository, org.mockito.Mockito.times(3)).saveAndFlush(categories.capture());
        assertThat(categories.getAllValues().getFirst().getLevel()).isZero();
        assertThat(categories.getAllValues().subList(1, 3))
                .allSatisfy(category -> {
                    assertThat(category.getLevel()).isEqualTo((short) 1);
                    assertThat(category.getCode()).hasSizeLessThanOrEqualTo(96);
                });
    }

    @Test
    void rejectionRequiresAnOperatorNote() {
        BacklineCategoryRequest request = BacklineCategoryRequest.create(
                studio,
                ownerId,
                UUID.randomUUID(),
                "a".repeat(64),
                BacklineCategoryRequestType.ROOT_CATEGORY,
                "Yeni Kategori",
                "yeni kategori",
                null,
                null,
                List.of()
        );
        request.setId(UUID.randomUUID());
        when(requestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.reviewRequest(
                UUID.randomUUID(),
                request.getId(),
                new BacklineCategoryReviewRequest(BacklineCategoryReviewDecision.REJECT, "  ")
        ))
                .isInstanceOf(SoundConnectException.class)
                .satisfies(error -> assertThat(((SoundConnectException) error).getErrorType())
                        .isEqualTo(ErrorType.VALIDATION_ERROR));
    }
}
