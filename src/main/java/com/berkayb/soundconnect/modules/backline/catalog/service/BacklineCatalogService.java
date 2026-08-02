package com.berkayb.soundconnect.modules.backline.catalog.service;

import com.berkayb.soundconnect.modules.backline.catalog.dto.BacklineCategoryChildResponse;
import com.berkayb.soundconnect.modules.backline.catalog.dto.BacklineCategoryRequestChildResponse;
import com.berkayb.soundconnect.modules.backline.catalog.dto.BacklineCategoryRequestCreateRequest;
import com.berkayb.soundconnect.modules.backline.catalog.dto.BacklineCategoryRequestResponse;
import com.berkayb.soundconnect.modules.backline.catalog.dto.BacklineCategoryReviewRequest;
import com.berkayb.soundconnect.modules.backline.catalog.dto.BacklineCategoryTreeResponse;
import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategory;
import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategoryRequest;
import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategoryRequest.CategoryCandidate;
import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategoryRequestChild;
import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryRequestStatus;
import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryRequestType;
import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryReviewDecision;
import com.berkayb.soundconnect.modules.backline.catalog.repository.BacklineCategoryRepository;
import com.berkayb.soundconnect.modules.backline.catalog.repository.BacklineCategoryRequestRepository;
import com.berkayb.soundconnect.modules.backline.catalog.support.BacklineCatalogTimeProvider;
import com.berkayb.soundconnect.modules.backline.catalog.support.BacklineCategoryNames;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BacklineCatalogService {
    private static final int MAX_REQUEST_PAGE_SIZE = 50;
    private static final int MAX_CATALOG_PAGE_SIZE = 100;

    private final BacklineCategoryRepository categoryRepository;
    private final BacklineCategoryRequestRepository requestRepository;
    private final StudioProfileRepository studioProfileRepository;
    private final BacklineCategoryNames categoryNames;
    private final BacklineCatalogTimeProvider timeProvider;

    @Transactional(readOnly = true)
    public Page<BacklineCategoryTreeResponse> listPublicCategories(int page, int size) {
        Page<BacklineCategory> roots = categoryRepository.findByParentIsNullAndActiveTrue(
                PageRequest.of(
                        Math.max(page, 0),
                        boundedSize(size, MAX_CATALOG_PAGE_SIZE),
                        Sort.by(
                                Sort.Order.asc("sortOrder"),
                                Sort.Order.asc("name").ignoreCase(),
                                Sort.Order.asc("id")
                        )
                )
        );

        List<UUID> rootIds = roots.getContent().stream().map(BacklineCategory::getId).toList();
        Map<UUID, List<BacklineCategoryChildResponse>> childrenByRoot = new HashMap<>();
        if (!rootIds.isEmpty()) {
            for (BacklineCategory child : categoryRepository.findActiveChildren(rootIds)) {
                childrenByRoot.computeIfAbsent(child.getParent().getId(), ignored -> new ArrayList<>())
                        .add(new BacklineCategoryChildResponse(
                                child.getId(),
                                child.getCode(),
                                child.getName(),
                                child.getIconKey(),
                                child.getSortOrder()
                        ));
            }
        }

        return roots.map(root -> new BacklineCategoryTreeResponse(
                root.getId(),
                root.getCode(),
                root.getName(),
                root.getIconKey(),
                root.getSortOrder(),
                List.copyOf(childrenByRoot.getOrDefault(root.getId(), List.of()))
        ));
    }

    @Transactional
    public BacklineCategoryRequestResponse submitRequest(
            UUID actingUserId,
            BacklineCategoryRequestCreateRequest command
    ) {
        if (command == null || command.clientRequestId() == null || command.type() == null) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        StudioProfile studio = studioProfileRepository.findByUserIdForUpdate(actingUserId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
        String displayName = categoryNames.displayName(command.name());
        String normalizedName = categoryNames.normalizedName(displayName);
        String requesterNote = cleanRequesterNote(command.requesterNote());

        List<CategoryCandidate> children;
        if (command.type() == BacklineCategoryRequestType.ROOT_CATEGORY) {
            if (command.parentCategoryId() != null) {
                throw new SoundConnectException(ErrorType.BACKLINE_CATEGORY_INVALID, "Root requests cannot specify a parent");
            }
            children = normalizeChildren(command.proposedChildren());
        } else {
            if (command.parentCategoryId() == null) {
                throw new SoundConnectException(
                        ErrorType.BACKLINE_CATEGORY_INVALID,
                        "Subcategory requests require an active root category"
                );
            }
            if (command.proposedChildren() != null && !command.proposedChildren().isEmpty()) {
                throw new SoundConnectException(
                        ErrorType.BACKLINE_CATEGORY_INVALID,
                        "Subcategory requests cannot contain proposed children"
                );
            }
            children = List.of();
        }

        String payloadHash = categoryRequestPayloadHash(
                command.type(),
                normalizedName,
                command.parentCategoryId(),
                children,
                requesterNote
        );
        BacklineCategoryRequest replay = requestRepository
                .findByStudioProfileIdAndClientRequestId(studio.getId(), command.clientRequestId())
                .orElse(null);
        if (replay != null) {
            if (!payloadHash.equals(replay.getRequestPayloadHash())) {
                throw new SoundConnectException(
                        ErrorType.DATA_INTEGRITY_CONFLICT,
                        "clientRequestId was already used for a different category request payload"
                );
            }
            return toRequestResponse(replay);
        }

        BacklineCategory parent = null;
        if (command.type() == BacklineCategoryRequestType.ROOT_CATEGORY) {
            BacklineCategory existingRoot = categoryRepository
                    .findByParentIsNullAndNormalizedName(normalizedName)
                    .orElse(null);
            if (existingRoot != null && existingRoot.isActive()) {
                throw new SoundConnectException(ErrorType.BACKLINE_CATEGORY_INVALID, "This root category already exists");
            }
            if (requestRepository
                    .existsByStudioProfileIdAndTypeAndNormalizedRequestedNameAndStatusAndParentCategoryIsNull(
                            studio.getId(),
                            command.type(),
                            normalizedName,
                            BacklineCategoryRequestStatus.PENDING
                    )) {
                throw new SoundConnectException(ErrorType.BACKLINE_CATEGORY_REQUEST_DUPLICATE);
            }
        } else {
            parent = categoryRepository.findByIdAndActiveTrue(command.parentCategoryId())
                    .orElseThrow(() -> new SoundConnectException(ErrorType.BACKLINE_CATEGORY_NOT_FOUND));
            if (!parent.isRoot()) {
                throw new SoundConnectException(ErrorType.BACKLINE_CATEGORY_INVALID);
            }
            BacklineCategory existingChild = categoryRepository
                    .findByParentIdAndNormalizedName(parent.getId(), normalizedName)
                    .orElse(null);
            if (existingChild != null && existingChild.isActive()) {
                throw new SoundConnectException(ErrorType.BACKLINE_CATEGORY_INVALID, "This subcategory already exists");
            }
            if (requestRepository
                    .existsByStudioProfileIdAndTypeAndNormalizedRequestedNameAndStatusAndParentCategoryId(
                            studio.getId(),
                            command.type(),
                            normalizedName,
                            BacklineCategoryRequestStatus.PENDING,
                            parent.getId()
                    )) {
                throw new SoundConnectException(ErrorType.BACKLINE_CATEGORY_REQUEST_DUPLICATE);
            }
        }

        BacklineCategoryRequest request = BacklineCategoryRequest.create(
                studio,
                actingUserId,
                command.clientRequestId(),
                payloadHash,
                command.type(),
                displayName,
                normalizedName,
                requesterNote,
                parent,
                children
        );
        return toRequestResponse(requestRepository.saveAndFlush(request));
    }

    @Transactional(readOnly = true)
    public Page<BacklineCategoryRequestResponse> listOwnerRequests(UUID actingUserId, int page, int size) {
        StudioProfile studio = studioProfileRepository.findByUserId(actingUserId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
        return requestRepository.findByStudioProfileId(studio.getId(), requestPage(page, size))
                .map(this::toRequestResponse);
    }

    @Transactional
    public BacklineCategoryRequestResponse withdrawRequest(UUID actingUserId, UUID requestId) {
        BacklineCategoryRequest request = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.BACKLINE_CATEGORY_REQUEST_NOT_FOUND));
        if (!request.getStudioProfile().getUser().getId().equals(actingUserId)) {
            throw new SoundConnectException(ErrorType.STUDIO_RESOURCE_FORBIDDEN);
        }
        ensurePending(request);
        request.withdraw();
        return toRequestResponse(requestRepository.saveAndFlush(request));
    }

    @Transactional(readOnly = true)
    public Page<BacklineCategoryRequestResponse> listAdminRequests(
            BacklineCategoryRequestStatus status,
            int page,
            int size
    ) {
        PageRequest pageable = requestPage(page, size);
        Page<BacklineCategoryRequest> requests = status == null
                ? requestRepository.findAll(pageable)
                : requestRepository.findByStatus(status, pageable);
        return requests.map(this::toRequestResponse);
    }

    @Transactional
    public BacklineCategoryRequestResponse reviewRequest(
            UUID reviewerUserId,
            UUID requestId,
            BacklineCategoryReviewRequest command
    ) {
        if (command == null || command.decision() == null) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        BacklineCategoryRequest request = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.BACKLINE_CATEGORY_REQUEST_NOT_FOUND));
        ensurePending(request);
        String note = cleanOptional(command.note());

        if (command.decision() == BacklineCategoryReviewDecision.REJECT) {
            if (!StringUtils.hasText(note)) {
                throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "A rejection note is required");
            }
            request.reject(reviewerUserId, timeProvider.now(), note);
        } else {
            approve(request, reviewerUserId, note);
        }
        return toRequestResponse(requestRepository.saveAndFlush(request));
    }

    private void approve(BacklineCategoryRequest request, UUID reviewerUserId, String note) {
        if (request.getType() == BacklineCategoryRequestType.ROOT_CATEGORY) {
            BacklineCategory root = resolveRoot(
                    request.getRequestedName(),
                    request.getNormalizedRequestedName()
            );
            int nextChildSortOrder = categoryRepository.findMaximumChildSortOrder(root.getId()) + 1;
            for (BacklineCategoryRequestChild childRequest : request.getProposedChildren()) {
                BacklineCategory child = resolveChild(
                        root,
                        childRequest.getName(),
                        childRequest.getNormalizedName(),
                        nextChildSortOrder
                );
                if (child.getSortOrder() == nextChildSortOrder) {
                    nextChildSortOrder++;
                }
                childRequest.resolveTo(child);
            }
            request.approve(reviewerUserId, timeProvider.now(), note, root, root);
            return;
        }

        BacklineCategory root = request.getParentCategory();
        if (root == null || !root.isRoot() || !root.isActive()) {
            throw new SoundConnectException(ErrorType.BACKLINE_CATEGORY_INVALID);
        }
        BacklineCategory child = resolveChild(
                root,
                request.getRequestedName(),
                request.getNormalizedRequestedName(),
                categoryRepository.findMaximumChildSortOrder(root.getId()) + 1
        );
        request.approve(reviewerUserId, timeProvider.now(), note, root, child);
    }

    private BacklineCategory resolveRoot(String name, String normalizedName) {
        BacklineCategory existing = categoryRepository.findRootForUpdate(normalizedName).orElse(null);
        if (existing != null) {
            existing.activate();
            return categoryRepository.save(existing);
        }
        BacklineCategory root = BacklineCategory.createRoot(
                stableCode(null, normalizedName),
                name,
                normalizedName,
                "backline-category",
                categoryRepository.findMaximumRootSortOrder() + 1
        );
        return categoryRepository.saveAndFlush(root);
    }

    private BacklineCategory resolveChild(
            BacklineCategory root,
            String name,
            String normalizedName,
            int nextSortOrder
    ) {
        BacklineCategory existing = categoryRepository.findChildForUpdate(root.getId(), normalizedName).orElse(null);
        if (existing != null) {
            existing.activate();
            return categoryRepository.save(existing);
        }
        return categoryRepository.saveAndFlush(
                BacklineCategory.createChild(
                        root,
                        stableCode(root.getCode(), normalizedName),
                        name,
                        normalizedName,
                        nextSortOrder
                )
        );
    }

    private List<CategoryCandidate> normalizeChildren(List<String> rawChildren) {
        if (rawChildren == null || rawChildren.isEmpty()) {
            return List.of();
        }
        if (rawChildren.size() > 10) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "At most 10 proposed children are allowed");
        }
        List<CategoryCandidate> children = new ArrayList<>(rawChildren.size());
        Set<String> uniquenessKeys = new HashSet<>();
        for (String rawChild : rawChildren) {
            String displayName = categoryNames.displayName(rawChild);
            String normalizedName = categoryNames.normalizedName(displayName);
            if (!uniquenessKeys.add(normalizedName)) {
                throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "Proposed child names must be unique");
            }
            children.add(new CategoryCandidate(displayName, normalizedName));
        }
        return List.copyOf(children);
    }

    private void ensurePending(BacklineCategoryRequest request) {
        if (request.getStatus() != BacklineCategoryRequestStatus.PENDING) {
            throw new SoundConnectException(ErrorType.BACKLINE_CATEGORY_REQUEST_STATUS_INVALID);
        }
    }

    private BacklineCategoryRequestResponse toRequestResponse(BacklineCategoryRequest request) {
        List<BacklineCategoryRequestChildResponse> children = request.getProposedChildren().stream()
                .map(child -> new BacklineCategoryRequestChildResponse(
                        child.getName(),
                        child.getPosition(),
                        child.getResolvedCategory() == null ? null : child.getResolvedCategory().getId()
                ))
                .toList();
        return new BacklineCategoryRequestResponse(
                request.getId(),
                request.getClientRequestId(),
                request.getStudioProfile().getId(),
                request.getType(),
                request.getRequestedName(),
                request.getParentCategory() == null ? null : request.getParentCategory().getId(),
                request.getParentCategory() == null ? null : request.getParentCategory().getName(),
                children,
                request.getRequesterNote(),
                request.getStatus(),
                request.getResolvedRootCategory() == null ? null : request.getResolvedRootCategory().getId(),
                request.getResolvedCategory() == null ? null : request.getResolvedCategory().getId(),
                request.getReviewedByUserId(),
                request.getReviewedAt(),
                request.getDecisionNote(),
                request.getCreatedAt(),
                request.getCreatedAt() == null
                        ? null
                        : request.getCreatedAt().toInstant(ZoneOffset.UTC)
        );
    }

    private PageRequest requestPage(int page, int size) {
        return PageRequest.of(
                Math.max(page, 0),
                boundedSize(size, MAX_REQUEST_PAGE_SIZE),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        );
    }

    private int boundedSize(int requestedSize, int maximum) {
        return Math.max(1, Math.min(requestedSize, maximum));
    }

    private String cleanOptional(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String cleaned = value.strip().replaceAll("\\s+", " ");
        if (cleaned.length() > 500) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "Decision note cannot exceed 500 characters");
        }
        return cleaned;
    }

    private String cleanRequesterNote(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String cleaned = value.strip().replaceAll("\\s+", " ");
        if (cleaned.length() > 300) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "Requester note cannot exceed 300 characters");
        }
        return cleaned;
    }

    private String stableCode(String parentCode, String normalizedName) {
        String ascii = Normalizer.normalize(normalizedName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        String prefix = parentCode == null ? ascii : parentCode + "-" + ascii;
        if (prefix.isBlank()) {
            prefix = "category";
        }
        String namespace = (parentCode == null ? "root:" : parentCode + ":") + normalizedName;
        String suffix = UUID.nameUUIDFromBytes(namespace.getBytes(StandardCharsets.UTF_8))
                .toString()
                .substring(0, 8);
        int maximumPrefixLength = 96 - suffix.length() - 1;
        if (prefix.length() > maximumPrefixLength) {
            prefix = prefix.substring(0, maximumPrefixLength).replaceAll("-+$", "");
        }
        return prefix + "-" + suffix;
    }

    private String categoryRequestPayloadHash(
            BacklineCategoryRequestType type,
            String normalizedName,
            UUID parentCategoryId,
            List<CategoryCandidate> children,
            String requesterNote
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            appendHashValue(digest, type.name());
            appendHashValue(digest, normalizedName);
            appendHashValue(digest, parentCategoryId == null ? null : parentCategoryId.toString());
            appendHashValue(digest, Integer.toString(children.size()));
            children.forEach(child -> appendHashValue(digest, child.normalizedName()));
            appendHashValue(digest, requesterNote);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void appendHashValue(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
