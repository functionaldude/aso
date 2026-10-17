import { Component, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { QuestionRow } from '../shared/question-row';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink, ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { IdentityService } from '../core/identity';
import { Question, QuestionsApi, SearchResult, contentError } from './questions-api';

@Component({
  selector: 'app-question-list',
  imports: [
    DatePipe,
    QuestionRow,
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './question-list.html',
})
export class QuestionList {
  readonly session = inject(IdentityService);
  private readonly api = inject(QuestionsApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  tag = '';
  query = '';
  activeQuery = '';
  readonly degraded = signal('');
  readonly matches = signal<Record<string, SearchResult>>({});
  state = 'open';
  view = 'recent';
  activeView = 'recent';
  readonly asOf = signal<string | undefined>(undefined);
  readonly items = signal<Question[]>([]);
  readonly next = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  history: (string | undefined)[] = [];
  private current?: string;
  private activeTag = '';
  private activeState = 'open';
  private generation = 0;
  constructor() {
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.tag = params.get('tag') ?? '';
      this.query = params.get('query') ?? '';
      this.activeQuery = this.query.trim();
      this.state = params.get('state') ?? (this.activeQuery ? 'all' : 'open');
      this.view = params.get('view') ?? 'recent';
      this.activeView = this.view;
      this.activeTag = this.tag;
      this.activeState = this.state;
      this.history = [];
      this.current = undefined;
      if (this.session.state() === 'member') void this.load();
    });
    effect(() => {
      if (this.session.state() === 'member') void this.load();
      else {
        this.generation++;
        this.items.set([]);
        this.next.set(null);
      }
    });
  }
  apply() {
    void this.router.navigate(['/questions'], {
      queryParams: {
        query: this.query.trim() || null,
        tag: this.tag || null,
        state: this.state,
        view: this.view,
      },
    });
  }
  async load() {
    const generation = ++this.generation;
    this.loading.set(true);
    this.error.set('');
    try {
      if (this.activeQuery) {
        const page = await this.api.search(
          this.activeQuery,
          this.activeTag,
          this.activeState,
          this.current,
        );
        if (generation === this.generation) {
          this.items.set(page.items.map((result) => result.question));
          this.matches.set(
            Object.fromEntries(page.items.map((result) => [result.question.id, result])),
          );
          this.degraded.set(page.degraded ? page.reasons.join(', ').replaceAll('_', ' ') : '');
          this.next.set(page.nextCursor);
        }
        return;
      }
      this.matches.set({});
      this.degraded.set('');
      const page = await this.api.list(
        this.activeTag,
        this.activeState,
        this.current,
        this.activeView,
      );
      if (generation === this.generation) {
        this.items.set(page.items);
        this.asOf.set(page.asOf);
        this.next.set(page.nextCursor);
      }
    } catch (error) {
      if (generation === this.generation) {
        this.items.set([]);
        this.next.set(null);
        this.error.set(contentError(error));
      }
    } finally {
      if (generation === this.generation) this.loading.set(false);
    }
  }
  forward() {
    const next = this.next();
    if (next) {
      this.history.push(this.current);
      this.current = next;
      void this.load();
    }
  }
  previous() {
    if (this.history.length) {
      this.current = this.history.pop();
      void this.load();
    }
  }
}
