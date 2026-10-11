import { useEffect, useRef, useState, type FormEvent } from 'react';
import { BookingPanel } from '../features/BookingPanel';
import { ResearchInspector } from '../features/ResearchInspector';
import { CardEditor } from '../features/CardEditor';
import { api, ApiError, terminal, fieldLabels, inputLabels, reasonText, safeLink, type Requirements, type Job, type Result, type Session, type Trip, type Detail, type Message, type Image } from '../shared/api';

const blank: Requirements = { departure: null, destinationIntent: null, country: 'IN', arrival: null, departureDate: null, budget: null, currency: 'INR', travelers: null, preferences: [] };
const date = (value: string) => new Date(value).toLocaleString('en-IN');
type Card = { job: Job; requirements: Requirements; result?: Result; images: Image[] };

function RequirementsForm({ value, change, locked = false }: { value: Requirements; change: (value: Requirements) => void; locked?: boolean }) {
  const set = (name: keyof Requirements, next: unknown) => change({ ...value, [name]: next });
  return <div className="form-grid">
    <label>Departure city<input value={value.departure || ''} maxLength={200} onChange={e => set('departure', e.target.value || null)} placeholder="e.g. Delhi" /></label>
    <label className="wide">{locked ? 'Selected destination' : 'Destinations to compare (up to 3, separated by commas)'}<input required value={value.destinationIntent || ''} maxLength={500} readOnly={locked} onChange={e => set('destinationIntent', e.target.value || null)} placeholder="Jaipur, Udaipur, Agra" /></label>
    <label>Arrival date<input type="date" value={value.arrival || ''} onChange={e => set('arrival', e.target.value || null)} /></label>
    <label>Return date<input type="date" min={value.arrival || undefined} value={value.departureDate || ''} onChange={e => set('departureDate', e.target.value || null)} /></label>
    <label>Travelers<input type="number" min="1" max="20" value={value.travelers ?? ''} onChange={e => set('travelers', e.target.value ? Number(e.target.value) : null)} /></label>
    <label>Total budget<input type="number" min="0.01" step="0.01" value={value.budget || ''} onChange={e => set('budget', e.target.value || null)} /></label>
    <label>Currency<input value={value.currency || ''} pattern="[A-Z]{3}" maxLength={3} onChange={e => set('currency', e.target.value.toUpperCase() || null)} /></label>
    <label className="wide">Preferences & requirements (one per line)<textarea maxLength={4000} value={value.preferences.join('\n')} onChange={e => set('preferences', e.target.value.split('\n'))} placeholder="Low walking distances&#10;Vegetarian food" /></label>
  </div>;
}
function Evidence({ result }: { result: Result }) {
  return <div className="evidence">
    <p className="muted">Validated {date(result.validatedAt)} · {result.complete ? 'Complete' : 'Research with gaps'}</p>
    {result.missingRequirements.length > 0 && <p className="gap">Details still needed: {result.missingRequirements.map(key => inputLabels[key] || key).join(', ')}.</p>}
    {result.fields.map(field => <section key={field.field} className="fact">
      <div className="fact-heading"><h4>{fieldLabels[field.field]}</h4><span className={'badge ' + field.state.toLowerCase()}>{field.state.toLowerCase()}</span></div>
      {field.state === 'SUPPORTED' && field.value && <blockquote>{field.value}</blockquote>}
      <p className="muted">{reasonText(field.reason)}</p>
      {field.citations.length > 0 && <details><summary>{field.state === 'CONFLICTING' ? 'Review conflicting excerpts' : 'Sources & exact excerpts'} ({field.citations.length})</summary>
        {field.citations.map((citation, i) => <div className="citation" key={citation.passageId + i}><blockquote>{citation.quote}</blockquote><a href={safeLink(citation.url)} target="_blank" rel="noreferrer">Read source ↗</a><small>Retrieved {date(citation.retrievedAt)}</small></div>)}
      </details>}
    </section>)}
    <div className="estimate"><strong>Stay duration: </strong>{result.durationEstimate.state === 'ESTIMATED' ? `${result.durationEstimate.nights} nights · date-based estimate` : 'Unavailable — clarify dates'}
      {result.durationEstimate.assumptions.map(text => <p key={text}>{text}</p>)}<p>Total cost estimate unavailable: no validated numeric prices. Your entered budget is a limit, not a quote.</p>
    </div>
  </div>;
}
function SourceImage({ images }: { images: Image[] }) {
  const [broken, setBroken] = useState(false);
  const image = images[0];
  if (!image || broken) return <div className="image-gap">Source image unavailable</div>;
  return <figure><img src={image.url} alt={image.title} loading="lazy" referrerPolicy="no-referrer" onError={() => setBroken(true)} /><figcaption>Source illustration · <a href={safeLink(image.sourceUrl)} target="_blank" rel="noreferrer">{image.author}</a> · <a href={safeLink(image.licenseUrl)} target="_blank" rel="noreferrer">{image.license}</a><small>Metadata retrieved {date(image.retrievedAt)}</small></figcaption></figure>;
}

export function App() {
  const [account, setAccount] = useState<{ email: string } | null>(null);
  const [ready, setReady] = useState(false);
  const [register, setRegister] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [sessionId, setSessionId] = useState('');
  const [requirements, setRequirements] = useState<Requirements>(blank);
  const [clarification, setClarification] = useState<string[]>([]);
  const [cards, setCards] = useState<Card[]>([]);
  const [comparison, setComparison] = useState<string[]>([]);
  const [trips, setTrips] = useState<Trip[]>([]);
  const [trip, setTrip] = useState<Detail | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [focus, setFocus] = useState('ALL');
  const [question, setQuestion] = useState('');
  const [tripRequirements, setTripRequirements] = useState<Requirements>(blank);
  const [connection, setConnection] = useState('');
  const [editing, setEditing] = useState<Card | null>(null);
  const [trash, setTrash] = useState(false);
  const [filter, setFilter] = useState('');
  const [notice, setNotice] = useState('');
  const editTrigger = useRef<HTMLButtonElement | null>(null);
  const generation = useRef(0);
  const streams = useRef<EventSource[]>([]);
  const actionKey = useRef<{ payload: string; key: string } | null>(null);
  const currentTripId = useRef<string | null>(null);
  currentTripId.current = trip?.context.id || null;
  const clearStreams = () => { streams.current.forEach(stream => stream.close()); streams.current = []; };
  const report = (failure: unknown) => { setError(failure instanceof Error ? failure.message : 'Something went wrong. Please retry.'); if (failure instanceof ApiError && failure.status === 401) { generation.current++; clearStreams(); setAccount(null); setCards([]); setTrip(null); setSessions([]); setTrips([]); setMessages([]); } };
  const perform = async (action: () => Promise<void>) => { setBusy(true); setError(''); try { await action(); } catch (failure) { report(failure); } finally { setBusy(false); } };
  useEffect(() => { let mounted = true; api<{ email: string }>('/api/identity/me').then(value => { if (mounted) setAccount(value); }).catch(failure => { if (mounted && !(failure instanceof ApiError && failure.status === 401)) report(failure); }).finally(() => { if (mounted) setReady(true); }); return () => { mounted = false; clearStreams(); }; }, []);
  useEffect(() => { if (account) api<Session[]>('/api/planning/sessions').then(setSessions).catch(report); }, [account]);

  async function card(job: Job): Promise<Card> {
    const revision = await api<{ requirements: Requirements }>(`/api/planning/revisions/${job.revisionId}`);
    let result: Result | undefined;
    if (terminal(job)) { try { result = await api<Result>(`/api/research/jobs/${job.id}/result`); } catch (failure) { if (!(failure instanceof ApiError && failure.code === 'RESULT_NOT_READY')) throw failure; } }
    return { job, requirements: revision.requirements, result, images: await api<Image[]>(`/api/research/jobs/${job.id}/images`) };
  }
  async function watch(job: Job, token: number) {
    if (terminal(job)) return;
    const stream = new EventSource(`/api/research/jobs/${job.id}/events`);
    streams.current.push(stream);
    let reading = false;
    const update = async () => {
      if (reading || token !== generation.current) return;
      reading = true;
      try {
        const latest = await api<Job>(`/api/research/jobs/${job.id}`);
        const refreshed = await card(latest);
        if (token !== generation.current) return;
        setCards(previous => previous.map(value => value.job.id === job.id ? { ...refreshed, job: { ...refreshed.job, cardTitle: value.job.cardTitle, cardNotes: value.job.cardNotes, cardDeletedAt: value.job.cardDeletedAt } } : value));
        setConnection('Live progress connected');
        if (terminal(latest)) {
          stream.close();
          const selected = currentTripId.current;
          if (selected) { const detail = await api<Detail>(`/api/planning/trips/${selected}?includeDeleted=true`); if (token === generation.current && currentTripId.current === selected) setTrip(detail); }
        }
      } catch (failure) { if (token === generation.current) report(failure); } finally { reading = false; }
    };
    for (const event of ['JOB_QUEUED', 'JOB_RUNNING', 'TASK_STARTED', 'TASK_FINISHED', 'SOURCE_SAVED', 'RESULT_VALIDATED', 'JOB_FINISHED', 'JOB_STATE']) stream.addEventListener(event, () => { void update(); });
    stream.onopen = () => { void update(); };
    stream.onerror = () => { if (token === generation.current) setConnection('Progress reconnecting… saved jobs can be reopened.'); };
    void update();
  }
  async function showSession(id: string) {
    setTrash(false); setFilter(''); setNotice('');
    const token = ++generation.current; clearStreams(); setTrip(null); setCards([]); setComparison([]); setMessages([]); setSessionId(id); setConnection('');
    const [jobs, selected] = await Promise.all([api<Job[]>(`/api/planning/sessions/${id}/jobs?includeDeleted=true`), api<Trip[]>(`/api/planning/sessions/${id}/selected-trips`)]);
    const loaded = await Promise.all(jobs.map(card));
    if (token !== generation.current) return;
    setCards(loaded); setTrips(selected); setRequirements(loaded[0]?.requirements || blank); loaded.forEach(value => { void watch(value.job, token); });
  }
  async function openTrip(selected: Trip) {
    setTrash(false); setFilter(''); setNotice('');
    const token = ++generation.current; clearStreams(); setComparison([]); setCards([]); setMessages([]);
    const [detail, history] = await Promise.all([api<Detail>(`/api/planning/trips/${selected.id}?includeDeleted=true`), api<Message[]>(`/api/planning/trips/${selected.id}/messages`)]);
    const loaded = await Promise.all(detail.jobs.map(card));
    if (token !== generation.current) return;
    setTrip(detail); setTripRequirements(detail.requirements); setMessages(history); setCards(loaded); setQuestion(''); setFocus('ALL'); actionKey.current = null;
    loaded.forEach(value => { void watch(value.job, token); });
  }
  async function research(event: FormEvent) {
    event.preventDefault();
    await perform(async () => {
      const normalized = { ...requirements, preferences: requirements.preferences.map(text => text.trim()).filter(Boolean) };
      const destinations = [...new Set((normalized.destinationIntent || '').split(',').map(text => text.trim()).filter(Boolean))];
      if (destinations.length < 1 || destinations.length > 3) throw new Error('Enter one to three destinations in India.');
      const gaps = await api<{ missing: string[] }>('/api/planning/clarification', normalized); setClarification(gaps.missing);
      let id = sessionId;
      if (!id) { const session = await api<Session>('/api/planning/sessions', { title: destinations.join(' · ').slice(0, 120) }); id = session.id; setSessionId(id); setSessions(previous => [session, ...previous]); }
      for (const destination of destinations) {
        const revision = await api<{ id: string }>(`/api/planning/sessions/${id}/revisions`, { ...normalized, destinationIntent: destination });
        await api<Job>(`/api/planning/sessions/${id}/jobs`, { revisionId: revision.id, requestKey: crypto.randomUUID() });
      }
      await showSession(id);
    });
  }
  async function followup(event: FormEvent) {
    event.preventDefault(); if (!trip) return;
    await perform(async () => {
      const payload = { requirements: { ...tripRequirements, preferences: tripRequirements.preferences.map(text => text.trim()).filter(Boolean) }, question: question.trim() || (focus === 'ALL' ? 'Refresh and explore this destination.' : `Research ${fieldLabels[focus]}.`), focus };
      const signature = JSON.stringify({ id: trip.context.id, ...payload });
      if (actionKey.current?.payload !== signature) actionKey.current = { payload: signature, key: crypto.randomUUID() };
      await api<Job>(`/api/planning/trips/${trip.context.id}/research`, { ...payload, requestKey: actionKey.current.key });
      await openTrip(trip.context);
    });
  }
  async function select(value: Card) {
    await perform(async () => {
      const selected = await api<Trip>(`/api/planning/sessions/${sessionId}/selected-trips`, { jobId: value.job.id });
      setTrips(await api<Trip[]>(`/api/planning/sessions/${sessionId}/selected-trips`)); await openTrip(selected);
    });
  }
  async function saveCard(id: string, title: string, notes: string) {
    const updated = await api<Job>(`/api/planning/cards/${id}`, { title, notes }, false, 'PUT');
    setCards(previous => previous.map(value => value.job.id === id ? { ...value, job: updated } : value));
    setNotice('Card saved.');
  }
  async function moveCard(value: Card, restore = false) {
    await perform(async () => {
      if (restore) await api(`/api/planning/cards/${value.job.id}/restore`, {});
      else await api(`/api/planning/cards/${value.job.id}`, undefined, false, 'DELETE');
      const updated = await api<Job>(`/api/planning/cards/${value.job.id}`);
      setCards(previous => previous.map(item => item.job.id === updated.id ? { ...item, job: updated } : item));
      setComparison(previous => previous.filter(id => id !== updated.id));
      setNotice(restore ? 'Card restored.' : 'Card moved to Trash. You can restore it anytime. Selected trips and saved evidence are retained.');
    });
  }
  const activeCards = cards.filter(value => !value.job.cardDeletedAt);
  const visibleCards = cards.filter(value => Boolean(value.job.cardDeletedAt) === trash && `${value.job.cardTitle || ''} ${value.requirements.destinationIntent || ''} ${value.job.cardNotes || ''}`.toLowerCase().includes(filter.toLowerCase()));
  const comparing = activeCards.filter(value => comparison.includes(value.job.id));
  if (!ready) return <main className="shell"><p role="status">Loading your workspace…</p></main>;
  return <main className="shell">
    <a className="skip-link" href="#workspace-main">Skip to workspace</a>
    <header><div><p className="eyebrow">TRIPPILOT / INDIA</p><h1>Go with a clearer picture.</h1><p className="intro">Research places, compare the evidence, and keep each journey in its own space.</p></div>{account && <div className="account"><span>{account.email}</span><button className="secondary" disabled={busy} onClick={() => void perform(async () => { await api('/api/identity/logout', {}); generation.current++; clearStreams(); setAccount(null); setSessionId(''); setCards([]); setTrip(null); setTrips([]); setMessages([]); setComparison([]); setRequirements(blank); })}>Sign out</button></div>}</header>
    {error && <p role="alert" className="error">{error}</p>}
    {!account ? <div className="auth-layout"><section className="auth-story"><span className="brand-mark" aria-hidden="true">↗</span><p className="eyebrow">LESS GUESSWORK. MORE EXPLORING.</p><h2>Your next journey starts with a little clarity.</h2><p>Discover India through travel guides, compare what matters, and build a collection that feels like you.</p><div className="story-points"><span>01 · Explore destinations</span><span>02 · Compare saved evidence</span><span>03 · Keep your plans together</span></div><div className="route-art" aria-hidden="true"><span>DELHI</span><i /><span>JAIPUR</span><i /><span>UDAIPUR</span></div></section><section className="panel auth" id="workspace-main"><p className="eyebrow">YOUR RESEARCH WORKSPACE</p><h2>{register ? 'Create an account' : 'Welcome back'}</h2><p>Save research and return to your selected trips.</p>
      <form onSubmit={event => { event.preventDefault(); const data = new FormData(event.currentTarget); void perform(async () => { const email = String(data.get('email')); const password = String(data.get('password')); if (register) await api('/api/identity/register', { email, password }); await api('/api/identity/login', new URLSearchParams({ username: email, password }).toString(), true); setAccount(await api('/api/identity/me')); }); }}>
        <label>Email<input type="email" name="email" required maxLength={191} autoComplete="username" /></label><label>Password<input type="password" name="password" required minLength={register ? 12 : 1} maxLength={72} autoComplete={register ? 'new-password' : 'current-password'} /></label><button disabled={busy}>{busy ? 'Please wait…' : register ? 'Create account & sign in' : 'Sign in'}</button>
      </form><button className="text-button" onClick={() => setRegister(!register)} disabled={busy}>{register ? 'Already have an account? Sign in' : 'New here? Create an account'}</button>
    </section></div> : <div className="workspace">
      <aside className="panel sidebar"><h2>Your plans</h2><button className="secondary" disabled={busy} onClick={() => { generation.current++; clearStreams(); setSessionId(''); setTrip(null); setCards([]); setTrips([]); setComparison([]); setRequirements(blank); setClarification([]); setTrash(false); setFilter(''); setNotice(''); }}>+ New plan</button>
        <nav aria-label="Saved plans">{sessions.map(session => <button key={session.id} aria-current={session.id === sessionId ? 'page' : undefined} className={session.id === sessionId ? 'nav-item active' : 'nav-item'} disabled={busy} onClick={() => void perform(() => showSession(session.id))}>{session.title}<small>{date(session.createdAt)}</small></button>)}</nav>
        {sessions.length === 0 && <p className="muted">Your saved plans will appear here. Start with a destination you’re curious about.</p>}
        {sessionId && <details className="plan-settings"><summary>Rename plan</summary><form key={sessionId + sessions.find(item => item.id === sessionId)?.title} onSubmit={event => { event.preventDefault(); const title = String(new FormData(event.currentTarget).get('title')).trim(); if (!title) return; void perform(async () => { const updated = await api<Session>(`/api/planning/sessions/${sessionId}`, { title }, false, 'PUT'); setSessions(previous => previous.map(item => item.id === updated.id ? updated : item)); setNotice('Plan renamed.'); }); }}><label>Plan name<input name="title" required maxLength={120} defaultValue={sessions.find(item => item.id === sessionId)?.title || ''} /></label><button disabled={busy}>Save plan name</button></form></details>}
        {trips.length > 0 && <><h3>Selected trips</h3>{trips.map(selected => <button className={trip?.context.id === selected.id ? 'nav-item active' : 'nav-item'} key={selected.id} disabled={busy} onClick={() => void perform(() => openTrip(selected))}>{selected.destination}<small>Separate research & follow-ups</small></button>)}</>}
      </aside>
      <div className="content" id="workspace-main" tabIndex={-1}>
        <section className="workspace-overview"><div><p className="eyebrow">YOUR TRAVEL WORKSPACE</p><h2>{trip ? trip.context.destination : sessions.find(item => item.id === sessionId)?.title || 'A new journey awaits.'}</h2><p>{trip ? 'A dedicated space for this destination.' : 'Collect ideas. Review the evidence. Make room for the next adventure.'}</p></div><div className="workspace-stats"><span><strong>{activeCards.length}</strong>research cards</span><span><strong>{trips.length}</strong>selected trips</span></div></section>
        {notice && <p className="success" role="status">{notice}</p>}
        <p className="scope-notice">India travel guides · commercial inventory and current quotes unavailable · optional booking demonstration in selected trips</p>
        {trip ? <section className="panel"><button className="text-button" disabled={busy} onClick={() => void perform(() => showSession(sessionId))}>← Back to comparison</button><p className="eyebrow">SELECTED TRIP / PRIVATE CONTEXT</p><h2>{trip.context.destination}</h2><p>Dates, requirements, research and follow-ups here belong only to this trip. Choose a topic for a fresh search; your question is saved as context. The current harness returns validated excerpts rather than free-form answers.</p>
          <details><summary>Evidence at selection</summary><Evidence result={trip.selectedResult} /></details>
          <form onSubmit={event => void followup(event)}><RequirementsForm value={tripRequirements} change={setTripRequirements} locked /><label htmlFor="research-topic">Research topic</label><select id="research-topic" value={focus} onChange={event => setFocus(event.target.value)}><option value="ALL">Deep research / refresh all fields</option>{Object.entries(fieldLabels).map(([key, label]) => <option value={key} key={key}>{label}</option>)}</select><label>Follow-up question or requirement<textarea value={question} maxLength={2000} onChange={event => setQuestion(event.target.value)} placeholder="What should I check before choosing a stay?" /></label><p className="muted">Every follow-up retrieves new sources. Prices, availability and policies remain gaps until a scoped provider supplies them.</p><button disabled={busy}>{busy ? 'Starting…' : 'Research / refresh this trip'}</button></form>
        </section> : <section className="panel"><details className="research-composer" open={cards.length === 0 || clarification.length > 0}><summary>{cards.length > 0 ? 'Research more destinations' : 'Start a new journey'}</summary><p className="eyebrow">START WITH YOUR NEEDS</p><h2>Where would you like to go?</h2><p>Pick up to three Indian destinations. Leave unknown details blank; guides can still help you explore.</p><form onSubmit={event => void research(event)}><RequirementsForm value={requirements} change={setRequirements} />{clarification.length > 0 && <p className="gap">To tailor the plan, clarify: {clarification.map(key => inputLabels[key]).join(', ')}. These remain unknown in your research.</p>}<button disabled={busy}>{busy ? 'Starting…' : 'Research destinations'}</button></form></details></section>}
        {trip && <BookingPanel key={trip.context.id + JSON.stringify(trip.requirements)} tripId={trip.context.id} requirements={trip.requirements} />}
        {trip && messages.length > 0 && <section className="panel"><h3>Follow-ups in this trip</h3>{messages.map(message => <div className="message" key={message.id}><p>{message.content}</p><small>{date(message.createdAt)} · <a href={'#job-' + message.jobId}>View research response</a></small></div>)}</section>}
        {comparing.length > 1 && <section className="panel comparison"><h2>Compare the evidence</h2><p>Review suitability yourself; missing values are never ranked as bargains.</p><div className="table-scroll"><table><caption>Current validated guide records</caption><thead><tr><th>Field</th>{comparing.map(value => <th key={value.job.id}>{value.requirements.destinationIntent}</th>)}</tr></thead><tbody>{Object.entries(fieldLabels).map(([key, label]) => <tr key={key}><th>{label}</th>{comparing.map(value => { const field = value.result?.fields.find(item => item.field === key); return <td key={value.job.id}><strong>{field?.state.toLowerCase() || value.job.status.toLowerCase()}</strong><p>{field?.state === 'SUPPORTED' ? field.value : field ? reasonText(field.reason) : 'Research pending'}</p></td>; })}</tr>)}</tbody></table></div></section>}
        {cards.length > 0 && <section aria-label="Research options"><div className="section-heading"><div><p className="eyebrow">YOUR COLLECTION</p><h2>{trip ? 'Research revisions' : 'Your options'}</h2></div><span role="status" className="muted">{connection}</span></div><div className="collection-toolbar"><div className="segmented" aria-label="Card view"><button className={!trash ? 'active' : ''} aria-pressed={!trash} onClick={() => setTrash(false)}>Cards ({activeCards.length})</button><button className={trash ? 'active' : ''} aria-pressed={trash} onClick={() => setTrash(true)}>Trash ({cards.length - activeCards.length})</button></div><label className="search-label">Search cards<input type="search" value={filter} onChange={event => setFilter(event.target.value)} placeholder="Destination, title or notes" /></label></div>{visibleCards.length === 0 && <div className="empty-state"><h3>{trash ? 'Nothing in Trash' : 'No cards found'}</h3><p>{trash ? 'Deleted cards appear here and can be restored.' : 'Try another search or research a new destination.'}</p></div>}<div className="cards">{visibleCards.map(value => <article className="panel trip-card" id={'job-' + value.job.id} key={value.job.id}>
          <SourceImage images={value.images} /><div className="card-body"><p className="eyebrow">{value.job.status.toLowerCase()} · {date(value.job.createdAt)}</p><h3>{value.job.cardTitle || value.requirements.destinationIntent}</h3>{value.job.cardTitle && <p className="destination-label">{value.requirements.destinationIntent}</p>}<p className="muted">{value.requirements.departure || 'Origin unknown'} · {value.requirements.arrival || 'Dates unknown'}{value.requirements.departureDate ? ` → ${value.requirements.departureDate}` : ''} · {value.requirements.travelers ?? '?'} travelers</p>
            {value.job.cardNotes && <div className="personal-note"><strong>Your notes</strong><p>{value.job.cardNotes}</p><small>Personal notes · not validated evidence</small></div>}
            {value.result && <><div className="coverage" aria-label="Evidence coverage">{value.result.fields.map(field => <span key={field.field} className={'coverage-item ' + field.state.toLowerCase()} title={reasonText(field.reason)}>{fieldLabels[field.field]} · {field.state.toLowerCase()}</span>)}</div>{value.result.fields.find(field => field.field === 'DESCRIPTION')?.state === 'SUPPORTED' && <blockquote className="card-excerpt">{value.result.fields.find(field => field.field === 'DESCRIPTION')?.value}</blockquote>}</>}
            {terminal(value.job) && value.job.outcomeCode && !['VALIDATED_COMPLETE', 'VALIDATED_WITH_GAPS'].includes(value.job.outcomeCode) && <p role="alert" className="error">Research could not finish: {reasonText(value.job.outcomeCode)}</p>}
            {value.result ? <details className="research-details"><summary>Guide details & sources</summary><Evidence result={value.result} /></details> : <p className="gap" role="status">{terminal(value.job) ? `No validated result. ${reasonText(value.job.outcomeCode || value.job.status)}` : 'Research is running. Evidence appears after validation.'}</p>}
            {trash ? <div className="actions"><button disabled={busy} onClick={() => void moveCard(value, true)}>Restore card</button><small>Saved evidence and selected trips remain available.</small></div> : <><div className="actions">{!trip && <><label className="check"><input type="checkbox" checked={comparison.includes(value.job.id)} onChange={event => setComparison(previous => event.target.checked ? [...previous, value.job.id] : previous.filter(id => id !== value.job.id))} /> Compare</label><button disabled={busy || !terminal(value.job) || !value.result?.fields.some(field => ['SUPPORTED', 'CONFLICTING'].includes(field.state))} onClick={() => void select(value)}>{value.result?.fields.some(field => field.state === 'CONFLICTING') ? 'Select with source differences' : 'Select trip'}</button></>}
              {!terminal(value.job) ? <button className="secondary" disabled={busy || value.job.status === 'CANCEL_REQUESTED'} onClick={() => void perform(async () => { await api(`/api/research/jobs/${value.job.id}/cancel`, {}); const latest = await card(await api<Job>(`/api/research/jobs/${value.job.id}`)); setCards(previous => previous.map(item => item.job.id === latest.job.id ? latest : item)); })}>Cancel research</button> : !trip && <button className="secondary" disabled={busy} onClick={() => void perform(async () => { await api(`/api/planning/sessions/${sessionId}/jobs`, { revisionId: value.job.revisionId, requestKey: crypto.randomUUID(), retryOf: value.job.id }); await showSession(sessionId); })}>Refresh sources</button>}
            </div><div className="card-tools"><button className="text-button" disabled={busy} onClick={event => { editTrigger.current = event.currentTarget; setEditing(value); }}>Edit card</button><button className="text-button danger" disabled={busy || !terminal(value.job)} title={!terminal(value.job) ? 'Cancel research and wait for it to finish first' : 'Move to Trash; restore anytime'} onClick={() => void moveCard(value)}>Delete card</button></div></>}<ResearchInspector jobId={value.job.id} />
          </div></article>)}</div></section>}
      </div>
    </div>}
    {editing && <CardEditor title={editing.job.cardTitle || editing.requirements.destinationIntent || 'Research card'} notes={editing.job.cardNotes || ''} close={() => { setEditing(null); editTrigger.current?.focus(); }} save={(title, notes) => saveCard(editing.job.id, title, notes)} />}
    <footer>Evidence helps you decide. Exact excerpts do not guarantee semantic truth; check sources and provider details before travel.</footer>
  </main>;
}


