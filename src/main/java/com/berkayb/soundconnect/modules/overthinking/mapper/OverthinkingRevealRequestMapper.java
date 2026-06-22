package com.berkayb.soundconnect.modules.overthinking.mapper;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingRevealRequestResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingRevealRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OverthinkingRevealRequestMapper {
	
	@Mapping(target = "postId", expression = "java(request.getPost().getId())")
	@Mapping(target = "postTitle", expression = "java(request.getPost().getTitle())")
	@Mapping(target = "requesterId", expression = "java(request.getRequester().getId())")
	@Mapping(target = "requesterUsername", expression = "java(request.getRequester().getUsername())")
	@Mapping(target = "authorId", expression = "java(request.getAuthor().getId())")
	OverthinkingRevealRequestResponseDto toDto(OverthinkingRevealRequest request);
}