package com.referboard.auth.mapper;

import com.referboard.auth.domain.User;
import com.referboard.auth.web.dto.AuthDtos.UserResponse;
import org.mapstruct.Mapper;

@Mapper
public interface UserMapper {

    @org.mapstruct.Mapping(target = "plan", expression = "java(user.hasPro(java.time.Instant.now()) ? \"PRO\" : \"FREE\")")
    UserResponse toResponse(User user);
}
