package com.referboard.profile.service;

import com.referboard.profile.domain.ProfileSkill;
import com.referboard.skills.SkillTaxonomy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalises skills to canonical names, de-duplicates them, and marks each one's evidence: BULLET when the skill
 * appears in an experience bullet/description (recruiters and ATS weight these), SKILLS_LIST otherwise.
 */
@Component
public class SkillAssembler {

    public record Candidate(String rawName, BigDecimal years, ProfileSkill.Source source) {
    }

    private final SkillTaxonomy taxonomy;

    public SkillAssembler(SkillTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    public List<ProfileSkill> assemble(List<Candidate> candidates, String experienceText) {
        Set<String> evidenced = taxonomy.extract(experienceText);
        String lowerExperience = experienceText == null ? "" : experienceText.toLowerCase(Locale.ROOT);

        Map<String, Candidate> byName = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            String name = taxonomy.normalize(c.rawName());
            if (name == null) {
                continue;
            }
            Candidate existing = byName.get(name);
            // USER wins over PARSED for the same skill; keep the first non-null years.
            if (existing == null) {
                byName.put(name, c);
            } else {
                ProfileSkill.Source source = existing.source() == ProfileSkill.Source.USER
                        || c.source() == ProfileSkill.Source.USER ? ProfileSkill.Source.USER : ProfileSkill.Source.PARSED;
                byName.put(name, new Candidate(existing.rawName(),
                        existing.years() != null ? existing.years() : c.years(), source));
            }
        }

        List<ProfileSkill> out = new ArrayList<>();
        int order = 0;
        for (Map.Entry<String, Candidate> e : byName.entrySet()) {
            String name = e.getKey();
            boolean inBullets = evidenced.contains(name)
                    || (!taxonomy.isKnown(name) && wordMatch(lowerExperience, name));
            String display = taxonomy.isKnown(name) ? taxonomy.display(name) : e.getValue().rawName().trim();
            out.add(ProfileSkill.of(name, truncate(display), e.getValue().years(), e.getValue().source(),
                    inBullets ? ProfileSkill.Evidence.BULLET : ProfileSkill.Evidence.SKILLS_LIST, order++));
        }
        return out;
    }

    private static boolean wordMatch(String haystack, String needle) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(needle) + "(?![\\p{L}\\p{N}])")
                .matcher(haystack).find();
    }

    private static String truncate(String s) {
        return s.length() <= 100 ? s : s.substring(0, 100);
    }
}
