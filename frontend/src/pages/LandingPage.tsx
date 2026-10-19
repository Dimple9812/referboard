import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Logo } from '@/components/Logo';
import { TryDemoButton } from '@/components/Demo';
import { ReferralThread } from '@/components/ReferralThread';

const STEPS = [
  { title: 'Find roles that fit', body: 'Jobs from company career pages across India and remote, ranked by how well they match your resume.' },
  { title: 'Check your resume against the JD', body: 'A score with the reasons behind it, the gaps, ATS checks, and edits you approve one by one. Nothing is invented.' },
  { title: 'Reach the right people', body: 'Recruiters, managers, alumni of your college. Drafted notes you copy and send yourself on LinkedIn.' },
  { title: 'Track every application', body: 'One board from saved to offer, updated as referrals come through, with reminders to follow up.' },
];

export function LandingPage() {
  return (
    <div className="min-h-screen bg-paper">
      <header className="mx-auto flex max-w-content items-center justify-between px-5 py-5 sm:px-8">
        <Logo />
        <nav className="flex items-center gap-2">
          <Button variant="ghost" asChild><Link to="/login">Sign in</Link></Button>
          <Button asChild><Link to="/signup">Create account</Link></Button>
        </nav>
      </header>

      <main>
        <section className="mx-auto grid max-w-content gap-14 px-5 pb-20 pt-12 sm:px-8 lg:grid-cols-[1.1fr_1fr] lg:items-center lg:pt-20">
          <div>
            <h1 className="max-w-xl text-4xl font-semibold leading-[1.05] sm:text-[3.6rem] sm:leading-[1.02]">
              The best way into a tech job is through someone who works there.
            </h1>
            <p className="mt-6 max-w-lg text-lg text-slate">
              ReferBoard finds the roles that fit your resume, shows you who to ask, and helps you ask well.
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <Button size="lg" asChild><Link to="/signup">Create a free account</Link></Button>
              <Button size="lg" variant="outline" asChild><Link to="/login">Sign in</Link></Button>
              <TryDemoButton variant="ghost" />
            </div>
          </div>

          <figure className="rounded-lg border border-line bg-surface p-6 sm:p-8">
            <ReferralThread nodes={[
              { label: 'You', sub: 'Backend, 6 yrs', you: true },
              { label: 'Rohan Mehta', sub: 'IIT Roorkee alum' },
              { label: 'Tidepool', sub: 'Payments team' },
            ]} />
            <figcaption className="mt-8 border-t border-line pt-5 text-sm text-slate">
              <span className="font-medium text-ink">Senior Software Engineer, Payments</span>
              <span className="mt-1 block">81% match. Rohan is an engineering manager there and went to your college. ReferBoard
                drafts the note; you send it.</span>
            </figcaption>
          </figure>
        </section>

        <section className="border-t border-line bg-surface">
          <ol className="mx-auto grid max-w-content gap-x-10 gap-y-10 px-5 py-16 sm:grid-cols-2 sm:px-8 lg:grid-cols-4">
            {STEPS.map((s, i) => (
              <li key={s.title}>
                <span className="font-display text-2xl font-semibold text-marigold tabular">{i + 1}</span>
                <h2 className="mt-2 text-lg font-semibold">{s.title}</h2>
                <p className="mt-2 text-sm leading-relaxed text-slate">{s.body}</p>
              </li>
            ))}
          </ol>
        </section>
      </main>

      <footer className="mx-auto max-w-content px-5 py-10 text-sm text-slate sm:px-8">
        ReferBoard never messages anyone for you and never scrapes LinkedIn. Jobs come from public company job boards.
      </footer>
    </div>
  );
}
