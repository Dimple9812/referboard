import type { Board, Card, Stage } from '@/api/types';

export const STAGES: Stage[] = ['SAVED', 'APPLIED', 'REFERRAL_REQUESTED', 'REFERRED', 'INTERVIEW', 'OFFER', 'REJECTED'];
export type Columns = Record<Stage, Card[]>;

export function toColumns(board?: Board): Columns {
  const cols = Object.fromEntries(STAGES.map((s) => [s, [] as Card[]])) as Columns;
  board?.columns.forEach((c) => { cols[c.stage] = c.cards; });
  return cols;
}

/** Which column an id belongs to: a column id is the stage itself, a card id is looked up. */
export function stageOf(cols: Columns, id: string): Stage | undefined {
  if ((STAGES as string[]).includes(id)) return id as Stage;
  return STAGES.find((s) => cols[s].some((c) => c.id === id));
}

/**
 * Moves a card to {@code toStage} at {@code toIndex} (clamped). Pure: returns new columns, the input is untouched.
 * The same index is sent to the API, which places the card there.
 */
export function moveCard(cols: Columns, cardId: string, toStage: Stage, toIndex: number): Columns {
  const from = stageOf(cols, cardId);
  if (!from) return cols;
  const card = cols[from].find((c) => c.id === cardId)!;
  const next = { ...cols, [from]: cols[from].filter((c) => c.id !== cardId) } as Columns;
  const target = [...next[toStage]];
  const i = Math.max(0, Math.min(toIndex, target.length));
  target.splice(i, 0, { ...card, stage: toStage });
  next[toStage] = target;
  return next;
}
