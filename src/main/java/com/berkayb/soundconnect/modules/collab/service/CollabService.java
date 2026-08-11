package com.berkayb.soundconnect.modules.collab.service;

import com.berkayb.soundconnect.modules.collab.dto.request.*;
import com.berkayb.soundconnect.modules.collab.dto.response.*;
import com.berkayb.soundconnect.modules.collab.entity.*;
import com.berkayb.soundconnect.modules.collab.enums.*;
import com.berkayb.soundconnect.modules.collab.event.CollabNotificationEvent;
import com.berkayb.soundconnect.modules.collab.exception.CollabExpiredException;
import com.berkayb.soundconnect.modules.collab.mapper.CollabMapper;
import com.berkayb.soundconnect.modules.collab.repository.*;
import com.berkayb.soundconnect.modules.collab.spec.CollabSpecifications;
import com.berkayb.soundconnect.modules.collab.support.*;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.support.InstrumentEntityFinder;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.*;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CollabService {
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_PAGE_NUMBER = 1_000;
    private static final long MAX_FEE_MINOR = 100_000_000L;

    private final CollabRepository listingRepository;
    private final CollabActorRepository actorRepository;
    private final CollabApplicationRepository applicationRepository;
    private final CollabJobRepository jobRepository;
    private final CollabReviewRepository reviewRepository;
    private final CollabSavedListingRepository savedRepository;
    private final CollabReportRepository reportRepository;
    private final CollabActorService actorService;
    private final CollabMapper mapper;
    private final UserEntityFinder userFinder;
    private final UserRepository userRepository;
    private final LocationEntityFinder locationFinder;
    private final InstrumentEntityFinder instrumentFinder;
    private final CollabTimeProvider timeProvider;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public List<CollabActorSummary> actorsMine(UUID userId) {
        // Actor synchronization is transactional and may insert projections.
        // Spring will join the outer transaction while retaining a single API surface.
        return actorService.listMine(userId);
    }

    @Transactional
    public CollabListingResponse createDraft(UUID userId, CollabDraftCreateRequest request) {
        User owner = lockUser(userId);
        CollabActor publisher = actorService.requireOwned(userId, request.publisherActorId());
        NormalizedListing normalized = normalizeListing(
                request.cadence(), request.wantedType(), request.instrumentId(), request.branch(),
                request.customSpecialty(), request.title(), request.description(), request.cityId(),
                request.genres(), request.scheduledAt(), request.feeAmountMinor(), request.currency(),
                publisher.getProfileType(), timeProvider.now());
        String payloadHash = creationHash(request.clientRequestId(), publisher.getId(), normalized);

        Optional<Collab> replay = listingRepository.findByOwnerIdAndClientRequestId(userId, request.clientRequestId());
        if (replay.isPresent()) {
            assertPayload(replay.get().getCreationPayloadHash(), payloadHash);
            return mapSingle(replay.get(), userId);
        }

        Collab listing = Collab.builder()
                .owner(owner)
                .publisherActor(publisher)
                .clientRequestId(request.clientRequestId())
                .creationPayloadHash(payloadHash)
                .status(CollabListingStatus.DRAFT)
                .build();
        applyNormalized(listing, normalized, false);
        listingRepository.saveAndFlush(listing);
        return mapSingle(listing, userId);
    }

    @Transactional(noRollbackFor = CollabExpiredException.class)
    public CollabListingResponse update(UUID userId, UUID listingId, CollabUpdateRequest request) {
        Collab listing = lockListing(listingId);
        requireOwner(listing, userId);
        if (listing.getStatus() != CollabListingStatus.DRAFT && listing.getStatus() != CollabListingStatus.OPEN) {
            throw new SoundConnectException(ErrorType.COLLAB_LIFECYCLE_INVALID);
        }
        assertVersion(listing.getVersion(), request.expectedVersion());
        if (listing.getStatus() == CollabListingStatus.OPEN && isDue(listing, timeProvider.now())) {
            expireLocked(listing, timeProvider.now());
            throw new CollabExpiredException();
        }

        CollabActor publisher = actorService.requireOwned(userId, request.publisherActorId());
        NormalizedListing normalized = normalizeListing(
                request.cadence(), request.wantedType(), request.instrumentId(), request.branch(),
                request.customSpecialty(), request.title(), request.description(), request.cityId(),
                request.genres(), request.scheduledAt(), request.feeAmountMinor(), request.currency(),
                publisher.getProfileType(), timeProvider.now());

        if (listing.getStatus() == CollabListingStatus.OPEN && applicationRepository.existsByListingId(listingId)) {
            assertPublishedImmutableFields(listing, publisher, normalized);
        }
        listing.setPublisherActor(publisher);
        applyNormalized(listing, normalized, listing.getStatus() == CollabListingStatus.OPEN);
        listingRepository.flush();
        return mapSingle(listing, userId);
    }

    @Transactional(noRollbackFor = CollabExpiredException.class)
    public CollabListingResponse publish(UUID userId, UUID listingId, ExpectedVersionRequest request) {
        Collab listing = lockListing(listingId);
        requireOwner(listing, userId);
        actorService.requireOwned(userId, listing.getPublisherActor().getId());
        if (listing.getStatus() == CollabListingStatus.OPEN) {
            if (isDue(listing, timeProvider.now())) {
                expireLocked(listing, timeProvider.now());
                throw new CollabExpiredException();
            }
            return mapSingle(listing, userId);
        }
        if (listing.getStatus() != CollabListingStatus.DRAFT) {
            throw new SoundConnectException(ErrorType.COLLAB_LIFECYCLE_INVALID);
        }
        assertVersion(listing.getVersion(), request.expectedVersion());
        Instant now = timeProvider.now();
        validateStoredForPublish(listing, now);
        listing.setStatus(CollabListingStatus.OPEN);
        listing.setPublishedAt(now);
        listing.setExpiresAt(listing.getCadence() == CollabCadence.EXTRA ? listing.getScheduledAt() : null);
        listingRepository.flush();
        return mapSingle(listing, userId);
    }

    @Transactional
    public void deleteDraft(UUID userId, UUID listingId, ExpectedVersionRequest request) {
        Collab listing = lockListing(listingId);
        requireOwner(listing, userId);
        if (listing.getStatus() != CollabListingStatus.DRAFT) {
            throw new SoundConnectException(ErrorType.COLLAB_LIFECYCLE_INVALID);
        }
        assertVersion(listing.getVersion(), request.expectedVersion());
        listingRepository.delete(listing);
    }

    @Transactional(noRollbackFor = CollabExpiredException.class)
    public CollabListingResponse close(UUID userId, UUID listingId, ExpectedVersionRequest request) {
        Collab listing = lockListing(listingId);
        requireOwner(listing, userId);
        if (listing.getStatus() == CollabListingStatus.CLOSED
                && listing.getClosureReason() == CollabClosureReason.OWNER_CLOSED) {
            return mapSingle(listing, userId);
        }
        if (listing.getStatus() != CollabListingStatus.OPEN) {
            throw new SoundConnectException(ErrorType.COLLAB_LIFECYCLE_INVALID);
        }
        Instant now = timeProvider.now();
        if (isDue(listing, now)) {
            expireLocked(listing, now);
            throw new CollabExpiredException();
        }
        assertVersion(listing.getVersion(), request.expectedVersion());
        List<CollabApplication> invalidated = pendingApplications(listingId);
        closeLocked(listing, CollabClosureReason.OWNER_CLOSED, now);
        invalidatePending(listingId, null, now);
        invalidated.forEach(value -> publish(value.getApplicantUser().getId(),
                NotificationType.COLLAB_APPLICATION_INVALIDATED, "Başvuru geçersizleşti",
                "İlan sahibi ilanı kapattı.", "APPLICATION_INVALIDATED",
                Map.of("listingId", listingId, "applicationId", value.getId()), now));
        listingRepository.flush();
        return mapSingle(listing, userId);
    }

    @Transactional
    public CollabListingResponse detail(UUID userId, UUID listingId) {
        Collab listing = lockListing(listingId);
        boolean wasPubliclyOpen = listing.getStatus() == CollabListingStatus.OPEN;
        if (listing.getStatus() == CollabListingStatus.OPEN && isDue(listing, timeProvider.now())) {
            expireLocked(listing, timeProvider.now());
        }
        boolean owner = Objects.equals(listing.getOwner().getId(), userId);
        if (listing.getStatus() == CollabListingStatus.DRAFT && !owner) {
            throw new SoundConnectException(ErrorType.COLLAB_NOT_FOUND);
        }
        if (listing.getStatus() != CollabListingStatus.OPEN && !wasPubliclyOpen && !owner
                && !applicationRepository.existsByListingIdAndApplicantUserId(listingId, userId)) {
            throw new SoundConnectException(ErrorType.COLLAB_NOT_FOUND);
        }
        return mapSingle(listing, userId);
    }

    @Transactional(readOnly = true)
    public PageResponse<CollabListingResponse> discovery(UUID userId, CollabFilterRequest filter, int page, int size) {
        validateFilter(filter);
        Pageable pageable = page(page, size, Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id")));
        Page<Collab> result = listingRepository.findAll(
                CollabSpecifications.discovery(filter, timeProvider.now()), pageable);
        CollabMapper.ListingContext context = listingContext(result.getContent(), userId);
        return PageResponse.from(result.map(value -> mapper.listing(value, context)));
    }

    @Transactional
    public PageResponse<CollabListingResponse> listingsMine(UUID userId, CollabListingStatus status, int page, int size) {
        reconcileOwnedDue(userId);
        Pageable pageable = page(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Collab> result = status == null
                ? listingRepository.findByOwnerId(userId, pageable)
                : listingRepository.findByOwnerIdAndStatus(userId, status, pageable);
        CollabMapper.ListingContext context = listingContext(result.getContent(), userId);
        return PageResponse.from(result.map(value -> mapper.listing(value, context)));
    }

    @Transactional(noRollbackFor = CollabExpiredException.class)
    public CollabListingResponse save(UUID userId, UUID listingId) {
        lockUser(userId);
        Instant now = timeProvider.now();
        Collab listing = listingRepository
                .findVisibleOpenByIdForShare(listingId, CollabListingStatus.OPEN, now)
                .orElseGet(() -> {
                    Collab unavailable = lockListing(listingId);
                    requireOpen(unavailable);
                    return unavailable;
                });
        if (Objects.equals(listing.getOwner().getId(), userId)) {
            throw new SoundConnectException(ErrorType.COLLAB_FORBIDDEN);
        }
        savedRepository.insertIfAbsent(UUID.randomUUID(), userId, listingId);
        return mapSingle(listing, userId);
    }

    @Transactional
    public void unsave(UUID userId, UUID listingId) {
        lockUser(userId);
        savedRepository.deleteByUserIdAndListingId(userId, listingId);
    }

    @Transactional(readOnly = true)
    public PageResponse<CollabListingResponse> savedMine(UUID userId, int page, int size) {
        Pageable pageable = page(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<CollabSavedListing> saved = savedRepository.findVisibleByUserId(
                userId, CollabListingStatus.OPEN, timeProvider.now(), pageable);
        List<Collab> listings = saved.getContent().stream().map(CollabSavedListing::getListing).toList();
        CollabMapper.ListingContext context = listingContext(listings, userId);
        return PageResponse.from(saved.map(value -> mapper.listing(value.getListing(), context)));
    }

    @Transactional(noRollbackFor = CollabExpiredException.class)
    public CollabApplicationResponse apply(UUID userId, UUID listingId, CollabApplicationCreateRequest request) {
        lockUser(userId);
        String phone = normalizePhone(request.phoneNumber());
        String message = normalizeOptional(request.message());
        String payloadHash = CollabPayloadHasher.hash(listingId, request.applicantActorId(), phone, message);
        Optional<CollabApplication> replay = applicationRepository
                .findByApplicantUserIdAndClientRequestId(userId, request.clientRequestId());
        if (replay.isPresent()) {
            assertPayload(replay.get().getRequestPayloadHash(), payloadHash);
            return mapApplication(replay.get(), userId);
        }

        CollabActor applicantActor = actorService.requireOwned(userId, request.applicantActorId());
        Collab listing = lockListing(listingId);
        requireOpen(listing);
        if (Objects.equals(listing.getOwner().getId(), userId)
                || Objects.equals(listing.getPublisherActor().getId(), applicantActor.getId())) {
            throw new SoundConnectException(ErrorType.COLLAB_SELF_APPLICATION);
        }
        if (!Objects.equals(applicantActor.getProfileType().name(), listing.getWantedType().name())) {
            throw new SoundConnectException(ErrorType.COLLAB_INVALID_ACTOR);
        }
        if (applicationRepository.existsByListingIdAndApplicantUserId(listingId, userId)
                || applicationRepository.existsByListingIdAndApplicantActorId(listingId, applicantActor.getId())) {
            throw new SoundConnectException(ErrorType.COLLAB_APPLICATION_DUPLICATE);
        }
        Instant now = timeProvider.now();
        CollabApplication application = CollabApplication.builder()
                .listing(listing).applicantActor(applicantActor).applicantUser(userFinder.getUser(userId))
                .clientRequestId(request.clientRequestId()).requestPayloadHash(payloadHash)
                .phoneSnapshot(phone).message(message).status(CollabApplicationStatus.PENDING)
                .submittedAt(now).statusChangedAt(now).build();
        applicationRepository.saveAndFlush(application);
        publish(listing.getOwner().getId(), NotificationType.COLLAB_APPLICATION_RECEIVED,
                "Yeni başvuru", applicantActor.getDisplayName() + " ilanına başvurdu.",
                "APPLICATION_RECEIVED", Map.of("listingId", listingId, "applicationId", application.getId()), now);
        return mapApplication(application, userId);
    }

    @Transactional
    public PageResponse<CollabApplicationResponse> incoming(UUID userId, UUID listingId,
                                                            CollabApplicationStatus status, int page, int size) {
        Collab listing = lockListing(listingId);
        requireOwner(listing, userId);
        if (listing.getStatus() == CollabListingStatus.OPEN && isDue(listing, timeProvider.now())) {
            expireLocked(listing, timeProvider.now());
        }
        Pageable pageable = page(page, size, Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("id")));
        Page<CollabApplication> result = status == null
                ? applicationRepository.findByListingId(listingId, pageable)
                : applicationRepository.findByListingIdAndStatus(listingId, status, pageable);
        CollabMapper.ListingContext context = listingContext(
                result.getContent().stream().map(CollabApplication::getListing).toList(), userId);
        return PageResponse.from(result.map(value -> mapper.application(value, userId, context)));
    }

    @Transactional
    public PageResponse<CollabApplicationResponse> applicationsMine(UUID userId, CollabApplicationStatus status,
                                                                    int page, int size) {
        reconcileApplicantDue(userId);
        Pageable pageable = page(page, size, Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("id")));
        Page<CollabApplication> result = status == null
                ? applicationRepository.findByApplicantUserId(userId, pageable)
                : applicationRepository.findByApplicantUserIdAndStatus(userId, status, pageable);
        CollabMapper.ListingContext context = listingContext(
                result.getContent().stream().map(CollabApplication::getListing).toList(), userId);
        return PageResponse.from(result.map(value -> mapper.application(value, userId, context)));
    }

    @Transactional(noRollbackFor = CollabExpiredException.class)
    public CollabJobResponse accept(UUID userId, UUID applicationId, ExpectedVersionRequest request) {
        UUID listingId = applicationRepository.findListingId(applicationId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_APPLICATION_NOT_FOUND));
        Collab listing = lockListing(listingId);
        requireOwner(listing, userId);
        CollabApplication application = lockApplication(applicationId);
        if (application.getStatus() == CollabApplicationStatus.ACCEPTED) {
            CollabJob existing = jobRepository.findByApplicationId(applicationId)
                    .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_JOB_NOT_FOUND));
            return mapJob(existing, userId);
        }
        requireOpen(listing);
        if (application.getStatus() != CollabApplicationStatus.PENDING) {
            throw new SoundConnectException(ErrorType.COLLAB_APPLICATION_STATUS_INVALID);
        }
        assertVersion(application.getVersion(), request.expectedVersion());
        Instant now = timeProvider.now();
        List<CollabApplication> invalidated = pendingApplications(listingId).stream()
                .filter(value -> !Objects.equals(value.getId(), applicationId)).toList();
        application.setStatus(CollabApplicationStatus.ACCEPTED);
        application.setStatusChangedAt(now);
        application.setDecidedAt(now);
        closeLocked(listing, CollabClosureReason.MATCHED, now);
        applicationRepository.flush();
        invalidatePending(listingId, applicationId, now);

        CollabJob job = CollabJob.builder()
                .listing(listing).application(application)
                .publisherActor(listing.getPublisherActor()).applicantActor(application.getApplicantActor())
                .publisherUser(listing.getOwner()).applicantUser(application.getApplicantUser())
                .status(CollabJobStatus.ACTIVE).build();
        jobRepository.saveAndFlush(job);
        publish(application.getApplicantUser().getId(), NotificationType.COLLAB_APPLICATION_ACCEPTED,
                "Başvurun kabul edildi", listing.getTitle() + " ilanı için eşleşme oluştu.",
                "APPLICATION_ACCEPTED", Map.of("listingId", listingId, "applicationId", applicationId, "jobId", job.getId()), now);
        invalidated.forEach(value -> publish(value.getApplicantUser().getId(),
                NotificationType.COLLAB_APPLICATION_INVALIDATED, "Başvuru geçersizleşti",
                "İlan başka bir başvuruyla eşleşti.", "APPLICATION_INVALIDATED",
                Map.of("listingId", listingId, "applicationId", value.getId()), now));
        return mapJob(job, userId);
    }

    @Transactional(noRollbackFor = CollabExpiredException.class)
    public CollabApplicationResponse reject(UUID userId, UUID applicationId, ExpectedVersionRequest request) {
        UUID listingId = applicationRepository.findListingId(applicationId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_APPLICATION_NOT_FOUND));
        Collab listing = lockListing(listingId);
        requireOwner(listing, userId);
        CollabApplication application = lockApplication(applicationId);
        if (application.getStatus() == CollabApplicationStatus.REJECTED) return mapApplication(application, userId);
        requireOpen(listing);
        if (application.getStatus() != CollabApplicationStatus.PENDING) {
            throw new SoundConnectException(ErrorType.COLLAB_APPLICATION_STATUS_INVALID);
        }
        assertVersion(application.getVersion(), request.expectedVersion());
        Instant now = timeProvider.now();
        application.setStatus(CollabApplicationStatus.REJECTED);
        application.setStatusChangedAt(now);
        application.setDecidedAt(now);
        applicationRepository.flush();
        publish(application.getApplicantUser().getId(), NotificationType.COLLAB_APPLICATION_REJECTED,
                "Başvurun reddedildi", listing.getTitle() + " ilanındaki başvurun reddedildi.",
                "APPLICATION_REJECTED", Map.of("listingId", listingId, "applicationId", applicationId), now);
        return mapApplication(application, userId);
    }

    @Transactional(noRollbackFor = CollabExpiredException.class)
    public CollabApplicationResponse withdraw(UUID userId, UUID applicationId, ExpectedVersionRequest request) {
        UUID listingId = applicationRepository.findListingId(applicationId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_APPLICATION_NOT_FOUND));
        Collab listing = lockListing(listingId);
        CollabApplication application = lockApplication(applicationId);
        if (!Objects.equals(application.getApplicantUser().getId(), userId)) {
            throw new SoundConnectException(ErrorType.COLLAB_FORBIDDEN);
        }
        if (application.getStatus() == CollabApplicationStatus.WITHDRAWN_BY_APPLICANT) {
            return mapApplication(application, userId);
        }
        requireOpen(listing);
        if (application.getStatus() != CollabApplicationStatus.PENDING) {
            throw new SoundConnectException(ErrorType.COLLAB_APPLICATION_STATUS_INVALID);
        }
        assertVersion(application.getVersion(), request.expectedVersion());
        Instant now = timeProvider.now();
        application.setStatus(CollabApplicationStatus.WITHDRAWN_BY_APPLICANT);
        application.setStatusChangedAt(now);
        applicationRepository.flush();
        publish(listing.getOwner().getId(), NotificationType.COLLAB_APPLICATION_WITHDRAWN,
                "Başvuru geri çekildi", application.getApplicantActor().getDisplayName() + " başvurusunu geri çekti.",
                "APPLICATION_WITHDRAWN", Map.of("listingId", listingId, "applicationId", applicationId), now);
        return mapApplication(application, userId);
    }

    @Transactional(readOnly = true)
    public PageResponse<CollabJobResponse> jobsMine(UUID userId, CollabJobStatus status, int page, int size) {
        Pageable pageable = page(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<CollabJob> result = status == null
                ? jobRepository.findMine(userId, pageable)
                : jobRepository.findMineByStatus(userId, status, pageable);
        Set<UUID> jobIds = result.getContent().stream().map(CollabJob::getId).collect(Collectors.toSet());
        Set<UUID> reviewed = jobIds.isEmpty() ? Set.of() : reviewRepository.findReviewedJobIds(userId, jobIds);
        CollabMapper.ListingContext context = listingContext(
                result.getContent().stream().map(CollabJob::getListing).toList(), userId);
        return PageResponse.from(result.map(value -> mapper.job(value, userId, reviewed.contains(value.getId()), context)));
    }

    @Transactional
    public CollabJobResponse confirmCompletion(UUID userId, UUID jobId, ExpectedVersionRequest request) {
        CollabJob job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_JOB_NOT_FOUND));
        boolean publisherSide = Objects.equals(job.getPublisherUser().getId(), userId);
        boolean applicantSide = Objects.equals(job.getApplicantUser().getId(), userId);
        if (!publisherSide && !applicantSide) throw new SoundConnectException(ErrorType.COLLAB_FORBIDDEN);
        if (job.getStatus() == CollabJobStatus.COMPLETED
                || (publisherSide && job.getPublisherConfirmedAt() != null)
                || (applicantSide && job.getApplicantConfirmedAt() != null)) {
            return mapJob(job, userId);
        }
        if (job.getStatus() != CollabJobStatus.ACTIVE) {
            throw new SoundConnectException(ErrorType.COLLAB_JOB_STATUS_INVALID);
        }
        assertVersion(job.getVersion(), request.expectedVersion());
        Instant now = timeProvider.now();
        if (publisherSide) job.setPublisherConfirmedAt(now); else job.setApplicantConfirmedAt(now);

        if (job.getPublisherConfirmedAt() != null && job.getApplicantConfirmedAt() != null) {
            if (Objects.equals(job.getPublisherActor().getId(), job.getApplicantActor().getId())) {
                throw new SoundConnectException(ErrorType.COLLAB_JOB_STATUS_INVALID);
            }
            List<UUID> actorIds = List.of(job.getPublisherActor().getId(), job.getApplicantActor().getId()).stream()
                    .sorted(Comparator.comparing(UUID::toString)).toList();
            CollabActor first = actorRepository.findByIdForUpdate(actorIds.get(0))
                    .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_ACTOR_NOT_FOUND));
            CollabActor second = actorRepository.findByIdForUpdate(actorIds.get(1))
                    .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_ACTOR_NOT_FOUND));
            first.recordCompletedJob();
            second.recordCompletedJob();
            job.setStatus(CollabJobStatus.COMPLETED);
            job.setCompletedAt(now);
            publish(job.getPublisherUser().getId(), NotificationType.COLLAB_JOB_COMPLETED,
                    "İş tamamlandı", job.getListing().getTitle() + " işi tamamlandı.", "JOB_COMPLETED",
                    Map.of("listingId", job.getListing().getId(), "jobId", jobId), now);
            publish(job.getApplicantUser().getId(), NotificationType.COLLAB_JOB_COMPLETED,
                    "İş tamamlandı", job.getListing().getTitle() + " işi tamamlandı.", "JOB_COMPLETED",
                    Map.of("listingId", job.getListing().getId(), "jobId", jobId), now);
        } else {
            UUID recipientId = publisherSide ? job.getApplicantUser().getId() : job.getPublisherUser().getId();
            publish(recipientId, NotificationType.COLLAB_JOB_COMPLETION_REQUESTED,
                    "Tamamlama onayı bekleniyor", job.getListing().getTitle() + " işi için onayın bekleniyor.",
                    "JOB_COMPLETION_REQUESTED", Map.of("listingId", job.getListing().getId(), "jobId", jobId), now);
        }
        jobRepository.flush();
        return mapJob(job, userId);
    }

    @Transactional
    public CollabReviewResponse review(UUID userId, UUID jobId, CollabReviewCreateRequest request) {
        lockUser(userId);
        String comment = normalizeOptional(request.comment());
        String payloadHash = CollabPayloadHasher.hash(jobId, request.rating(), comment);
        Optional<CollabReview> replay = reviewRepository
                .findByReviewerUserIdAndClientRequestId(userId, request.clientRequestId());
        if (replay.isPresent()) {
            assertPayload(replay.get().getRequestPayloadHash(), payloadHash);
            return mapper.review(replay.get());
        }
        CollabJob job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_JOB_NOT_FOUND));
        if (job.getStatus() != CollabJobStatus.COMPLETED) {
            throw new SoundConnectException(ErrorType.COLLAB_JOB_STATUS_INVALID);
        }
        boolean publisherSide = Objects.equals(job.getPublisherUser().getId(), userId);
        boolean applicantSide = Objects.equals(job.getApplicantUser().getId(), userId);
        if (!publisherSide && !applicantSide) throw new SoundConnectException(ErrorType.COLLAB_REVIEW_NOT_ALLOWED);
        CollabActor reviewerActor = publisherSide ? job.getPublisherActor() : job.getApplicantActor();
        CollabActor targetActor = publisherSide ? job.getApplicantActor() : job.getPublisherActor();
        User targetUser = publisherSide ? job.getApplicantUser() : job.getPublisherUser();
        Optional<CollabReview> existing = reviewRepository.findByJobIdAndReviewerActorId(jobId, reviewerActor.getId());
        if (existing.isPresent()) {
            if (existing.get().getRating() == request.rating()
                    && Objects.equals(existing.get().getComment(), comment)) return mapper.review(existing.get());
            throw new SoundConnectException(ErrorType.COLLAB_REVIEW_DUPLICATE);
        }
        CollabActor lockedTarget = actorRepository.findByIdForUpdate(targetActor.getId())
                .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_ACTOR_NOT_FOUND));
        lockedTarget.recordReview(request.rating());
        Instant now = timeProvider.now();
        CollabReview review = CollabReview.builder()
                .job(job).reviewerActor(reviewerActor).targetActor(lockedTarget).reviewerUser(userFinder.getUser(userId))
                .clientRequestId(request.clientRequestId()).requestPayloadHash(payloadHash)
                .rating(request.rating()).comment(comment).submittedAt(now).build();
        reviewRepository.saveAndFlush(review);
        publish(targetUser.getId(), NotificationType.COLLAB_REVIEW_RECEIVED,
                "Yeni değerlendirme", "Tamamlanan işin için yeni bir değerlendirme aldın.",
                "REVIEW_RECEIVED", Map.of("listingId", job.getListing().getId(), "jobId", jobId, "reviewId", review.getId()), now);
        return mapper.review(review);
    }

    @Transactional(readOnly = true)
    public PageResponse<CollabReviewResponse> actorReviews(UUID actorId, int page, int size) {
        if (!actorRepository.existsById(actorId)) throw new SoundConnectException(ErrorType.COLLAB_ACTOR_NOT_FOUND);
        Pageable pageable = page(page, size, Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("id")));
        return PageResponse.from(reviewRepository.findByTargetActorId(actorId, pageable).map(mapper::review));
    }

    @Transactional
    public CollabReportResponse report(UUID userId, UUID listingId, CollabReportCreateRequest request) {
        lockUser(userId);
        String details = normalizeOptional(request.details());
        if (request.reason() == CollabReportReason.OTHER && details == null) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        String payloadHash = CollabPayloadHasher.hash(listingId, request.reason(), details);
        Optional<CollabReport> replay = reportRepository
                .findByReporterUserIdAndClientRequestId(userId, request.clientRequestId());
        if (replay.isPresent()) {
            assertPayload(replay.get().getRequestPayloadHash(), payloadHash);
            return mapReport(replay.get());
        }
        Optional<CollabReport> duplicate = reportRepository.findByReporterUserIdAndListingId(userId, listingId);
        if (duplicate.isPresent()) {
            if (Objects.equals(duplicate.get().getReason(), request.reason())
                    && Objects.equals(duplicate.get().getDetails(), details)) return mapReport(duplicate.get());
            throw new SoundConnectException(ErrorType.COLLAB_REPORT_DUPLICATE);
        }
        Collab listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_NOT_FOUND));
        if (Objects.equals(listing.getOwner().getId(), userId)) {
            throw new SoundConnectException(ErrorType.COLLAB_SELF_REPORT);
        }
        if (!isVisibleToNonOwner(listing, userId)) {
            throw new SoundConnectException(ErrorType.COLLAB_NOT_FOUND);
        }
        Instant now = timeProvider.now();
        CollabReport value = CollabReport.builder().listing(listing).reporterUser(userFinder.getUser(userId))
                .clientRequestId(request.clientRequestId()).requestPayloadHash(payloadHash)
                .reason(request.reason()).details(details).reportedAt(now).build();
        reportRepository.saveAndFlush(value);
        return mapReport(value);
    }

    @Transactional
    public int expireDueBatch(int batchSize) {
        int safeBatch = Math.max(1, Math.min(batchSize, 500));
        Instant now = timeProvider.now();
        List<UUID> ids = listingRepository.findDueIds(CollabListingStatus.OPEN, now, PageRequest.of(0, safeBatch));
        int expired = 0;
        for (UUID id : ids) {
            Collab listing = listingRepository.findByIdForUpdate(id).orElse(null);
            if (listing != null && listing.getStatus() == CollabListingStatus.OPEN && isDue(listing, now)) {
                expireLocked(listing, now);
                expired++;
            }
        }
        return expired;
    }

    private void reconcileOwnedDue(UUID userId) {
        // Scheduler is reconciliation only; read paths materialize every due row in their scope.
        Instant now = timeProvider.now();
        List<UUID> ids;
        do {
            ids = listingRepository.findDueIdsByOwner(userId, CollabListingStatus.OPEN, now, PageRequest.of(0, 100));
            ids.forEach(id -> {
                    Collab locked = lockListing(id);
                    if (locked.getStatus() == CollabListingStatus.OPEN && isDue(locked, now)) {
                        expireLocked(locked, now);
                    }
                });
        } while (!ids.isEmpty());
    }

    private void reconcileApplicantDue(UUID userId) {
        Instant now = timeProvider.now();
        List<UUID> ids;
        do {
            ids = applicationRepository.findDueListingIdsForApplicant(userId, CollabApplicationStatus.PENDING,
                    CollabListingStatus.OPEN, now, PageRequest.of(0, 100));
            ids.forEach(id -> {
                    Collab locked = lockListing(id);
                    if (locked.getStatus() == CollabListingStatus.OPEN && isDue(locked, now)) {
                        expireLocked(locked, now);
                    }
                });
        } while (!ids.isEmpty());
    }

    private void expireLocked(Collab listing, Instant now) {
        if (listing.getStatus() != CollabListingStatus.OPEN || !isDue(listing, now)) return;
        List<CollabApplication> invalidated = pendingApplications(listing.getId());
        listing.setStatus(CollabListingStatus.EXPIRED);
        listing.setClosureReason(CollabClosureReason.EXPIRED);
        listing.setClosedAt(now);
        invalidatePending(listing.getId(), null, now);
        savedRepository.deleteByListingId(listing.getId());
        publish(listing.getOwner().getId(), NotificationType.COLLAB_LISTING_EXPIRED,
                "İlanın süresi doldu", listing.getTitle() + " ilanı sona erdi.", "LISTING_EXPIRED",
                Map.of("listingId", listing.getId()), now);
        invalidated.forEach(value -> publish(value.getApplicantUser().getId(),
                NotificationType.COLLAB_APPLICATION_INVALIDATED, "Başvuru geçersizleşti",
                "İlanın süresi doldu.", "APPLICATION_INVALIDATED",
                Map.of("listingId", listing.getId(), "applicationId", value.getId()), now));
    }

    private void requireOpen(Collab listing) {
        if (listing.getStatus() == CollabListingStatus.OPEN && isDue(listing, timeProvider.now())) {
            expireLocked(listing, timeProvider.now());
            throw new CollabExpiredException();
        }
        if (listing.getStatus() != CollabListingStatus.OPEN) {
            if (listing.getStatus() == CollabListingStatus.EXPIRED) throw new CollabExpiredException();
            throw new SoundConnectException(ErrorType.COLLAB_LIFECYCLE_INVALID);
        }
    }

    private void closeLocked(Collab listing, CollabClosureReason reason, Instant now) {
        listing.setStatus(CollabListingStatus.CLOSED);
        listing.setClosureReason(reason);
        listing.setClosedAt(now);
        savedRepository.deleteByListingId(listing.getId());
    }

    private List<CollabApplication> pendingApplications(UUID listingId) {
        return applicationRepository.findByListingIdAndStatus(listingId, CollabApplicationStatus.PENDING);
    }

    private void invalidatePending(UUID listingId, UUID exceptId, Instant now) {
        applicationRepository.invalidatePending(listingId, exceptId, CollabApplicationStatus.PENDING,
                CollabApplicationStatus.INVALIDATED_BY_LISTING_CLOSURE, now);
    }

    private boolean isDue(Collab listing, Instant now) {
        return listing.getExpiresAt() != null && !listing.getExpiresAt().isAfter(now);
    }

    private boolean isVisibleToNonOwner(Collab listing, UUID userId) {
        if (listing.getStatus() == CollabListingStatus.OPEN) {
            return !isDue(listing, timeProvider.now());
        }
        if (listing.getStatus() == CollabListingStatus.DRAFT) return false;
        return applicationRepository.existsByListingIdAndApplicantUserId(listing.getId(), userId);
    }

    private Collab lockListing(UUID id) {
        return listingRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_NOT_FOUND));
    }

    private User lockUser(UUID id) {
        return userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
    }

    private CollabApplication lockApplication(UUID id) {
        return applicationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_APPLICATION_NOT_FOUND));
    }

    private void requireOwner(Collab listing, UUID userId) {
        if (!Objects.equals(listing.getOwner().getId(), userId)) throw new SoundConnectException(ErrorType.COLLAB_FORBIDDEN);
    }

    private void assertVersion(long actual, long expected) {
        if (actual != expected) throw new SoundConnectException(ErrorType.COLLAB_STALE_UPDATE);
    }

    private void assertPayload(String actual, String expected) {
        if (!Objects.equals(actual, expected)) throw new SoundConnectException(ErrorType.COLLAB_IDEMPOTENCY_CONFLICT);
    }

    private Pageable page(int page, int size, Sort sort) {
        if (page < 0 || page > MAX_PAGE_NUMBER || size < 1 || size > MAX_PAGE_SIZE) {
            throw new SoundConnectException(ErrorType.COLLAB_PAGE_REQUEST_INVALID);
        }
        return PageRequest.of(page, size, sort);
    }

    private void validateFilter(CollabFilterRequest filter) {
        if (filter == null || filter.publisherTypes() == null) return;
        if (!CollabActorService.ALLOWED_TYPES.containsAll(filter.publisherTypes())) {
            throw new SoundConnectException(ErrorType.COLLAB_INVALID_ACTOR);
        }
    }

    private CollabListingResponse mapSingle(Collab listing, UUID viewerId) {
        return mapper.listing(listing, listingContext(List.of(listing), viewerId));
    }

    private CollabApplicationResponse mapApplication(CollabApplication application, UUID viewerId) {
        return mapper.application(application, viewerId,
                listingContext(List.of(application.getListing()), viewerId));
    }

    private CollabJobResponse mapJob(CollabJob job, UUID viewerId) {
        boolean reviewed = reviewRepository.findReviewedJobIds(viewerId, Set.of(job.getId())).contains(job.getId());
        return mapper.job(job, viewerId, reviewed, listingContext(List.of(job.getListing()), viewerId));
    }

    private CollabMapper.ListingContext listingContext(Collection<Collab> listings, UUID viewerId) {
        Set<UUID> ids = listings.stream().map(Collab::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return CollabMapper.ListingContext.empty(viewerId);
        Set<UUID> applied = applicationRepository.findAppliedListingIds(viewerId, ids);
        Set<UUID> saved = savedRepository.findSavedListingIds(viewerId, ids);
        Map<UUID, Long> counts = applicationRepository.countByListingIds(ids).stream()
                .collect(Collectors.toMap(CollabListingApplicationCount::getListingId,
                        CollabListingApplicationCount::getApplicationCount));
        return new CollabMapper.ListingContext(viewerId, applied, saved, counts);
    }

    private CollabReportResponse mapReport(CollabReport value) {
        return new CollabReportResponse(value.getId(), value.getListing().getId(), value.getReason(),
                value.getDetails(), value.getReportedAt());
    }

    private NormalizedListing normalizeListing(CollabCadence cadence, CollabWantedType wantedType,
                                                UUID instrumentId, CollabBranch branch, String customSpecialty,
                                                String title, String description, UUID cityId, List<String> genres,
                                                Instant scheduledAt, Long feeAmountMinor, String currency,
                                                ProfileType publisherType, Instant now) {
        String normalizedTitle = title == null ? null : title.strip();
        String normalizedDescription = description == null ? null : description.strip();
        if (normalizedTitle == null || normalizedTitle.length() < 5 || normalizedTitle.length() > 100
                || normalizedDescription == null || normalizedDescription.length() < 20
                || normalizedDescription.length() > 500) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        List<String> normalizedGenres = normalizeGenres(genres);
        String custom = normalizeOptional(customSpecialty);
        if (custom != null && custom.length() > 80) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        validateSpecialty(wantedType, instrumentId, branch, custom);
        Instrument instrument = instrumentId == null ? null : instrumentFinder.getInstrument(instrumentId);
        City city = locationFinder.getCity(cityId);
        validateCadence(cadence, scheduledAt, now);
        Money money = normalizeMoney(cadence, publisherType, feeAmountMinor, currency);
        return new NormalizedListing(cadence, wantedType, instrument, branch, custom,
                normalizedTitle, normalizedDescription, city, normalizedGenres, scheduledAt,
                money.amountMinor(), money.currency());
    }

    private void validateSpecialty(CollabWantedType wantedType, UUID instrumentId,
                                   CollabBranch branch, String customSpecialty) {
        if (wantedType == CollabWantedType.MUSICIAN) {
            if ((instrumentId == null) == (branch == null)) {
                throw new SoundConnectException(ErrorType.COLLAB_SPECIALTY_INVALID);
            }
            if (branch == CollabBranch.OTHER && customSpecialty == null) {
                throw new SoundConnectException(ErrorType.COLLAB_SPECIALTY_INVALID);
            }
            if (branch != CollabBranch.OTHER && customSpecialty != null) {
                throw new SoundConnectException(ErrorType.COLLAB_SPECIALTY_INVALID);
            }
        } else if (instrumentId != null || branch != null || customSpecialty != null) {
            throw new SoundConnectException(ErrorType.COLLAB_SPECIALTY_INVALID);
        }
    }

    private void validateCadence(CollabCadence cadence, Instant scheduledAt, Instant now) {
        if (cadence == CollabCadence.REGULAR) {
            if (scheduledAt != null) throw new SoundConnectException(ErrorType.COLLAB_CADENCE_FIELDS_INVALID);
            return;
        }
        if (cadence != CollabCadence.EXTRA || scheduledAt == null || !scheduledAt.isAfter(now)
                || scheduledAt.isAfter(now.plus(Duration.ofDays(7)))) {
            throw new SoundConnectException(ErrorType.COLLAB_CADENCE_FIELDS_INVALID);
        }
    }

    private Money normalizeMoney(CollabCadence cadence, ProfileType publisherType,
                                 Long amountMinor, String currency) {
        if (amountMinor == null) {
            if (currency != null && !currency.isBlank()) throw new SoundConnectException(ErrorType.COLLAB_FEE_INVALID);
            return new Money(null, null);
        }
        if (amountMinor < 1 || amountMinor > MAX_FEE_MINOR) {
            throw new SoundConnectException(ErrorType.COLLAB_FEE_INVALID);
        }
        if (cadence == CollabCadence.REGULAR && publisherType != ProfileType.VENUE) {
            throw new SoundConnectException(ErrorType.COLLAB_FEE_INVALID);
        }
        String normalizedCurrency = currency == null || currency.isBlank() ? "TRY" : currency.strip().toUpperCase(Locale.ROOT);
        if (!"TRY".equals(normalizedCurrency)) throw new SoundConnectException(ErrorType.COLLAB_FEE_INVALID);
        return new Money(amountMinor, normalizedCurrency);
    }

    private List<String> normalizeGenres(List<String> genres) {
        if (genres == null || genres.isEmpty()) return List.of();
        Map<String, String> distinct = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String value : genres) {
            if (value == null || value.isBlank()) throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
            String normalized = value.strip();
            if (normalized.length() > 40) throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
            distinct.putIfAbsent(normalized, normalized);
        }
        if (distinct.size() > 3) throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        return List.copyOf(distinct.values());
    }

    private void applyNormalized(Collab listing, NormalizedListing value, boolean published) {
        listing.setCadence(value.cadence());
        listing.setWantedType(value.wantedType());
        listing.setInstrument(value.instrument());
        listing.setBranch(value.branch());
        listing.setCustomSpecialty(value.customSpecialty());
        listing.setTitle(value.title());
        listing.setDescription(value.description());
        listing.setCity(value.city());
        listing.setGenres(new ArrayList<>(value.genres()));
        listing.setScheduledAt(value.scheduledAt());
        listing.setFeeAmountMinor(value.feeAmountMinor());
        listing.setCurrency(value.currency());
        listing.setExpiresAt(value.cadence() == CollabCadence.EXTRA ? value.scheduledAt() : null);
    }

    private void assertPublishedImmutableFields(Collab listing, CollabActor publisher, NormalizedListing next) {
        if (!Objects.equals(listing.getPublisherActor().getId(), publisher.getId())
                || listing.getCadence() != next.cadence()
                || listing.getWantedType() != next.wantedType()
                || !Objects.equals(idOf(listing.getInstrument()), idOf(next.instrument()))
                || listing.getBranch() != next.branch()
                || !Objects.equals(listing.getCustomSpecialty(), next.customSpecialty())
                || !Objects.equals(listing.getCity().getId(), next.city().getId())
                || !Objects.equals(listing.getScheduledAt(), next.scheduledAt())) {
            throw new SoundConnectException(ErrorType.COLLAB_PUBLISHED_EDIT_RESTRICTED);
        }
    }

    private UUID idOf(Instrument value) { return value == null ? null : value.getId(); }

    private void validateStoredForPublish(Collab listing, Instant now) {
        validateSpecialty(listing.getWantedType(), idOf(listing.getInstrument()), listing.getBranch(), listing.getCustomSpecialty());
        validateCadence(listing.getCadence(), listing.getScheduledAt(), now);
        normalizeMoney(listing.getCadence(), listing.getPublisherActor().getProfileType(),
                listing.getFeeAmountMinor(), listing.getCurrency());
    }

    private String creationHash(UUID requestId, UUID publisherActorId, NormalizedListing value) {
        return CollabPayloadHasher.hash(requestId, publisherActorId, value.cadence(), value.wantedType(),
                idOf(value.instrument()), value.branch(), value.customSpecialty(), value.title(), value.description(),
                value.city().getId(), value.genres(), value.scheduledAt(), value.feeAmountMinor(), value.currency());
    }

    private String normalizePhone(String value) {
        String stripped = value == null ? "" : value.strip();
        if (!stripped.matches("[+0-9() .-]+")) throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        boolean plus = stripped.startsWith("+");
        String digits = stripped.replaceAll("\\D", "");
        if (digits.length() < 7 || digits.length() > 15) throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        return plus ? "+" + digits : digits;
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void publish(UUID recipientId, NotificationType type, String title, String message,
                         String action, Map<String, ?> attributes, Instant occurredAt) {
        eventPublisher.publishEvent(CollabNotificationEvent.create(
                recipientId, type, title, message, action, attributes, occurredAt));
    }

    private record Money(Long amountMinor, String currency) {}

    private record NormalizedListing(CollabCadence cadence, CollabWantedType wantedType,
                                     Instrument instrument, CollabBranch branch, String customSpecialty,
                                     String title, String description, City city, List<String> genres,
                                     Instant scheduledAt, Long feeAmountMinor, String currency) {}
}
