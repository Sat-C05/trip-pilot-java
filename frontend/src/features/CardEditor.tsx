import { useEffect, useId, useRef, useState, type FormEvent } from 'react';

export function CardEditor({ title: initialTitle, notes: initialNotes, close, save }: {
  title: string; notes: string; close: () => void; save: (title: string, notes: string) => Promise<void>;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const fieldId = useId();
  const [title, setTitle] = useState(initialTitle);
  const [notes, setNotes] = useState(initialNotes);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => { const element = dialog.current; element?.showModal(); return () => element?.close(); }, []);
  const dismiss = () => { dialog.current?.close(); close(); };
  async function submit(event: FormEvent) {
    event.preventDefault(); if (saving) return;
    setSaving(true); setError('');
    try { await save(title.trim(), notes.trim()); dismiss(); }
    catch (failure) { setError(failure instanceof Error ? failure.message : 'Could not save this card.'); }
    finally { setSaving(false); }
  }
  return <dialog ref={dialog} className="card-dialog" aria-labelledby="card-editor-title" onCancel={event => { event.preventDefault(); if (!saving) dismiss(); }}>
    <p className="eyebrow">MAKE IT YOURS</p><h2 id="card-editor-title">Edit card</h2>
    <p className="muted">Give this research a memorable title and keep your own notes. Notes are personal and aren’t source evidence.</p>
    <form onSubmit={event => void submit(event)}>
      <label htmlFor={fieldId + '-title'}>Card title</label><input id={fieldId + '-title'} autoFocus required maxLength={120} value={title} onChange={event => setTitle(event.target.value)} />
      <label htmlFor={fieldId + '-notes'}>Personal notes</label><textarea id={fieldId + '-notes'} maxLength={2000} rows={5} value={notes} onChange={event => setNotes(event.target.value)} placeholder="What appeals to you? What do you want to check?" />
      <small>{notes.length} / 2,000 characters</small>
      {error && <p role="alert" className="error">{error}</p>}
      <div className="actions"><button disabled={saving || !title.trim()}>{saving ? 'Saving…' : 'Save card'}</button><button type="button" className="secondary" disabled={saving} onClick={dismiss}>Cancel</button></div>
    </form>
  </dialog>;
}
