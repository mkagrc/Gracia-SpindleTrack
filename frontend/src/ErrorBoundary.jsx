import { Component } from 'react';

export default class ErrorBoundary extends Component {
  state = { hasError: false };

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  componentDidCatch() {
    // Keep diagnostics generic; never log messages, props, tokens, or personal data.
    console.error('[SpindleTrack] A rendering error was caught; details omitted.');
  }

  render() {
    if (this.state.hasError) {
      return (
        <main className="flex min-h-screen flex-col items-center justify-center bg-slate-50 px-5 text-center text-slate-900" role="alert">
          <h1 className="text-2xl font-semibold">Something went wrong. Reload the page</h1>
          <p className="mt-2 max-w-md text-sm text-slate-600">An unexpected display error stopped this page from rendering.</p>
          <button type="button" onClick={() => window.location.reload()} className="mt-5 min-h-11 rounded-xl bg-teal-700 px-5 text-sm font-semibold text-white hover:bg-teal-800">
            Reload page
          </button>
        </main>
      );
    }
    return this.props.children;
  }
}
