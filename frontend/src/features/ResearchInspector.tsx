import { useState } from 'react';
import { api, fieldLabels, reasonText, safeLink, type Result } from '../shared/api';

type Event = { id: number; type: string; createdAt: string; data: Record<string, unknown> };
type Source = { id: string; title: string; finalUrl: string; retrievedAt: string; contentHash: string; passages: { id: string; startOffset: number; endOffset: number; text: string }[] };
type Inspection = { job: { id: string; traceId: string; revisionId: string; contextId: string; status: string; modelTurns: number; searches: number; pages: number }; retryOf: string | null; focus: string; model: { modelId: string; artifactFingerprint: string; runtimeVersion: string; contextTokens: number; promptVersion: string }; budgets: { modelTurns: number; searches: number; pages: number; jobSeconds: number; outputRepairs: number; traceRetentionDays: number }; tasks: { id: string; kind: string; status: string; outcomeCode: string | null }[]; sources: Source[]; result: (Result & { validatorVersion: string; decisions: { field: string; code: string }[] }) | null };

export function ResearchInspector({ jobId }: { jobId: string }) {
  const [open, setOpen] = useState(false);
  const [inspection, setInspection] = useState<Inspection | null>(null);
  const [events, setEvents] = useState<Event[]>([]);
  const [more, setMore] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function load(append = false) {
    setBusy(true); setError('');
    try {
      const cursor = append ? events.at(-1)?.id || 0 : 0;
      const [saved, page] = await Promise.all([api<Inspection>(`/api/research/jobs/${jobId}/inspector`), api<Event[]>(`/api/research/jobs/${jobId}/event-log?after=${cursor}`)]);
      setInspection(saved); setEvents(previous => append ? [...previous, ...page] : page); setMore(page.length === 100);
    } catch (failure) { setError(failure instanceof Error ? failure.message : 'Inspector could not load.'); }
    finally { setBusy(false); }
  }
  const anchor = (id: string) => `inspect-${jobId}-${id}`;
  function reveal(id: string) {
    let target: HTMLElement | null = document.getElementById(anchor(id));
    while (target) { if (target instanceof HTMLDetailsElement) target.open = true; target = target.parentElement; }
  }
  return <section className="inspector" aria-label="Research Inspector">
    <button className="secondary" aria-expanded={open} onClick={() => { setOpen(!open); if (!open) void load(); }}> {open ? 'Close Research Inspector' : 'Open Research Inspector'}</button>
    {open && <div className="inspector-body" onClick={event => { const link = (event.target as HTMLElement).closest('a'); const prefix = '#' + anchor(''); if (link?.getAttribute('href')?.startsWith(prefix)) reveal(link.getAttribute('href')!.slice(prefix.length)); }}>
      <h4>Research Inspector</h4><p>Saved work, source passages and validation decisions for this revision. Source text is untrusted evidence. Raw prompts and model replies are excluded.</p>
      {error && <p role="alert" className="error">{error}</p>}
      <button className="text-button" disabled={busy} onClick={() => void load()}>Refresh inspector</button>
      {busy && <p role="status">Loading saved diagnostics…</p>}
      {inspection && <>
        <dl><dt>Job / trace</dt><dd>{inspection.job.id}<br />{inspection.job.traceId}</dd><dt>Context / requirement revision</dt><dd>{inspection.job.contextId}<br />{inspection.job.revisionId}</dd><dt>State / topic</dt><dd>{inspection.job.status} / {inspection.focus}</dd><dt>Retry of</dt><dd>{inspection.retryOf || 'Original research'}</dd><dt>Captured model</dt><dd>{inspection.model.modelId} · runtime {inspection.model.runtimeVersion} · {inspection.model.contextTokens} context tokens · {inspection.model.promptVersion}<br />Artifact: {inspection.model.artifactFingerprint}</dd><dt>Used / allowed</dt><dd>{inspection.job.modelTurns} / {inspection.budgets.modelTurns} model calls · {inspection.job.searches} / {inspection.budgets.searches} searches · {inspection.job.pages} / {inspection.budgets.pages} fetches<br />{inspection.budgets.jobSeconds}s job deadline · {inspection.budgets.outputRepairs} repair maximum</dd></dl>
        <details><summary>Tasks & tool outcomes ({inspection.tasks.length})</summary>{inspection.tasks.map(task => <div id={anchor(task.id)} key={task.id}><strong>{task.kind} · {task.status}</strong><p>{task.id}</p>{task.outcomeCode && <p>{reasonText(task.outcomeCode)}</p>}</div>)}</details>
        <details open><summary>Validated fields & decisions</summary>{inspection.result ? <><p>Validator {inspection.result.validatorVersion}. Freshness is checked again when opened.</p>{inspection.result.fields.map(field => <div key={field.field}><strong>{fieldLabels[field.field]} · {field.state}</strong><p>{reasonText(field.reason)}</p>{field.citations.map((citation, index) => <p key={citation.passageId + index}><a href={'#' + anchor(citation.passageId)}>Inspect cited passage</a> · <a href={safeLink(citation.url)} target="_blank" rel="noreferrer">Original source ↗</a></p>)}</div>)}<details><summary>Candidate acceptance / rejection codes ({inspection.result.decisions.length})</summary>{inspection.result.decisions.map((decision, index) => <p key={index}>{fieldLabels[decision.field]} · {reasonText(decision.code)}</p>)}</details></> : <p>No validated result saved yet. Tasks and events explain running, cancelled or interrupted work.</p>}</details>
        <details><summary>Evidence snapshots ({inspection.sources.length})</summary>{inspection.sources.map(source => <div id={anchor(source.id)} key={source.id}><h5>{source.title || 'Source snapshot'}</h5><a href={safeLink(source.finalUrl)} target="_blank" rel="noreferrer">Read source ↗</a><p>Retrieved {new Date(source.retrievedAt).toLocaleString('en-IN')}<br />SHA-256: {source.contentHash}</p>{source.passages.map(passage => <details id={anchor(passage.id)} key={passage.id}><summary>Passage {passage.id} · offsets {passage.startOffset}–{passage.endOffset}</summary><blockquote>{passage.text}</blockquote></details>)}</div>)}</details>
        <details><summary>Event timeline ({events.length})</summary><p>Terminal event retention: {inspection.budgets.traceRetentionDays} days. An empty timeline can mean retained events expired; evidence remains saved. Events are loaded separately; refresh to see changes during running research.</p>{events.map(event => <div key={event.id}><strong>{event.type}</strong><small> · {new Date(event.createdAt).toLocaleString('en-IN')} · #{event.id}</small>{Object.entries(event.data).map(([key, value]) => <p key={key}>{key}: {['taskId', 'sourceId'].includes(key) && typeof value === 'string' ? <a href={'#' + anchor(value)}>{value}</a> : key === 'selectedPassageIds' && Array.isArray(value) ? value.map(id => <a key={String(id)} href={'#' + anchor(String(id))}>{String(id)} </a>) : JSON.stringify(value)}</p>)}</div>)}{more && <button disabled={busy} onClick={() => void load(true)}>Load next timeline page</button>}</details>
      </>}
    </div>}
  </section>;
}
