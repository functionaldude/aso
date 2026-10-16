import { Component, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { IdentityService } from '../core/identity';
import { Question, ContentPage, QuestionsApi, contentError } from './questions-api';
@Component({
  selector: 'app-moderation-list',
  imports: [RouterLink, MatButtonModule],
  styleUrl: './questions.scss',
  template: `<section class="questions-page">
    <h1>Content moderation</h1>
    @if (session.identity()?.admin) {
      <p>
        Open a question to edit, hide or restore it and its answers and comments. Hidden descendants
        remain available here.
      </p>
      @for (question of items(); track question.id) {
        <article class="question-row">
          <p>{{ question.hidden ? 'Hidden' : 'Visible' }} · {{ question.state }}</p>
          <h2>
            <a [routerLink]="['/moderation', question.id]">{{ question.title }}</a>
          </h2>
        </article>
      }
      @if (next()) {
        <button mat-button [disabled]="busy()" (click)="load(true)">Load more questions</button>
      }
      @if (error()) {
        <p role="alert">{{ error() }}</p>
        <button mat-button (click)="load()">Retry moderation</button>
      }
    } @else {
      <p>Administrator browser access is required.</p>
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
