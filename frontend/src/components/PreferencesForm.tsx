import { useEffect, useState } from 'react';
import type { ExperienceLevel, Preferences, Profile } from '@/api/types';
import { Checkbox, Field, Input, Select } from '@/components/ui/Field';
import { ChipsInput } from '@/components/ChipsInput';

export const LEVELS: { value: ExperienceLevel; label: string }[] = [
  { value: 'INTERN', label: 'Internship' }, { value: 'ENTRY', label: 'Entry level (0–2 years)' },
  { value: 'MID', label: 'Mid level (2–5 years)' }, { value: 'SENIOR', label: 'Senior (5–8 years)' },
  { value: 'LEAD', label: 'Lead / Staff' }, { value: 'PRINCIPAL', label: 'Principal and above' },
];

const EMPTY: Preferences = { roles: [], locations: [], remoteOnly: false, openToRemote: true, minSalaryInr: null, experienceLevel: null };

/** Editable copy of the saved preferences; salary is edited in lakhs and converted back on save. */
export function usePreferencesDraft(profile: Profile | undefined) {
  const [prefs, setPrefs] = useState<Preferences>(EMPTY);
  const [lpa, setLpa] = useState('');
  useEffect(() => {
    const p = profile?.preferences;
    if (p) {
      setPrefs(p);
      setLpa(p.minSalaryInr ? String(p.minSalaryInr / 100000) : '');
    } else if (profile) {
      // Start from the title they hold now, so recommendations work even if they skip this step's details.
      const title = profile.currentTitle || profile.experiences.find((e) => e.current)?.title || profile.experiences[0]?.title;
      if (title) setPrefs((x) => ({ ...x, roles: x.roles.length ? x.roles : [title] }));
    }
  }, [profile]);
  const value = (): Preferences => ({ ...prefs, minSalaryInr: lpa ? Math.round(Number(lpa) * 100000) : null });
  return { prefs, setPrefs, lpa, setLpa, value };
}

export function PreferencesFields({ draft }: { draft: ReturnType<typeof usePreferencesDraft> }) {
  const { prefs, setPrefs, lpa, setLpa } = draft;
  return (
    <div className="space-y-6">
      <Field label="Roles" hint="For example: Backend Engineer, SDE II, Platform Engineer">
        {(p) => <ChipsInput id={p.id} describedBy={p['aria-describedby']} max={10} value={prefs.roles} onChange={(roles) => setPrefs({ ...prefs, roles })} placeholder="Add a role" />}
      </Field>
      <Field label="Cities" hint="Bengaluru, Pune, Hyderabad… Leave empty for anywhere.">
        {(p) => <ChipsInput id={p.id} describedBy={p['aria-describedby']} max={10} value={prefs.locations} onChange={(locations) => setPrefs({ ...prefs, locations })} placeholder="Add a city" />}
      </Field>
      <div className="space-y-3">
        <Checkbox label="Open to remote roles" checked={prefs.openToRemote} onChange={(e) => setPrefs({ ...prefs, openToRemote: e.target.checked })} />
        <Checkbox label="Only remote roles" checked={prefs.remoteOnly} onChange={(e) => setPrefs({ ...prefs, remoteOnly: e.target.checked, openToRemote: e.target.checked || prefs.openToRemote })} />
      </div>
      <div className="grid gap-5 sm:grid-cols-2">
        <Field label="Level">
          {(p) => (
            <Select {...p} value={prefs.experienceLevel ?? ''} onChange={(e) => setPrefs({ ...prefs, experienceLevel: (e.target.value || null) as ExperienceLevel | null })}>
              <option value="">Any level</option>
              {LEVELS.map((l) => <option key={l.value} value={l.value}>{l.label}</option>)}
            </Select>
          )}
        </Field>
        <Field label="Minimum salary (lakhs per year)" hint="Optional. Jobs that state less are ranked lower.">
          {(p) => <Input {...p} inputMode="decimal" value={lpa} onChange={(e) => setLpa(e.target.value.replace(/[^\d.]/g, ''))} placeholder="e.g. 25" />}
        </Field>
      </div>
    </div>
  );
}
