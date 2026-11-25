package com.berkayb.soundconnect.modules.contentreport;

import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.comment.support.CommentEntityFinder;
import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostService;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class ContentReportTargets {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountDeliveryFence accounts;
    private final PublicProfileResolverService profiles;
    private final CommentTargetAccessGuard targetAccess;
    private final CommentRepository comments;
    private final CommentEntityFinder commentFinder;
    private final OverthinkingPostRepository posts;
    private final OverthinkingPostService postCommands;
    private final ReportedProfileShares shares;

    ContentReportEvidence capture(UUID actor, ContentReportKind kind, UUID id, UUID profileId) {
        if (kind.isProfile()) return profile(actor, kind, id, profileId);
        return switch (kind) {
            case COMMENT -> comment(actor, id);
            case OVERTHINKING_POST -> {
                targetAccess.requireReadable(actor, EngagementTargetType.OVERTHINKING, id);
                var post = posts.findById(id).orElseThrow(ContentReportTargets::unavailable);
                if (actor.equals(post.getAuthor().getId())) throw unavailable();
                yield new ContentReportEvidence(post.getTitle(), post.getContent(), null);
            }
            default -> shares.capture(actor, kind, id);
        };
    }

    private ContentReportEvidence profile(UUID actor, ContentReportKind kind, UUID owner, UUID profileId) {
        if (actor.equals(owner)) throw unavailable();
        accounts.requireActive(List.of(owner));
        accounts.lockInteraction(actor, owner);
        jdbc.queryForList("select id from \"tbl_listener-profile\" where user_id=:owner for share",
                Map.of("owner", owner), UUID.class);
        var candidates = profiles.resolveByUserId(owner).profiles().stream()
                .filter(candidate -> kind.profileType.equals(candidate.type()))
                .filter(candidate -> profileId == null || profileId.equals(candidate.profileId())).toList();
        if (candidates.size() != 1) throw unavailable();
        var target = candidates.getFirst();
        // The resolver applies listener audience, blocks, and ghost profile privacy.
        // Read only the public biography. A ghost account's private profile fields never enter the evidence.
        String query = switch (kind) {
            case LISTENER_PROFILE -> "select case when visibility_mode='STANDARD' then description end from \"tbl_listener-profile\" where id=:id and visibility_choice_completed for share";
            case MUSICIAN_PROFILE -> "select description from tbl_musician_profile where id=:id for share";
            case STUDIO_PROFILE -> "select description from tbl_studio_profile where id=:id for share";
            case VENUE_PROFILE -> "select bio from tbl_venue_profile where venue_id=:id for share";
            default -> throw unavailable();
        };
        var bios = jdbc.queryForList(query, Map.of("id", target.profileId()), String.class);
        if (bios.isEmpty()) throw unavailable();
        return new ContentReportEvidence(target.displayName(), bios.getFirst(), null);
    }

    private ContentReportEvidence comment(UUID actor, UUID id) {
        var target = comments.findCommentTarget(id).orElseThrow(ContentReportTargets::unavailable);
        targetAccess.requireReadable(actor, EngagementTargetType.valueOf(target.getTargetType()), target.getTargetId());
        String parentText = null;
        if (target.getParentId() != null) {
            var root = comments.lockCommentForRead(target.getParentId()).orElseThrow(ContentReportTargets::unavailable);
            if (root.getParentId() != null || !target.getTargetType().equals(root.getTargetType()) ||
                    !target.getTargetId().equals(root.getTargetId())) throw unavailable();
            var parent = commentFinder.getVisibleById(actor, target.getParentId());
            if (!parent.isDeleted()) parentText = parent.getText();
        }
        comments.lockCommentForRead(id).orElseThrow(ContentReportTargets::unavailable);
        var comment = commentFinder.getVisibleById(actor, id);
        if (comment.isDeleted() || actor.equals(comment.getUser().getId())) throw unavailable();
        String label = target.getParentId() == null ? "Yorum" : "Yanıt";
        return new ContentReportEvidence(label, comment.getText(), parentText);
    }

    boolean removable(ContentReportKind kind, UUID id, ContentReportEvidence evidence) {
        if (kind.isProfile() || id == null) return false;
        return switch (kind) {
            case COMMENT -> comments.findById(id).filter(comment -> !comment.isDeleted() &&
                    Objects.equals(comment.getText(), evidence.evidenceText())).isPresent();
            case OVERTHINKING_POST -> posts.findById(id).filter(post ->
                    Objects.equals(post.getTitle(), evidence.targetLabel()) &&
                    Objects.equals(post.getContent(), evidence.evidenceText())).isPresent();
            default -> shares.removable(kind, id, evidence);
        };
    }

    void remove(ContentReportKind kind, UUID id, ContentReportEvidence evidence) {
        if (kind.isProfile() || id == null) throw changed();
        switch (kind) {
            case COMMENT -> {
                var row = comments.lockComment(id).orElseThrow(ContentReportTargets::changed);
                if (row.getDeleted()) throw changed();
                var content = comments.findById(id).orElseThrow(ContentReportTargets::changed);
                if (!Objects.equals(content.getText(), evidence.evidenceText()) || comments.softDelete(id) != 1) throw changed();
            }
            case OVERTHINKING_POST -> postCommands.removeReportedPost(id, evidence.targetLabel(), evidence.evidenceText());
            default -> shares.remove(kind, id, evidence);
        }
    }

    private static SoundConnectException unavailable() { return new SoundConnectException(ErrorType.CONTENT_REPORT_TARGET_UNAVAILABLE); }
    private static SoundConnectException changed() { return new SoundConnectException(ErrorType.CONTENT_REPORT_STATE_CHANGED); }
}
