package com.referboard.auth.mapper;

import com.referboard.auth.domain.User;
import com.referboard.auth.web.dto.AuthDtos.UserResponse;
import org.mapstruct.Mapper;

@Mapper
public interface UserMapper {

    UserResponse toResponse(User user);
}
