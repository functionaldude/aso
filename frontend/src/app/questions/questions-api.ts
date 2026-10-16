import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface Author {
  id: string;
  displayName: string;
}
export interface Question {
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
}
export interface Answer {
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
export interface ContentPage<T> {
  items: T[];
  nextCursor: string | null;
}
export interface Detail {
  question: Question;
  answers: ContentPage<Answer>;
}

export function contentError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 401 || error.status === 403)
      return 'Your session expired or workspace access is unavailable. Sign in again.';
    if (error.status === 404) return 'Question not found.';
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
  list(tag: string, state: string, cursor?: string) {
    let params = new HttpParams().set('state', state);
    if (tag.trim()) params = params.set('tag', tag.trim());
    if (cursor) params = params.set('cursor', cursor);
    return firstValueFrom(this.http.get<ContentPage<Question>>('/api/v1/questions', { params }));
  }
  detail(id: string) {
    return firstValueFrom(this.http.get<Detail>(`/api/v1/questions/${id}`));
  }
  answers(id: string, cursor: string) {
    return firstValueFrom(
      this.http.get<ContentPage<Answer>>(`/api/v1/questions/${id}/answers`, { params: { cursor } }),
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
