package com.berkayb.soundconnect.modules.overthinking.mapper;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OverthinkingPostMapper {
	
	@Mapping(target = "authorId", expression = "java(post.getAuthor().getId())")
	OverthinkingPostResponseDto toDto(OverthinkingPost post);
}