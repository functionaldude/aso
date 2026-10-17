import { Component, effect, inject, signal } from '@angular/core';
import { QuestionRow } from '../shared/question-row';
import { MatButtonModule } from '@angular/material/button';
import { IdentityService } from '../core/identity';
import { Question, ContentPage, QuestionsApi, contentError } from './questions-api';
@Component({
  selector: 'app-moderation-list',
  imports: [QuestionRow, MatButtonModule],
  template: `<section class="questions-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">Administration / Content</p>
        <h1>Content moderation<span class="heading-cursor" aria-hidden="true">_</span></h1>
      </div>
    </header>
    @if (session.state() === 'loading') {
      <p class="loading-state" role="status">Checking administrator access…</p>
    } @else if (session.identity()?.admin) {
      <p class="notice warning">
        Administrator access · Edit, hide or restore questions and their answers and comments.
        Hidden content remains visible here.
      </p>
      <div class="list-heading">
        <span>All content</span><span>Visibility and resolution are independent</span>
      </div>
      @for (question of items(); track question.id) {
        <app-question-row [question]="question" [moderation]="true" />
      }
      @if (busy()) {
        <p class="loading-state" role="status">Loading moderation content…</p>
      }
      @if (!busy() && !error() && !items().length) {
        <p class="empty">No questions to moderate.</p>
      }
      @if (next()) {
        <button mat-button [disabled]="busy()" (click)="load(true)">Load more questions</button>
      }
      @if (error()) {
        <p class="notice error" role="alert">{{ error() }}</p>
        <button mat-button (click)="load()">Retry moderation</button>
      }
    } @else {
      <p class="notice">Administrator browser access is required.</p>
    }
  </section>`,
})
export class ModerationList {
  readonly session = inject(IdentityService);
  private readonly api = inject(QuestionsApi);
  readonly items = signal<Question[]>([]);
  readonly next = signal<string | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  constructor() {
    effect(() => {
      if (this.session.identity()?.admin) void this.load();
      else {
        this.items.set([]);
        this.next.set(null);
      }
    });
  }
  async load(more = false) {
    this.busy.set(true);
    this.error.set('');
    try {
      const page = await this.api.get<ContentPage<Question>>(
        '/admin/questions',
        more && this.next() ? { cursor: this.next()! } : {},
      );
      this.items.set(more ? [...this.items(), ...page.items] : page.items);
      this.next.set(page.nextCursor);
    } catch (error) {
      this.error.set(contentError(error));
    } finally {
      this.busy.set(false);
    }
  }
}
