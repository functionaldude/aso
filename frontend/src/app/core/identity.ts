import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

export interface Identity {
  id: string;
  issuer: string;
  subject: string;
  displayName: string;
  email: string | null;
  admin: boolean;
  channel: 'web';
}

@Injectable({ providedIn: 'root' })
export class IdentityService {
  private readonly http = inject(HttpClient);
  readonly identity = signal<Identity | null>(null);
  readonly state = signal<'loading' | 'signedOut' | 'member' | 'denied' | 'unavailable'>('loading');
  readonly csrf = signal<{ token: string; parameterName: string } | null>(null);
  readonly loginError = new URLSearchParams(window.location.search).has('loginError');

  constructor() {
    this.refresh();
    window.addEventListener('focus', () => this.refresh());
  }

  refresh() {
    this.http.get<Identity>('/api/v1/identity').subscribe({
      next: (identity) => {
        this.identity.set(identity);
        this.state.set('member');
      },
      error: (error: HttpErrorResponse) => {
        this.identity.set(null);
        this.state.set(
          error.status === 401 ? 'signedOut' : error.status === 403 ? 'denied' : 'unavailable',
        );
      },
    });
    this.http.get<{ token: string; parameterName: string }>('/api/v1/csrf').subscribe({
      next: (token) => this.csrf.set(token),
      error: () => this.csrf.set(null),
    });
  }
}
