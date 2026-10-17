import { Component, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink, ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { IdentityService } from '../core/identity';
import { Question, QuestionsApi, contentError } from './questions-api';

@Component({
  selector: 'app-question-list',
  imports: [
    DatePipe,
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatProgressSpinnerModule,
  ],
  styleUrl: './questions.scss',
  template: ` <section class="questions-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">Private workspace</p>
        <h1>Questions</h1>
      </div>
      <a mat-flat-button routerLink="/questions/new">Ask a question</a>
    </div>
    @if (session.state() === 'member') {
      <form class="filters" (ngSubmit)="apply()">
        <mat-form-field
          ><mat-label>Tag filter</mat-label
          ><input matInput name="tag" [(ngModel)]="tag" maxlength="40"
        /></mat-form-field>
        <mat-form-field
          ><mat-label>View</mat-label
          ><mat-select name="view" [(ngModel)]="view"
            ><mat-option value="recent">Recent</mat-option
            ><mat-option value="unanswered">Unanswered</mat-option
            ><mat-option value="recurring">Recurring · 7 days</mat-option></mat-select
          ></mat-form-field
        >
        <mat-form-field
          ><mat-label>State</mat-label
          ><mat-select name="state" [(ngModel)]="state"
            ><mat-option value="all">All</mat-option><mat-option value="open">Open</mat-option
            ><mat-option value="resolved">Resolved</mat-option></mat-select
          ></mat-form-field
        >
        <button mat-button [disabled]="loading()">Apply filters</button>
      </form>
      @if (activeView === 'recurring') {
        <p>
          Last 7 days (UTC), through {{ asOf() | date: 'medium' : 'UTC' }} UTC. Ranked by distinct
          developers, encounters, then latest encounter.
        </p>
      }
      @if (error()) {
        <p role="alert">{{ error() }}</p>
        <button mat-button (click)="load()">Retry</button>
      }
      @if (loading()) {
        <mat-spinner diameter="28" aria-label="Loading questions" />
      } @else {
        @for (question of items(); track question.id) {
          <article class="question-row">
            <p class="metadata">
              {{ question.state === 'resolved' ? 'Resolved' : 'Open' }} ·
              {{ question.author.displayName }} · {{ question.createdAt | date: 'medium' }} ·
              {{ question.channel }}
            </p>
            <h2>
              <a [routerLink]="['/questions', question.id]">{{ question.title }}</a>
            </h2>
            <p>
              {{ question.votes.upvotes }} upvotes · All time:
              {{ question.occurrences.allTime.encounters }} encounters ·
              {{ question.occurrences.allTime.distinctDevelopers }} distinct developers
            </p>
            <p>
              Last 7 days (UTC): {{ question.occurrences.sevenDay.encounters }} encounters ·
              {{ question.occurrences.sevenDay.distinctDevelopers }} distinct developers
            </p>
            <div class="tags">
              @for (tag of question.tags; track tag) {
                <a [routerLink]="['/questions']" [queryParams]="{ tag, state: 'all' }">{{ tag }}</a>
              }
            </div>
          </article>
        } @empty {
          @if (!error()) {
            <p class="empty">No questions match these filters.</p>
          }
        }
        <div class="actions">
          <button mat-button [disabled]="!history.length" (click)="previous()">Previous page</button
          ><button mat-button [disabled]="!next()" (click)="forward()">Next page</button>
        </div>
      }
    } @else {
      <p>Sign in with workspace membership to read and create questions.</p>
    }
  </section>`,
})
export class QuestionList {
  readonly session = inject(IdentityService);
  private readonly api = inject(QuestionsApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  tag = '';
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
      this.state = params.get('state') ?? 'open';
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
      queryParams: { tag: this.tag || null, state: this.state, view: this.view },
    });
  }
  async load() {
    const generation = ++this.generation;
    this.loading.set(true);
    this.error.set('');
    try {
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
