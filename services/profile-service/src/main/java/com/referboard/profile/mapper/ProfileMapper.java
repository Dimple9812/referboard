package com.referboard.profile.mapper;

import com.referboard.profile.domain.Education;
import com.referboard.profile.domain.Experience;
import com.referboard.profile.domain.JobPreferences;
import com.referboard.profile.domain.ProfileSkill;
import com.referboard.profile.domain.Resume;
import com.referboard.profile.web.dto.ProfileDtos.EducationDto;
import com.referboard.profile.web.dto.ProfileDtos.ExperienceDto;
import com.referboard.profile.web.dto.ProfileDtos.PreferencesDto;
import com.referboard.profile.web.dto.ProfileDtos.ResumeResponse;
import com.referboard.profile.web.dto.ProfileDtos.SkillDto;
import org.mapstruct.Mapper;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@Mapper
public interface ProfileMapper {

    SkillDto toDto(ProfileSkill skill);

    List<SkillDto> toSkillDtos(List<ProfileSkill> skills);

    ExperienceDto toDto(Experience experience);

    List<ExperienceDto> toExperienceDtos(List<Experience> experiences);

    EducationDto toDto(Education education);

    List<EducationDto> toEducationDtos(List<Education> educations);

    PreferencesDto toDto(JobPreferences preferences);

    ResumeResponse toDto(Resume resume);

    List<ResumeResponse> toResumeDtos(List<Resume> resumes);

    default YearMonth toYearMonth(LocalDate date) {
        return date == null ? null : YearMonth.from(date);
    }
}
