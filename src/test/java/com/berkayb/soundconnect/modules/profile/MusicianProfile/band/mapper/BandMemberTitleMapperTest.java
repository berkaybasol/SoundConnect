package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.mapper;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandMemberResponseDto;
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

class BandMemberTitleMapperTest {
    final BandMapper mapper = Mappers.getMapper(BandMapper.class);

    @Test void existingRosterMappingIncludesTitleAndVersionWithoutChangingRole() throws Exception {
        var founder = member(BandRole.FOUNDER, "Vokal", 3, BandMemberShipStatus.ACTIVE);
        var ordinary = member(BandRole.MEMBER, null, 0, BandMemberShipStatus.ACTIVE);
        var left = member(BandRole.MEMBER, "Eski başlık", 8, BandMemberShipStatus.LEFT);
        var band = Band.builder().id(UUID.randomUUID()).name("Şahbaz").members(Set.of(founder, ordinary, left)).build();
        var result = mapper.toDto(band);
        assertThat(result.members()).hasSize(2);
        var mapped = result.members().stream().filter(row -> row.userId().equals(founder.getUser().getId())).findFirst().orElseThrow();
        assertThat(mapped.memberTitle()).isEqualTo("Vokal");
        assertThat(mapped.titleVersion()).isEqualTo(3);
        assertThat(mapped.role()).isEqualTo("FOUNDER");
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(mapped));
        assertThat(json.path("memberTitle").asText()).isEqualTo("Vokal");
        assertThat(json.path("titleVersion").asLong()).isEqualTo(3);
        assertThat(json.path("role").asText()).isEqualTo("FOUNDER");
    }

    @Test void backwardConstructorDefaultsToNoTitleAndZeroVersion() {
        var dto = new BandMemberResponseDto(UUID.randomUUID(), "aedrum", null, "MEMBER", "ACTIVE");
        assertThat(dto.memberTitle()).isNull();
        assertThat(dto.titleVersion()).isZero();
    }

    @Test void newlyBuiltMembershipHasZeroVersionAndNoDisplayRoleFallback() {
        var dto = mapper.toMemberDto(member(BandRole.MEMBER, null, 0, BandMemberShipStatus.ACTIVE));
        assertThat(dto.memberTitle()).isNull();
        assertThat(dto.titleVersion()).isZero();
        assertThat(dto.role()).isEqualTo("MEMBER");
    }

    private BandMember member(BandRole role, String title, long version, BandMemberShipStatus status) {
        return BandMember.builder().user(User.builder().id(UUID.randomUUID()).username("aedrum").build())
                .bandRole(role).status(status).memberTitle(title).titleVersion(version).build();
    }
}
