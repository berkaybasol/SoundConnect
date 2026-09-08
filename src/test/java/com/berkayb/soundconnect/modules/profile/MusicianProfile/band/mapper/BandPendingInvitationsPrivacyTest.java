package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.mapper;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandPendingInvitationResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class BandPendingInvitationsPrivacyTest {
    @Test void publicAndPrivateProfileMapperStillIncludeOnlyActiveRoster() {
        var active = member("founder", BandMemberShipStatus.ACTIVE);
        var pending = member("pending", BandMemberShipStatus.PENDING);
        var rejected = member("rejected", BandMemberShipStatus.REJECTED);
        var left = member("left", BandMemberShipStatus.LEFT);
        var band = Band.builder().id(UUID.randomUUID()).name("Şahbaz").members(Set.of(active, pending, rejected, left)).build();
        assertThat(Mappers.getMapper(BandMapper.class).toDto(band).members()).hasSize(1)
                .allSatisfy(row -> assertThat(row.username()).isEqualTo("founder"));
    }

    @Test void privateProjectionSerializesOnlyIntendedSummaryFields() throws Exception {
        var dto = new BandPendingInvitationResponseDto(UUID.randomUUID(), "aedrum", null, "PENDING");
        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(dto));
        assertThat(json.size()).isEqualTo(4);
        assertThat(json.has("userId")).isTrue();
        assertThat(json.has("username")).isTrue();
        assertThat(json.has("profilePicture")).isTrue();
        assertThat(json.path("status").asText()).isEqualTo("PENDING");
    }

    private BandMember member(String username, BandMemberShipStatus status) {
        return BandMember.builder().id(UUID.randomUUID()).user(User.builder().id(UUID.randomUUID()).username(username).build())
                .bandRole(BandRole.MEMBER).status(status).build();
    }
}
