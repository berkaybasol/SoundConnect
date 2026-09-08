package com.berkayb.soundconnect.modules.comment.mapper;

import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class CommentTimestampContractTest {
    private final CommentMapper mapper=Mappers.getMapper(CommentMapper.class);

    @ParameterizedTest
    @ValueSource(strings={"UTC","Europe/Istanbul","America/Los_Angeles"})
    void everyCommentMapperPathEmitsUtcInstantRatherThanOffsetlessWallTime(String jacksonZone) throws Exception {
        // The JPA auditing provider stores this UTC wall-clock value, not Istanbul local time.
        var created=LocalDateTime.of(2026,9,8,20,32,10,123456000);
        var root=Comment.builder().id(UUID.randomUUID()).targetType(EngagementTargetType.EVENT)
                .targetId(UUID.randomUUID()).text("fresh").createdAt(created).build();
        var reply=Comment.builder().id(UUID.randomUUID()).targetType(EngagementTargetType.EVENT)
                .targetId(root.getTargetId()).parentComment(root).text("reply").createdAt(created).build();
        var json=json(jacksonZone);
        for(Object dto:List.of(mapper.toResolvedComment(root,1,true,null),mapper.toResolvedReply(reply,true,null),
                mapper.toCommentResponseDto(root,1,true),mapper.toCommentReplyResponseDto(reply,true))) {
            assertThat(json.readTree(json.writeValueAsString(dto)).get("createdAt").asText())
                    .isEqualTo("2026-09-08T20:32:10.123456Z");
        }
    }

    @Test void missingLegacyTimestampRemainsNullInsteadOfInventingCurrentTime() throws Exception {
        var comment=Comment.builder().id(UUID.randomUUID()).text("legacy").build();
        var json=json("Europe/Istanbul");
        assertThat(json.readTree(json.writeValueAsString(mapper.toResolvedComment(comment,0,true,null)))
                .get("createdAt").isNull()).isTrue();
        assertThat(json.readTree(json.writeValueAsString(mapper.toResolvedReply(comment,true,null)))
                .get("createdAt").isNull()).isTrue();
    }

    private ObjectMapper json(String zone) {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).setTimeZone(TimeZone.getTimeZone(zone));
    }
}
