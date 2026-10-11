export type Requirements = { departure: string | null; destinationIntent: string | null; country: 'IN'; arrival: string | null; departureDate: string | null; budget: string | null; currency: string | null; travelers: number | null; preferences: string[] };
export type Session = { id: string; title: string; createdAt: string };
export type Job = { id: string; sessionId: string; contextId: string; revisionId: string; status: string; outcomeCode: string | null; createdAt: string; modelTurns: number; searches: number; pages: number; cardTitle: string | null; cardNotes: string; cardDeletedAt: string | null };
export type Citation = { sourceId: string; passageId: string; url: string; retrievedAt: string; quote: string };
export type Field = { field: string; state: string; value: string | null; reason: string; citations: Citation[] };
export type Result = { validatedAt: string; complete: boolean; missingRequirements: string[]; fields: Field[]; durationEstimate: { state: string; nights: number | null; assumptions: string[] }; costEstimateReason: string };
export type Image = { url: string; sourceUrl: string; title: string; author: string; license: string; licenseUrl: string; retrievedAt: string };
export type Trip = { id: string; sessionId: string; selectedJobId: string; destination: string; createdAt: string };
export type Detail = { context: Trip; requirements: Requirements; selectedResult: Result; jobs: Job[] };
export type Message = { id: string; role: string; content: string; jobId: string; createdAt: string };
export class ApiError extends Error { constructor(public status: number, public code: string, message: string) { super(message); } }
export async function api<T>(path: string, body?: unknown, form = false, method?: 'PUT' | 'DELETE'): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined || method) {
    const csrf = await api<{ headerName: string; token: string }>('/api/identity/csrf');
    headers[csrf.headerName] = csrf.token;
    headers['Content-Type'] = form ? 'application/x-www-form-urlencoded' : 'application/json';
  }
  const response = await fetch(path, { method: method || (body === undefined ? 'GET' : 'POST'), credentials: 'same-origin', headers, body: body === undefined ? undefined : form ? String(body) : JSON.stringify(body) });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new ApiError(response.status, data.code || 'REQUEST_FAILED', response.status === 401 ? 'Your session has ended. Please sign in.' : data.message || 'The request could not be completed. Please try again.');
  }
  return response.status === 204 ? undefined as T : response.json();
}
export const terminal = (job: Job) => !['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(job.status);
export const fieldLabels: Record<string, string> = { DESCRIPTION: 'Destination guide', STAY: 'Stays', TRANSPORT: 'Getting there & around', ACTIVITY: 'Things to do', PRICE: 'Current prices', AVAILABILITY: 'Availability', POLICY: 'Provider policies' };
export const inputLabels: Record<string, string> = { departure: 'Departure city', destinationIntent: 'Destination', arrival: 'Arrival date', departureDate: 'Return date', budget: 'Total budget', currency: 'Currency', travelers: 'Travelers' };
export const reasonText = (code: string) => ({ MODEL_UNAVAILABLE: 'The local model could not be reached or loaded. Start the configured model service, then refresh sources.', MODEL_SNAPSHOT_MISMATCH: 'The model runtime or artifact differs from this job’s saved configuration. Check the configured model, then start new research.', MODEL_DEADLINE: 'The local model took too long to respond. Check the model service, then refresh sources.', INVALID_EXTRACTION: 'The model response did not pass structured validation. Refresh sources to try again.', CONTEXT_EVIDENCE_LIMIT: 'The evidence could not fit the configured model context. Try a shorter request.', EMPTY_SEARCH: 'No travel-guide search results were found for this destination.', NO_EVIDENCE: 'No usable source pages could be retrieved. Check the destination and internet connection, then refresh.', NO_SCOPED_PROVIDER_EVIDENCE: 'Current provider evidence is unavailable from travel guides.', NO_ACCEPTED_EVIDENCE: 'No excerpt passed validation for this field.', ATTRIBUTED_EXCERPT_SEMANTICS_UNVERIFIED: 'Exact source excerpt; meaning and suitability need your review.', RESULT_FRESHNESS_EXPIRED: 'This evidence is old. Refresh before relying on it.', RETRIEVAL_FRESHNESS_EXPIRED: 'This evidence is old. Refresh before relying on it.', DIFFERING_EXCERPTS: 'Sources differ. Review each excerpt before deciding.' }[code] || code.toLowerCase().replaceAll('_', ' '));
export const safeLink = (url: string) => { try { const parsed = new URL(url); return ['http:', 'https:'].includes(parsed.protocol) && !parsed.username && !parsed.password ? parsed.href : undefined; } catch { return undefined; } };
