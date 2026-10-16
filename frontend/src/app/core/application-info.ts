import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

export interface ApplicationInfo {
  name: string;
  version: string;
}
export type ConnectionState = 'loading' | 'connected' | 'unavailable';

@Injectable({ providedIn: 'root' })
export class ApplicationInfoService {
  private readonly http = inject(HttpClient);
  private readonly currentState = signal<ConnectionState>('loading');
  private readonly currentInfo = signal<ApplicationInfo | null>(null);
  readonly state = this.currentState.asReadonly();
  readonly info = this.currentInfo.asReadonly();

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.currentState.set('loading');
    this.http.get<ApplicationInfo>('/api/v1/info').subscribe({
      next: (info) => {
        this.currentInfo.set(info);
        this.currentState.set('connected');
      },
      error: () => {
        this.currentInfo.set(null);
        this.currentState.set('unavailable');
      },
    });
  }
}
