import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { DemoBanner, TryDemoButton } from './Demo';

const auth = { demoAvailable: false, isDemo: false, loginDemo: vi.fn(), logout: vi.fn() };
vi.mock('@/auth/AuthProvider', () => ({ useAuth: () => auth }));

function renderAt(ui: JSX.Element) {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={ui} />
        <Route path="/jobs" element={<p>jobs page</p>} />
        <Route path="/signup" element={<p>signup page</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('demo mode', () => {
  beforeEach(() => {
    Object.assign(auth, { demoAvailable: false, isDemo: false });
    auth.loginDemo.mockReset().mockResolvedValue(undefined);
    auth.logout.mockReset().mockResolvedValue(undefined);
  });

  it('hides the button when the deployment has no demo', () => {
    renderAt(<TryDemoButton />);
    expect(screen.queryByRole('button', { name: /try the demo/i })).toBeNull();
  });

  it('signs in to the demo and opens the jobs page', async () => {
    auth.demoAvailable = true;
    renderAt(<TryDemoButton />);
    fireEvent.click(screen.getByRole('button', { name: /try the demo/i }));
    expect(await screen.findByText('jobs page')).toBeTruthy();
    expect(auth.loginDemo).toHaveBeenCalledOnce();
  });

  it('shows the banner only for the demo account, and signs out before sign-up', async () => {
    const { unmount } = renderAt(<DemoBanner />);
    expect(screen.queryByRole('note')).toBeNull();
    unmount();
    auth.isDemo = true;
    renderAt(<DemoBanner />);
    fireEvent.click(screen.getByRole('link', { name: /create your own account/i }));
    await waitFor(() => expect(auth.logout).toHaveBeenCalledOnce());
    expect(await screen.findByText('signup page')).toBeTruthy();
  });
});
