import { HttpErrorResponse } from '@angular/common/http';
import { ContentEditor } from './content-editor';
import { ContentComments } from './content-comments';
import { Component, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { IdentityService } from '../core/identity';
import { Answer, CreateRequest, Detail, QuestionsApi, contentError } from './questions-api';

@Component({
  selector: 'app-question-detail',
  imports: [
    DatePipe,
    ContentEditor,
    ContentComments,
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
  ],
  styleUrl: './questions.scss',
  template: `<section class="questions-page">
    <a mat-button routerLink="/questions">← Questions</a>
    @if (session.state() === 'member') {
      @if (loading()) {
        <mat-spinner diameter="28" aria-label="Loading question" />
      }
      @if (error()) {
        <p role="alert">{{ error() }}</p>
        <button mat-button (click)="load()">Retry</button>
      }
      @if (detail(); as detail) {
        <article>
          <p class="eyebrow">
            {{ detail.question.state === 'resolved' ? 'Resolved question' : 'Open question' }}
          </p>
          @if (session.identity()?.admin && !moderation) {
            <a mat-button [routerLink]="['/moderation', detail.question.id]">Moderate content</a>
          }
          @if (moderation) {
            <p>Administrator moderation · Hidden parents keep descendants out of ordinary reads.</p>
          }
          <h1>{{ detail.question.title }}</h1>
          <p class="metadata">
            {{ detail.question.author.displayName }} ·
            {{ detail.question.createdAt | date: 'medium' }} · {{ detail.question.channel }}
          </p>
          <div class="tags">
            @for (tag of detail.question.tags; track tag) {
              <a routerLink="/questions" [queryParams]="{ tag, state: 'all' }">{{ tag }}</a>
            }
          </div>
          <div class="markdown" [innerHTML]="detail.question.bodyHtml"></div>
          @if (detail.question.context.errorText) {
            <h3>Exact error</h3>
            <pre>{{ detail.question.context.errorText }}</pre>
          }
          @if (detail.question.context.dependencies) {
            <h3>Dependencies</h3>
            <pre>{{ detail.question.context.dependencies }}</pre>
          }
          @if (detail.question.context.versions) {
            <h3>Versions</h3>
            <pre>{{ detail.question.context.versions }}</pre>
          }
          @if (detail.question.context.repository) {
            <h3>Repository / module</h3>
            <pre>{{ detail.question.context.repository }}</pre>
          }
          <app-content-editor
            kind="question"
            [content]="detail.question"
            [moderation]="moderation"
            (changed)="load()"
          />
          <app-content-comments
            kind="question"
            [targetId]="detail.question.id"
            [moderation]="moderation"
          />
          @if (canAccept() && detail.question.acceptedAnswerId) {
            <button
              mat-button
              [disabled]="accepting() || acceptanceConflict()"
              (click)="accept(null)"
            >
              Reopen question
            </button>
          }
          @if (acceptanceError()) {
            <p role="alert">{{ acceptanceError() }}</p>
            <button mat-button (click)="reviewAcceptance()">Reload acceptance state</button>
          }
        </article>
        <section aria-label="Answers">
          <h2>Answers</h2>
          @for (answer of detail.answers.items; track answer.id) {
            <article class="answer">
              @if (detail.question.acceptedAnswerId === answer.id) {
                <p class="accepted">Accepted answer</p>
              }
              @if (
                canAccept() &&
                !answer.hidden &&
                !detail.question.hidden &&
                detail.question.acceptedAnswerId !== answer.id
              ) {
                <button
                  mat-button
                  [disabled]="accepting() || acceptanceConflict()"
                  (click)="accept(answer.id)"
                >
                  Accept answer
                </button>
              }
              <p class="metadata">
                {{ answer.author.displayName }} · {{ answer.createdAt | date: 'medium' }} ·
                {{ answer.channel }}
              </p>
              <div class="markdown" [innerHTML]="answer.bodyHtml"></div>
              @if (answer.applicability) {
                <h3>Applicability</h3>
                <pre>{{ answer.applicability }}</pre>
              }
              <h3>Verification evidence</h3>
              <pre>{{ answer.verification || 'No testing evidence provided.' }}</pre>
              <app-content-editor
                kind="answer"
                [content]="answer"
                [moderation]="moderation"
                (changed)="load()"
              />
              <app-content-comments
                kind="answer"
                [targetId]="answer.id"
                [moderation]="moderation"
              />
            </article>
          } @empty {
            <p>No answers yet. Share a proposed fix and what you tested.</p>
          }
          @if (detail.answers.nextCursor) {
            <button mat-button [disabled]="moreLoading()" (click)="more()">
              Load more answers
            </button>
          }
          @if (moreError()) {
            <p role="alert">{{ moreError() }}</p>
          }
        </section>
        @if (!moderation) {
          <h2>Your answer</h2>
          <p>
            Remove credentials. Describe applicability and verification, or explicitly say the fix
            was not tested.
          </p>
          <form #form="ngForm" (ngSubmit)="submit()" class="content-form">
            <fieldset [disabled]="saving()">
              <mat-form-field
                ><mat-label>Answer (Markdown)</mat-label
                ><textarea
                  matInput
                  name="body"
                  [(ngModel)]="body"
                  required
                  maxlength="20000"
                  rows="8"
                ></textarea>
              </mat-form-field>
              <mat-form-field
                ><mat-label>Applicability</mat-label
                ><textarea
                  matInput
                  name="applicability"
                  [(ngModel)]="applicability"
                  maxlength="2000"
                ></textarea>
              </mat-form-field>
              <mat-form-field
                ><mat-label>Verification evidence</mat-label
                ><textarea
                  matInput
                  name="verification"
                  [(ngModel)]="verification"
                  maxlength="2000"
                ></textarea>
              </mat-form-field>
            </fieldset>
            @if (saveError()) {
              <p role="alert">{{ saveError() }}</p>
            }
            <button mat-flat-button [disabled]="form.invalid || saving()">
              {{ saving() ? 'Publishing…' : 'Publish answer' }}
            </button>
          </form>
        }
      }
    } @else {
      <p>Sign in with workspace membership to read this question.</p>
    }
  </section>`,
})
export class QuestionDetail {
  readonly session = inject(IdentityService);
  private readonly api = inject(QuestionsApi);
  private readonly route = inject(ActivatedRoute);
  private readonly request = new CreateRequest();
  readonly moderation = this.route.snapshot.data['moderation'] === true;
  readonly accepting = signal(false);
  readonly acceptanceError = signal('');
  readonly acceptanceConflict = signal(false);
  canAccept() {
    const q = this.detail()?.question;
    return (
      !!q &&
      !q.hidden &&
      (this.session.identity()?.admin || this.session.identity()?.id === q.author.id)
    );
  }
  async accept(answerId: string | null) {
    const question = this.detail()?.question;
    if (!question || this.accepting() || this.acceptanceConflict()) return;
    this.accepting.set(true);
    this.acceptanceError.set('');
    try {
      await this.api.post(`/questions/${this.id}/acceptance`, {
        expectedVersion: question.version,
        answerId,
      });
      await this.load();
    } catch (error) {
      this.acceptanceError.set(contentError(error));
      this.acceptanceConflict.set(error instanceof HttpErrorResponse && error.status === 409);
    } finally {
      this.accepting.set(false);
    }
  }
  async reviewAcceptance() {
    await this.load();
    this.acceptanceConflict.set(false);
    this.acceptanceError.set('');
  }

  private id = '';
  private generation = 0;
  readonly detail = signal<Detail | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly saving = signal(false);
  readonly saveError = signal('');
  readonly moreLoading = signal(false);
  readonly moreError = signal('');
  body = '';
  applicability = '';
  verification = '';
  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const id = params.get('id') ?? '';
      if (id !== this.id) this.detail.set(null);
      this.id = id;
      if (this.session.state() === 'member') void this.load();
    });
    effect(() => {
      if (this.session.state() === 'member') void this.load();
      else {
        this.generation++;
        this.detail.set(null);
      }
    });
  }
  async load() {
    const generation = ++this.generation;
    this.loading.set(true);
    this.error.set('');
    try {
      const detail = await this.api.detail(this.id, this.moderation);
      if (generation === this.generation) this.detail.set(detail);
    } catch (error) {
      if (generation === this.generation) {
        this.detail.set(null);
        this.error.set(contentError(error));
      }
    } finally {
      if (generation === this.generation) this.loading.set(false);
    }
  }
  async more() {
    const detail = this.detail();
    if (!detail?.answers.nextCursor || this.moreLoading()) return;
    this.moreLoading.set(true);
    this.moreError.set('');
    try {
      const page = await this.api.answers(this.id, detail.answers.nextCursor, this.moderation);
      this.detail.set({
        ...detail,
        answers: { items: [...detail.answers.items, ...page.items], nextCursor: page.nextCursor },
      });
    } catch (error) {
      this.moreError.set(contentError(error));
    } finally {
      this.moreLoading.set(false);
    }
  }
  async submit() {
    if (this.saving()) return;
    this.saving.set(true);
    this.saveError.set('');
    try {
      await this.api.create<Answer>(
        `/${this.id}/answers`,
        this.request.payload({
          body: this.body,
          applicability: this.applicability,
          verification: this.verification,
        }),
      );
      this.request.reset();
      this.body = '';
      this.applicability = '';
      this.verification = '';
      await this.load();
    } catch (error) {
      this.saveError.set(contentError(error));
    } finally {
      this.saving.set(false);
    }
  }
}
