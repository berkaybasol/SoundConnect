package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class MusicianFeedModerationScopeResolverTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MusicianFeedModerationScopeResolver scopes = new MusicianFeedModerationScopeResolver(mapper);

    @Test
    void realDeliveryEvidenceEnvelopeAndEveryPresentIdentityFieldMustMatch() {
        UUID media = UUID.randomUUID();
        String itemId = "TRACK:" + UUID.randomUUID();
        var evidence = mapper.createObjectNode().put("itemId", itemId).put("itemType", "TRACK");
        evidence.set("target", mapper.valueToTree(new MusicianFeedItemResponse.Target("MEDIA", media)));
        evidence.set("payload", mapper.createObjectNode().put("omitted", true));
        assertThat(scopes.reportScope(subject(itemId, "TRACK", "MEDIA", media, evidence)))
                .isEqualTo("TARGET:MEDIA:" + media);
        for (String field : List.of("itemId", "itemType", "id", "type")) {
            var tampered = evidence.deepCopy().put(field, "different");
            assertThat(scopes.reportScope(subject(itemId, "TRACK", "MEDIA", media, tampered))).as(field).isNull();
        }
        var wrongTarget = evidence.deepCopy();
        ((ObjectNode) wrongTarget.get("target")).put("id", UUID.randomUUID().toString());
        assertThat(scopes.reportScope(subject(itemId, "TRACK", "MEDIA", media, wrongTarget))).isNull();
    }

    @Test
    void profileScopeNeedsTheExactProfileNamespaceAndRejectsConflictingAuthorEvidence() {
        UUID profile = UUID.randomUUID();
        String id = "PROFILE:" + profile;
        assertThat(scopes.reportScope(subject(id, "PROFILE", "PROFILE", profile, mapper.createObjectNode()))).isNull();
        var evidence = mapper.createObjectNode();
        evidence.set("payload", mapper.createObjectNode().put("profileType", "LISTENER").put("profileId", profile.toString()));
        assertThat(scopes.reportScope(subject(id, "PROFILE", "PROFILE", profile, evidence)))
                .isEqualTo("TARGET:PROFILE:LISTENER:" + profile);
        evidence.set("author", mapper.createObjectNode().put("profileType", "MUSICIAN").put("profileId", profile.toString()));
        assertThat(scopes.reportScope(subject(id, "PROFILE", "PROFILE", profile, evidence))).isNull();
    }

    @Test
    void followTargetScopeComesFromTheFollowedProfileNotTheActor() {
        UUID sharedId = UUID.randomUUID();
        var actor = new MusicianFeedItemResponse.Author(UUID.randomUUID(), sharedId, "MUSICIAN", "actor", "Actor", null, true);
        var profile = new MusicianFeedPayloads.Profile(sharedId, "LISTENER", UUID.randomUUID(), "target", "Target", null, null, null, false);
        var item = item("ACTIVITY_FOLLOW:LISTENER:" + sharedId, MusicianFeedItemType.ACTIVITY_FOLLOW,
                "PROFILE", sharedId, actor, new MusicianFeedPayloads.Activity("FOLLOW", actor, MusicianFeedItemType.PROFILE, profile));
        assertThat(scopes.presentationScopes(item)).containsExactlyInAnyOrder(
                "ITEM:" + item.id(), "TARGET:PROFILE:LISTENER:" + sharedId);
        assertThat(scopes.reportScope(subject(item.id(), "ACTIVITY_FOLLOW", "PROFILE", sharedId,
                mapper.valueToTree(item)))).isEqualTo("ITEM:" + item.id());
    }

    @Test
    void commentReportRemovesTheStoryWhileNativeMediaRestrictionCoversThatPresentation() {
        UUID media = UUID.randomUUID();
        String comment = "ACTIVITY_COMMENT:" + UUID.randomUUID();
        assertThat(scopes.reportScope(subject(comment, "ACTIVITY_COMMENT", "MEDIA", media, mapper.createObjectNode())))
                .isEqualTo("ITEM:" + comment);
        var item = item(comment, MusicianFeedItemType.ACTIVITY_COMMENT, "MEDIA", media, null, Map.of());
        assertThat(scopes.presentationScopes(item)).containsExactlyInAnyOrder("ITEM:" + comment, "TARGET:MEDIA:" + media);
    }

    @Test
    void eventPublicationHasItsOwnScopeAndAlsoHonorsRestrictionOfItsSourceEvent() {
        UUID event = UUID.randomUUID(), post = UUID.randomUUID();
        var payload = mapper.createObjectNode();
        payload.set("event", mapper.createObjectNode().put("id", event.toString()));
        payload.put("publicationId", post.toString());
        var item = item("EVENT_PROFILE_SHARE:" + post, MusicianFeedItemType.EVENT_PROFILE_SHARE,
                "EVENT_POST", post, null, payload);
        assertThat(scopes.presentationScopes(item)).containsExactlyInAnyOrder(
                "ITEM:" + item.id(), "TARGET:EVENT_POST:" + post, "TARGET:EVENT:" + event);
        assertThat(scopes.reportScope(subject(item.id(), item.type().name(), "EVENT_POST", post,
                mapper.valueToTree(item)))).isEqualTo("TARGET:EVENT_POST:" + post);
    }

    @Test
    void completionIsExemptAndStandalonePromotionDoesNotAssumeACampaignDatabaseIdentity() {
        UUID id = UUID.randomUUID();
        assertThat(scopes.presentationScopes(item("PROFILE_COMPLETION:" + id, MusicianFeedItemType.PROFILE_COMPLETION,
                "PROFILE", id, null, Map.of()))).isEmpty();
        assertThat(scopes.reportScope(subject("PROFILE_COMPLETION:" + id, "PROFILE_COMPLETION", "PROFILE", id,
                mapper.createObjectNode()))).isNull();
        assertThat(scopes.reportScope(subject("SPONSORED:" + id, "SPONSORED", "STANDALONE", id,
                mapper.createObjectNode()))).isEqualTo("ITEM:SPONSORED:" + id);
    }

    private MusicianFeedModerationSubject subject(String itemId, String type, String target, UUID id, com.fasterxml.jackson.databind.JsonNode evidence) {
        return new MusicianFeedModerationSubject(UUID.randomUUID(), itemId, type, target, id, evidence);
    }

    private MusicianFeedItemResponse item(String id, MusicianFeedItemType type, String target, UUID targetId,
                                          MusicianFeedItemResponse.Author author, Object payload) {
        return new MusicianFeedItemResponse(id, type, 1, Instant.EPOCH, null, author,
                new MusicianFeedItemResponse.Target(target, targetId), null, null, List.of(), payload);
    }
}
