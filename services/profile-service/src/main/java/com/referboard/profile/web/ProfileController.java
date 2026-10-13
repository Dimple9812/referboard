package com.referboard.profile.web;

import com.referboard.common.security.CurrentUser;
import com.referboard.profile.service.ProfileService;
import com.referboard.profile.web.dto.ProfileDtos.PreferencesDto;
import com.referboard.profile.web.dto.ProfileDtos.PreferencesRequest;
import com.referboard.profile.web.dto.ProfileDtos.ProfileResponse;
import com.referboard.profile.web.dto.ProfileDtos.UpdateProfileRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
@Tag(name = "Profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    @Operation(summary = "My profile (exists=false until a resume is parsed or the profile is saved)")
    public ProfileResponse get() {
        return profileService.get(CurrentUser.id());
    }

    @PutMapping
    @Operation(summary = "Save the reviewed/edited profile (full replacement of editable fields)")
    public ProfileResponse update(@Valid @RequestBody UpdateProfileRequest request) {
        return profileService.update(CurrentUser.id(), request);
    }

    @GetMapping("/preferences")
    @Operation(summary = "My job preferences")
    public PreferencesDto preferences() {
        return profileService.get(CurrentUser.id()).preferences();
    }

    @PutMapping("/preferences")
    @Operation(summary = "Set job preferences (roles, locations, remote, minimum salary, level)")
    public PreferencesDto updatePreferences(@Valid @RequestBody PreferencesRequest request) {
        return profileService.updatePreferences(CurrentUser.id(), request);
    }
}
