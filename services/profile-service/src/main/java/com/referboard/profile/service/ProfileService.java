package com.referboard.profile.service;

import com.referboard.profile.domain.Education;
import com.referboard.profile.domain.Experience;
import com.referboard.profile.domain.JobPreferences;
import com.referboard.profile.domain.Profile;
import com.referboard.profile.domain.ProfileSkill;
import com.referboard.profile.domain.Resume;
import com.referboard.profile.mapper.ProfileMapper;
import com.referboard.profile.parsing.ParsedResume;
import com.referboard.profile.repository.JobPreferencesRepository;
import com.referboard.profile.repository.ProfileRepository;
import com.referboard.profile.web.dto.ProfileDtos.PreferencesDto;
import com.referboard.profile.web.dto.ProfileDtos.PreferencesRequest;
import com.referboard.profile.web.dto.ProfileDtos.ProfileResponse;
import com.referboard.profile.web.dto.ProfileDtos.UpdateProfileRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class ProfileService {

    private final ProfileRepository profiles;
    private final JobPreferencesRepository preferences;
    private final SkillAssembler skillAssembler;
    private final ProfileMapper mapper;
    private final ApplicationEventPublisher springEvents;
    private final Clock clock;

    public ProfileService(ProfileRepository profiles, JobPreferencesRepository preferences,
                          SkillAssembler skillAssembler, ProfileMapper mapper, ApplicationEventPublisher springEvents,
                          Clock clock) {
        this.profiles = profiles;
        this.preferences = preferences;
        this.skillAssembler = skillAssembler;
        this.mapper = mapper;
        this.springEvents = springEvents;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(UUID userId) {
        PreferencesDto prefs = mapper.toDto(preferences.findById(userId).orElseGet(() -> JobPreferences.defaults(userId)));
        return profiles.findById(userId)
                .map(p -> toResponse(p, prefs))
                .orElseGet(() -> new ProfileResponse(userId, false, null, null, null, null, null, null, null, null,
                        null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), prefs, 0, null, null,
                        false, null));
    }

    /** Applies the user's reviewed/edited profile. Skills they add are USER skills and survive future re-parses. */
    @Transactional
    public ProfileResponse update(UUID userId, UpdateProfileRequest req) {
        Profile profile = profiles.findById(userId).orElseGet(() -> profiles.save(Profile.create(userId)));
        LocalDate today = LocalDate.now(clock);

        profile.setFullName(trim(req.fullName()));
        profile.setEmail(trim(req.email()));
        profile.setPhone(trim(req.phone()));
        profile.setHeadline(trim(req.headline()));
        profile.setSummary(trim(req.summary()));
        profile.setLocation(trim(req.location()));
        profile.setLinks(nonNull(req.links()));
        profile.setCertifications(nonNull(req.certifications()));

        List<Experience> experiences = new ArrayList<>();
        for (int i = 0; i < req.experiences().size(); i++) {
            var e = req.experiences().get(i);
            experiences.add(Experience.of(e.company().trim(), e.title().trim(), trim(e.location()),
                    e.startDate().atDay(1), e.endDate() == null ? null : e.endDate().atDay(1), e.current(),
                    trim(e.description()), nonNull(e.bullets()), i));
        }
        profile.replaceExperiences(experiences);

        List<Education> educations = new ArrayList<>();
        for (int i = 0; i < req.educations().size(); i++) {
            var e = req.educations().get(i);
            educations.add(Education.of(e.institution().trim(), trim(e.degree()), trim(e.fieldOfStudy()),
                    e.startYear(), e.endYear(), trim(e.grade()), i));
        }
        profile.replaceEducations(educations);

        // A skill the user keeps that came from the resume stays PARSED; anything new is USER.
        List<String> previouslyParsed = profile.getSkills().stream()
                .filter(s -> s.getSource() == ProfileSkill.Source.PARSED).map(ProfileSkill::getName).toList();
        List<SkillAssembler.Candidate> candidates = req.skills().stream()
                .map(s -> new SkillAssembler.Candidate(s.name(), s.years(), ProfileSkill.Source.USER))
                .toList();
        List<ProfileSkill> skills = skillAssembler.assemble(candidates, experienceText(experiences));
        List<ProfileSkill> withSource = skills.stream()
                .map(s -> previouslyParsed.contains(s.getName())
                        ? ProfileSkill.of(s.getName(), s.getDisplayName(), s.getYears(), ProfileSkill.Source.PARSED,
                        s.getEvidence(), s.getSortOrder())
                        : s)
                .toList();
        replaceSkills(profile, withSource);

        deriveFields(profile, today, trim(req.currentTitle()), trim(req.currentCompany()), trim(req.college()),
                trim(req.highestDegree()));
        if (profile.getParseSource() == null) {
            profile.setParseSource(Profile.Source.MANUAL);
        }
        return touch(profile, userId);
    }

    @Transactional
    public PreferencesDto updatePreferences(UUID userId, PreferencesRequest req) {
        JobPreferences prefs = preferences.findById(userId).orElseGet(() -> JobPreferences.defaults(userId));
        prefs.setRoles(clean(req.roles()));
        prefs.setLocations(clean(req.locations()));
        prefs.setRemoteOnly(req.remoteOnly());
        prefs.setOpenToRemote(req.remoteOnly() || req.openToRemote());
        prefs.setMinSalaryInr(req.minSalaryInr());
        prefs.setExperienceLevel(req.experienceLevel());
        preferences.save(prefs);

        Profile profile = profiles.findById(userId).orElseGet(() -> profiles.save(Profile.create(userId)));
        touch(profile, userId);
        return mapper.toDto(prefs);
    }

    /**
     * Rebuilds the resume-derived parts of the profile from the primary resume. Experiences, education and
     * PARSED skills are replaced; USER skills and job preferences are kept. Called within the caller's transaction.
     */
    @Transactional
    public void applyParsedResume(UUID userId, UUID resumeId, ParsedResume parsed, Resume.Parser parser) {
        Profile profile = profiles.findById(userId).orElseGet(() -> profiles.save(Profile.create(userId)));
        LocalDate today = LocalDate.now(clock);

        setIfPresent(parsed.fullName(), profile::setFullName);
        setIfPresent(parsed.email(), profile::setEmail);
        setIfPresent(parsed.phone(), profile::setPhone);
        setIfPresent(parsed.location(), profile::setLocation);
        setIfPresent(parsed.headline(), profile::setHeadline);
        setIfPresent(parsed.summary(), profile::setSummary);
        profile.setLinks(new ArrayList<>(parsed.links()));
        profile.setCertifications(new ArrayList<>(parsed.certifications()));

        List<Experience> experiences = new ArrayList<>();
        int order = 0;
        for (ParsedResume.ParsedExperience e : parsed.experiences()) {
            LocalDate start = ExperienceMath.parseYearMonth(e.startDate(), today);
            boolean current = Boolean.TRUE.equals(e.isCurrent());
            LocalDate end = current ? null : ExperienceMath.parseYearMonth(e.endDate(), today);
            experiences.add(Experience.of(fallback(e.company(), "Unknown company"), fallback(e.title(), "Unknown title"),
                    trim(e.location()), start, end, current, trim(e.description()), e.bullets(), order++));
        }
        profile.replaceExperiences(experiences);

        List<Education> educations = new ArrayList<>();
        order = 0;
        for (ParsedResume.ParsedEducation e : parsed.educations()) {
            educations.add(Education.of(e.institution().trim(), trim(e.degree()), trim(e.fieldOfStudy()),
                    e.startYear(), e.endYear(), trim(e.grade()), order++));
        }
        profile.replaceEducations(educations);

        List<SkillAssembler.Candidate> candidates = Stream.concat(
                profile.getSkills().stream()
                        .filter(s -> s.getSource() == ProfileSkill.Source.USER)
                        .map(s -> new SkillAssembler.Candidate(s.getName(), s.getYears(), ProfileSkill.Source.USER)),
                parsed.skills().stream().map(s -> new SkillAssembler.Candidate(s, null, ProfileSkill.Source.PARSED))
        ).toList();
        replaceSkills(profile, skillAssembler.assemble(candidates, experienceText(experiences)));

        deriveFields(profile, today, null, null, null, null);
        profile.setPrimaryResumeId(resumeId);
        profile.setParseSource(parser == Resume.Parser.LLM ? Profile.Source.LLM : Profile.Source.HEURISTIC);
        touch(profile, userId);
    }

    /** Fills title/company/college/degree/total years: explicit values win, else derived from the entries. */
    private static void deriveFields(Profile profile, LocalDate today, String title, String company, String college,
                                     String degree) {
        List<Experience> exps = profile.getExperiences();
        Experience latest = exps.stream().filter(Experience::isCurrent).findFirst()
                .orElse(exps.isEmpty() ? null : exps.getFirst());
        profile.setCurrentTitle(title != null ? title : latest == null ? null : latest.getTitle());
        profile.setCurrentCompany(company != null ? company : latest == null ? null : latest.getCompany());
        Education topEducation = profile.getEducations().isEmpty() ? null : profile.getEducations().getFirst();
        profile.setCollege(college != null ? college : topEducation == null ? null : topEducation.getInstitution());
        profile.setHighestDegree(degree != null ? degree : topEducation == null ? null : topEducation.getDegree());
        profile.setTotalExperienceYears(ExperienceMath.totalYears(exps.stream()
                .map(e -> ExperienceMath.span(e.getStartDate(), e.getEndDate(), e.isCurrent(), today))
                .toList()));
    }

    /**
     * Hibernate flushes inserts before deletes, so re-adding a skill with the same name would trip the
     * (user_id, name) unique index. Remove and flush first, then insert.
     */
    private void replaceSkills(Profile profile, List<ProfileSkill> next) {
        profile.getSkills().clear();
        profiles.flush();
        profile.replaceSkills(new ArrayList<>(next));
    }

    private ProfileResponse touch(Profile profile, UUID userId) {
        profile.bumpVersion();
        profiles.saveAndFlush(profile);
        springEvents.publishEvent(new ProfileChanged(userId));
        PreferencesDto prefs = mapper.toDto(preferences.findById(userId).orElseGet(() -> JobPreferences.defaults(userId)));
        return toResponse(profile, prefs);
    }

    private ProfileResponse toResponse(Profile p, PreferencesDto prefs) {
        return new ProfileResponse(p.getUserId(), true, p.getFullName(), p.getEmail(), p.getPhone(), p.getHeadline(),
                p.getSummary(), p.getCurrentTitle(), p.getCurrentCompany(), p.getLocation(),
                p.getTotalExperienceYears(), p.getCollege(), p.getHighestDegree(), p.getLinks(),
                p.getCertifications(), mapper.toSkillDtos(p.getSkills()), mapper.toExperienceDtos(p.getExperiences()),
                mapper.toEducationDtos(p.getEducations()), prefs, p.getProfileVersion(),
                p.getParseSource() == null ? null : p.getParseSource().name(), p.getPrimaryResumeId(),
                Objects.equals(p.getEmbeddingVersion(), p.getProfileVersion()), p.getUpdatedAt());
    }

    static String experienceText(List<Experience> experiences) {
        StringBuilder sb = new StringBuilder();
        for (Experience e : experiences) {
            if (e.getDescription() != null) {
                sb.append(e.getDescription()).append('\n');
            }
            e.getBullets().forEach(b -> sb.append(b).append('\n'));
        }
        return sb.toString();
    }

    private static void setIfPresent(String value, java.util.function.Consumer<String> setter) {
        String t = trim(value);
        if (t != null) {
            setter.accept(t);
        }
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String fallback(String s, String fallback) {
        String t = trim(s);
        return t == null ? fallback : t;
    }

    private static List<String> nonNull(List<String> list) {
        return list == null ? new ArrayList<>() : new ArrayList<>(list.stream().map(String::trim).toList());
    }

    private static List<String> clean(List<String> list) {
        return new ArrayList<>(list.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList());
    }
}
