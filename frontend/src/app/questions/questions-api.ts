import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface Author {
  id: string;
  displayName: string;
}
export interface VoteSummary {
  upvotes: number;
  votedByYou: boolean;
}
export interface EncounterCounts {
  encounters: number;
  distinctDevelopers: number;
}
export interface VoteEvidence {
  id: string;
  voter: Author;
  channel: string;
  createdAt: string;
  callerReportedVerification: string | null;
}
export interface Question {
  votes: VoteSummary;
  occurrences: { allTime: EncounterCounts; sevenDay: EncounterCounts };
  id: string;
  title: string;
  body: string;
  bodyHtml: string;
  tags: string[];
  context: {
    errorText: string | null;
    dependencies: string | null;
    versions: string | null;
    repository: string | null;
  };
  author: Author;
  channel: string;
  createdAt: string;
  state: string;
  acceptedAnswerId: string | null;
  version: number;
  updatedAt: string;
  hidden: boolean;
}
export interface Answer {
  votes: VoteSummary;
  version: number;
  updatedAt: string;
  hidden: boolean;
  id: string;
  questionId: string;
  body: string;
  bodyHtml: string;
  applicability: string | null;
  verification: string | null;
  author: Author;
  channel: string;
  createdAt: string;
}
export interface Comment {
  id: string;
  questionId: string;
  answerId: string | null;
  body: string;
  bodyHtml: string;
  author: Author;
  channel: string;
  createdAt: string;
  updatedAt: string;
  version: number;
  hidden: boolean;
}
export type Content = Question | Answer | Comment;
export type ContentKind = 'question' | 'answer' | 'comment';
export interface ContentPage<T> {
  items: T[];
  nextCursor: string | null;
  asOf?: string;
}
export interface Detail {
  question: Question;
  answers: ContentPage<Answer>;
}
export interface SearchResult {
  question: Question;
  snippet: string;
  match: { identifiers: string[]; lexical: boolean; semantic: boolean; explanation: string };
}
export interface SearchPage {
  items: SearchResult[];
  nextCursor: string | null;
  degraded: boolean;
  reasons: string[];
  notice: string;
}

export function contentError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 401 || error.status === 403)
      return 'Your session expired or workspace access is unavailable. Sign in again.';
    if (error.status === 404) return error.error?.detail ?? 'Content not found.';
    if (error.status === 429) {
      const seconds = Number(error.headers.get('Retry-After'));
      return Number.isFinite(seconds) && seconds > 0
        ? `Request limit reached. Wait ${seconds} seconds, then retry with the same content.`
        : 'Request limit reached. Wait briefly, then retry with the same content.';
    }
    if (error.status === 400 || error.status === 409 || error.status === 413)
      return error.error?.detail ?? 'Check your input and try again.';
  }
  return 'The request could not be completed. Retry with the same content to avoid a duplicate.';
}

/** A retry retains its UUID until the content changes. */
export class CreateRequest {
  private previous = '';
  private id = '';
  reset() {
    this.previous = '';
    this.id = '';
  }
  payload(content: object) {
    const serialized = JSON.stringify(content);
    if (serialized !== this.previous) {
      this.previous = serialized;
      this.id = crypto.randomUUID();
    }
    return { ...content, requestId: this.id };
  }
}

@Injectable({ providedIn: 'root' })
export class QuestionsApi {
  private readonly http = inject(HttpClient);
  search(query: string, tag: string, state: string, cursor?: string) {
    let params = new HttpParams().set('query', query).set('state', state);
    if (tag.trim()) params = params.set('tag', tag.trim());
    if (cursor) params = params.set('cursor', cursor);
    return firstValueFrom(this.http.get<SearchPage>('/api/v1/search', { params }));
  }
  list(tag: string, state: string, cursor?: string, view = 'recent') {
    let params = new HttpParams().set('state', state).set('view', view);
    if (tag.trim()) params = params.set('tag', tag.trim());
    if (cursor) params = params.set('cursor', cursor);
    return firstValueFrom(this.http.get<ContentPage<Question>>('/api/v1/questions', { params }));
  }
  detail(id: string, moderation = false) {
    return this.get<Detail>(`${moderation ? '/admin' : ''}/questions/${id}`);
  }
  answers(id: string, cursor: string, moderation = false) {
    return firstValueFrom(
      this.http.get<ContentPage<Answer>>(
        `/api/v1/${moderation ? 'admin/' : ''}questions/${id}/answers`,
        { params: { cursor } },
      ),
    );
  }
  get<T>(path: string, params: Record<string, string> = {}) {
    return firstValueFrom(this.http.get<T>(`/api/v1${path}`, { params }));
  }
  async post<T>(path: string, content: object): Promise<T> {
    const csrf = await firstValueFrom(this.http.get<{ token: string }>('/api/v1/csrf'));
    return firstValueFrom(
      this.http.post<T>(`/api/v1${path}`, content, { headers: { 'X-CSRF-TOKEN': csrf.token } }),
    );
  }
  async create<T>(path: string, content: object): Promise<T> {
    const csrf = await firstValueFrom(this.http.get<{ token: string }>('/api/v1/csrf'));
    return firstValueFrom(
      this.http.post<T>(`/api/v1/questions${path}`, content, {
        headers: { 'X-CSRF-TOKEN': csrf.token },
      }),
    );
  }
}
