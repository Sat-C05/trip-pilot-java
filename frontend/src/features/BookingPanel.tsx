import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, type Requirements } from '../shared/api';

type Scope = { destination: string; country: string; arrival: string; departure: string; travelers: number; currency: string };
type Quote = { id: string; itemId: string; name: string; scope: Scope; total: string; expiresAt: string; policy: string };
type SavedQuote = { id: string; contextId: string; revisionId: string; quote: Quote };
type Item = { id: string; name: string; nightlyPerTraveler: string; remaining: number; currency: string };
type Attempt = { id: string; quoteId: string; state: string; ceiling: string; authorizedUntil: string; bookingId: string | null; outcome: string | null };
type Capability = { enabled: boolean; environmentLabel: string };

export function BookingPanel({ tripId, requirements }: { tripId: string; requirements: Requirements }) {
  const [capability, setCapability] = useState<Capability>();
  const [items, setItems] = useState<Item[]>([]);
  const [quote, setQuote] = useState<SavedQuote>();
  const [attempts, setAttempts] = useState<Attempt[]>([]);
  const [ceiling, setCeiling] = useState('');
  const [accepted, setAccepted] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(Date.now());
  const authorizationKey = useRef<{ payload: string; key: string } | undefined>(undefined);
  const base = `/api/booking/trips/${tripId}`;
  const load = async () => setAttempts(await api<Attempt[]>(`${base}/attempts`));
  useEffect(() => {
    let mounted = true;
    Promise.all([api<Capability>('/api/booking/capability'), api<Attempt[]>(`${base}/attempts`)]).then(([configuration, history]) => { if (mounted) { setCapability(configuration); setAttempts(history); } }).catch(failure => { if (mounted) setError(failure.message); });
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => { mounted = false; clearInterval(timer); };
  }, [base]);
  const act = async (action: () => Promise<void>) => { setBusy(true); setError(''); try { await action(); } catch (failure) { setError(failure instanceof Error ? failure.message : 'Request failed. Check saved booking status before trying again.'); } finally { setBusy(false); } };
  async function authorize(event: FormEvent) {
    event.preventDefault(); if (!quote) return;
    await act(async () => {
      const payload = { quoteId: quote.id, ceiling, authorizedUntil: quote.quote.expiresAt, accepted };
      const signature = JSON.stringify(payload);
      if (authorizationKey.current?.payload !== signature) authorizationKey.current = { payload: signature, key: crypto.randomUUID() };
      await api(`${base}/authorizations`, { ...payload, requestKey: authorizationKey.current.key });
      await load();
    });
  }
  const expired = quote && new Date(quote.quote.expiresAt).getTime() <= now;
  const incomplete = !requirements.arrival || !requirements.departureDate || !requirements.travelers || !requirements.budget || !requirements.currency || !requirements.departure;
  const active = attempts.some(attempt => ['AUTHORIZED', 'SUBMITTING', 'UNCERTAIN', 'CONFIRMED'].includes(attempt.state));
  return <section className="panel booking-panel" aria-label="Booking demonstration">
    <p className="eyebrow">BOOKING DEMONSTRATION</p><h2>Review a stay before authorizing</h2>
    <p className="scope-notice">{capability?.environmentLabel || 'Loading provider configuration…'}</p>
    {error && <p role="alert" className="error">{error}</p>}
    {capability && !capability.enabled && <p className="gap">Booking is disabled. Enabling the capability still requires your authorization for a specific stay.</p>}
    {capability?.enabled && <>
      <p>Only the stay shown in the quote is included. Guide research and total trip cost remain separate.</p>
      {incomplete && <p className="gap">Save departure, future dates, travelers, currency and budget using the research form above, then request inventory.</p>}
      <button className="secondary" disabled={busy || incomplete || active} onClick={() => void act(async () => { const inventory = await api<{ items: Item[] }>(`${base}/inventory`, {}); setItems(inventory.items); setQuote(undefined); setAccepted(false); if (!inventory.items.length) setError('No provider inventory for this destination.'); })}>Check stay inventory</button>
      {items.map(item => <div className="booking-item" key={item.id}><p><strong>{item.name}</strong> · {item.currency} {item.nightlyPerTraveler} per traveler per night · {item.remaining} group stays remaining</p><button disabled={busy || !item.remaining || active} onClick={() => void act(async () => { const saved = await api<SavedQuote>(`${base}/quotes`, { itemId: item.id }); setQuote(saved); setCeiling(saved.quote.total); setAccepted(false); authorizationKey.current = undefined; })}>Get quote</button></div>)}
    </>}
    {quote && <div className="booking-quote"><h3>{quote.quote.name}</h3><p>{quote.quote.scope.destination} · {quote.quote.scope.arrival} → {quote.quote.scope.departure} · {quote.quote.scope.travelers} travelers</p><p><strong>All-inclusive stay total: {quote.quote.scope.currency} {quote.quote.total}</strong></p><p>{quote.quote.policy}</p><p className={expired ? 'gap' : 'muted'}>{expired ? 'Quote expired. Request a fresh quote.' : `Quote valid until ${new Date(quote.quote.expiresAt).toLocaleString('en-IN')}`}</p>
      {capability?.enabled && !active && <form onSubmit={event => void authorize(event)}>
        <label>Maximum authorized stay spend<input type="number" min="0.01" step="0.01" max={requirements.budget || undefined} value={ceiling} onChange={event => setCeiling(event.target.value)} required /></label>
        <p className="muted">Saved total trip budget: {requirements.currency} {requirements.budget}. This ceiling authorizes one stay only, until the quote expires.</p>
        <label className="check"><input type="checkbox" required checked={accepted} onChange={event => setAccepted(event.target.checked)} /> I authorize this exact stay, dates, travelers, policy and spending ceiling.</label>
        <button disabled={busy || expired || !accepted}>Authorize this stay</button>
      </form>}
    </div>}
    {attempts.length > 0 && <div className="booking-history"><h3>Saved booking attempts</h3>{attempts.map(attempt => <article key={attempt.id}>
      <p role="status"><strong>Stay status: {attempt.state.toLowerCase()}</strong>{attempt.bookingId && ` · Reference ${attempt.bookingId}`}</p>
      <p>Ceiling {attempt.ceiling} · authorization ends {new Date(attempt.authorizedUntil).toLocaleString('en-IN')}</p>
      {attempt.outcome && <p className="muted">{attempt.outcome.toLowerCase().replaceAll('_', ' ')}</p>}
      {['SUBMITTING', 'UNCERTAIN'].includes(attempt.state) && <p className="gap">The outcome is unresolved. Check provider status; a new submission is blocked to prevent duplicates.</p>}
      <div className="actions"><button className="secondary" disabled={busy} onClick={() => void act(async () => setQuote(await api<SavedQuote>(`/api/booking/quotes/${attempt.quoteId}`)))}>Review saved quote</button>
        {attempt.state === 'AUTHORIZED' && <><button disabled={busy || !capability?.enabled || new Date(attempt.authorizedUntil).getTime() <= now} onClick={() => void act(async () => { await api(`/api/booking/attempts/${attempt.id}/execute`, {}); await load(); })}>Submit authorized stay</button><button className="secondary" disabled={busy} onClick={() => void act(async () => { await api(`/api/booking/attempts/${attempt.id}/revoke`, {}); await load(); })}>Revoke authorization</button></>}
        {['SUBMITTING', 'UNCERTAIN'].includes(attempt.state) && <button disabled={busy} onClick={() => void act(async () => { await api(`/api/booking/attempts/${attempt.id}/status`, {}); await load(); })}>Check provider status</button>}
      </div>
    </article>)}</div>}
  </section>;
}
