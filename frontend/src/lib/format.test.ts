import { plural, salaryRange, timeAgo } from './format';

test('salaries read as lakhs per annum', () => {
  expect(salaryRange(1800000, 2400000, 'INR')).toBe('₹18–24 LPA');
  expect(salaryRange(650000, null, null)).toBe('From ₹6.5 LPA');
  expect(salaryRange(null, 900000, 'INR')).toBe('Up to ₹9 LPA');
  expect(salaryRange(120000, 150000, 'USD')).toBe('USD 120k–150k');
  expect(salaryRange(null, null)).toBeNull();
});

test('relative times', () => {
  expect(timeAgo(new Date(Date.now() - 3 * 86400_000).toISOString())).toBe('3 days ago');
  expect(timeAgo(new Date().toISOString())).toBe('just now');
  expect(timeAgo(null)).toBe('');
});

test('plural', () => {
  expect(plural(1, 'contact')).toBe('1 contact');
  expect(plural(3, 'contact')).toBe('3 contacts');
});
