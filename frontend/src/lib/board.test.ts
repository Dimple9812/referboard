import { describe, expect, it } from 'vitest';
import type { Card } from '@/api/types';
import { moveCard, stageOf, toColumns } from './board';

const card = (id: string, stage: Card['stage']): Card => ({
  id, stage, jobTitle: id, companyName: 'Co', position: 0, source: 'MANUAL', stageChangedAt: '', jobClosed: false,
});

describe('moveCard', () => {
  const cols = toColumns({
    total: 3,
    columns: [
      { stage: 'SAVED', label: 'Saved', count: 2, cards: [card('a', 'SAVED'), card('b', 'SAVED')] },
      { stage: 'APPLIED', label: 'Applied', count: 1, cards: [card('c', 'APPLIED')] },
    ],
  });

  it('moves across columns at the given index and updates the stage', () => {
    const next = moveCard(cols, 'a', 'APPLIED', 1);
    expect(next.SAVED.map((c) => c.id)).toEqual(['b']);
    expect(next.APPLIED.map((c) => c.id)).toEqual(['c', 'a']);
    expect(next.APPLIED[1].stage).toBe('APPLIED');
    expect(cols.SAVED).toHaveLength(2);   // input untouched
  });

  it('reorders within a column and clamps the index', () => {
    expect(moveCard(cols, 'a', 'SAVED', 99).SAVED.map((c) => c.id)).toEqual(['b', 'a']);
    expect(moveCard(cols, 'b', 'SAVED', -3).SAVED.map((c) => c.id)).toEqual(['b', 'a']);
  });

  it('drops into empty columns and resolves ids', () => {
    expect(moveCard(cols, 'c', 'OFFER', 0).OFFER.map((c) => c.id)).toEqual(['c']);
    expect(stageOf(cols, 'INTERVIEW')).toBe('INTERVIEW');
    expect(stageOf(cols, 'c')).toBe('APPLIED');
    expect(stageOf(cols, 'missing')).toBeUndefined();
  });
});
