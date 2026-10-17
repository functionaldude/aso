import { ReadableContent } from '../shared/readable-content';
import { EngagementControls } from './engagement-controls';
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
    ReadableContent,
    DatePipe,
    ContentEditor,
    EngagementControls,
    ContentComments,
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './question-detail.html',
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
