import { Link, Route, Routes } from 'react-router-dom';
import { ArrowRight, Check, Droplets, ShieldCheck, Sparkles } from 'lucide-react';

const setupItems = [
  'React + Vite frontend scaffolded',
  'Spring Boot API scaffolded',
  'MySQL schema and sample data prepared',
];

function HomePage() {
  return (
    <main className="mx-auto grid w-full max-w-6xl flex-1 items-center gap-12 px-5 py-12 sm:px-8 lg:grid-cols-[1.1fr_0.9fr] lg:py-20">
      <section>
        <div className="mb-6 inline-flex items-center gap-2 rounded-full border border-teal-100 bg-white px-3 py-1.5 text-sm font-medium text-teal-800 shadow-sm">
          <Sparkles size={15} aria-hidden="true" />
          Laundry operations, made simpler
        </div>
        <h1 className="max-w-2xl text-4xl font-semibold tracking-tight text-ink sm:text-5xl lg:text-6xl">
          Good laundry days start with <span className="text-brand">SpindleTrack.</span>
        </h1>
        <p className="mt-5 max-w-xl text-lg leading-8 text-slate-600">
          A clear place for your shop team to track orders, payments, and pickup readiness.
        </p>
        <div className="mt-8 flex flex-wrap items-center gap-3">
          <Link
            to="/sign-in"
            className="inline-flex min-h-12 items-center justify-center gap-2 rounded-xl bg-brand px-5 py-3 font-semibold text-white shadow-sm transition hover:bg-teal-700 focus:outline-none focus:ring-4 focus:ring-teal-100"
          >
            Staff sign in <ArrowRight size={18} aria-hidden="true" />
          </Link>
          <span className="text-sm text-slate-500">Starter build · core workflows come next</span>
        </div>
      </section>

      <section aria-label="Project setup status" className="rounded-3xl border border-slate-100 bg-white p-6 shadow-soft sm:p-8">
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="text-sm font-semibold uppercase tracking-[0.14em] text-slate-500">Project setup</p>
            <h2 className="mt-2 text-2xl font-semibold text-ink">The foundation is in place</h2>
          </div>
          <div className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl bg-teal-50 text-brand">
            <Droplets size={24} aria-hidden="true" />
          </div>
        </div>
        <ul className="mt-7 space-y-4">
          {setupItems.map((item) => (
            <li key={item} className="flex items-center gap-3 text-slate-700">
              <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-emerald-50 text-emerald-700">
                <Check size={16} aria-hidden="true" />
              </span>
              {item}
            </li>
          ))}
        </ul>
        <div className="mt-8 flex items-start gap-3 rounded-2xl bg-slate-50 p-4 text-sm leading-6 text-slate-600">
          <ShieldCheck size={19} className="mt-0.5 shrink-0 text-slate-500" aria-hidden="true" />
          This is the initial project scaffold. Google sign-in, order lookup, payments, and shop workflows are not wired up yet.
        </div>
      </section>
    </main>
  );
}

function SignInPage() {
  return (
    <main className="mx-auto flex w-full max-w-xl flex-1 items-center px-5 py-16">
      <section className="w-full rounded-3xl border border-slate-100 bg-white p-7 text-center shadow-soft sm:p-10">
        <div className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-teal-50 text-brand">
          <Droplets size={28} aria-hidden="true" />
        </div>
        <h1 className="mt-5 text-2xl font-semibold text-ink">Sign-in setup is next</h1>
        <p className="mt-3 text-slate-600">
          Firebase Google Sign-In will be connected in a later implementation step. No credentials are needed for this starter screen.
        </p>
        <Link to="/" className="mt-7 inline-flex min-h-11 items-center rounded-xl px-4 font-semibold text-brand hover:bg-teal-50">
          Back to home
        </Link>
      </section>
    </main>
  );
}

export default function App() {
  return (
    <div className="flex min-h-screen flex-col bg-canvas">
      <header className="border-b border-slate-200/80 bg-white/90">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-5 sm:px-8">
          <Link to="/" className="flex items-center gap-2.5 rounded-lg text-ink focus:outline-none focus:ring-4 focus:ring-teal-100" aria-label="SpindleTrack home">
            <span className="grid h-9 w-9 place-items-center rounded-xl bg-brand text-white">
              <Droplets size={20} aria-hidden="true" />
            </span>
            <span className="text-lg font-semibold tracking-tight">SpindleTrack</span>
          </Link>
          <Link to="/sign-in" className="inline-flex min-h-11 items-center rounded-xl px-4 text-sm font-semibold text-slate-700 transition hover:bg-slate-100 focus:outline-none focus:ring-4 focus:ring-teal-100">
            Sign in
          </Link>
        </div>
      </header>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/sign-in" element={<SignInPage />} />
        <Route path="*" element={<HomePage />} />
      </Routes>
      <footer className="border-t border-slate-200/80 bg-white">
        <div className="mx-auto flex max-w-6xl flex-col gap-1 px-5 py-5 text-xs text-slate-500 sm:flex-row sm:items-center sm:justify-between sm:px-8">
          <span>SpindleTrack · Laundry shop operations</span>
          <span>Project scaffold v0.1.0</span>
        </div>
      </footer>
    </div>
  );
}
