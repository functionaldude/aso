import { Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { IdentityService } from '../core/identity';
import {
  Answer,
  Content,
  ContentKind,
  Question,
  QuestionsApi,
  contentError,
} from './questions-api';

@Component({
  selector: 'app-content-editor',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  styleUrl: './questions.scss',
  template: `
    @if (moderation()) {
      <p class="metadata">
        {{ content().hidden ? 'Hidden' : 'Visible' }} · Version {{ content().version }}
      </p>
    }
    @if (!editing() && (moderation() || session.identity()?.id === content().author.id)) {
      <button mat-button type="button" (click)="begin()" [disabled]="busy()">
        Edit {{ kind() }}
      </button>
    }
    @if (moderation()) {
      <button mat-button type="button" [disabled]="busy()" (click)="visibility()">
        {{ content().hidden ? 'Restore' : 'Hide' }} {{ kind() }}
      </button>
    }
    @if (editing()) {
      <form class="content-form" (ngSubmit)="save()" #form="ngForm">
        <fieldset [disabled]="busy()">
          @if (kind() === 'question') {
            <mat-form-field
              ><mat-label>Edit title</mat-label
              ><input matInput name="title" [(ngModel)]="title" required maxlength="200"
            /></mat-form-field>
            <mat-form-field
              ><mat-label>Edit tags (comma separated)</mat-label
              ><input matInput name="tags" [(ngModel)]="tags"
            /></mat-form-field>
          }
          <mat-form-field
            ><mat-label>Edit body (Markdown)</mat-label
            ><textarea
              matInput
              name="body"
              [(ngModel)]="body"
              required
              maxlength="20000"
              rows="6"
            ></textarea>
          </mat-form-field>
          @if (kind() === 'question') {
            @for (field of contextFields; track field) {
              <mat-form-field
                ><mat-label>Edit {{ field }}</mat-label
                ><textarea
                  matInput
                  [name]="field"
                  [(ngModel)]="context[field]"
                  maxlength="2000"
                ></textarea>
              </mat-form-field>
            }
          }
          @if (kind() === 'answer') {
            <mat-form-field
              ><mat-label>Edit applicability</mat-label
              ><textarea
                matInput
                name="applicability"
                [(ngModel)]="applicability"
                maxlength="2000"
              ></textarea>
            </mat-form-field>
            <mat-form-field
              ><mat-label>Edit verification</mat-label
              ><textarea
                matInput
                name="verification"
                [(ngModel)]="verification"
                maxlength="2000"
              ></textarea>
            </mat-form-field>
          }
        </fieldset>
        <button mat-flat-button [disabled]="busy() || form.invalid || conflict()">
          Save {{ kind() }}
        </button>
        <button mat-button type="button" [disabled]="busy()" (click)="cancel()">Cancel edit</button>
      </form>
    }
    @if (error()) {
      <p role="alert">{{ error() }}</p>
    }
    @if (conflict()) {
      <p>Your draft is preserved above. Load the current content and compare before saving.</p>
      <button mat-button type="button" [disabled]="busy()" (click)="loadLatest()">
        Load latest content
      </button>
      @if (latest(); as latest) {
        <h4>Latest saved content · Version {{ latest.version }}</h4>
        @if (latestQuestion(); as question) {
          <h5>{{ question.title }}</h5>
          <p>Tags: {{ question.tags.join(', ') || 'None' }}</p>
          @for (field of contextFields; track field) {
            @if (question.context[field]) {
              <p>{{ field }}</p>
              <pre>{{ question.context[field] }}</pre>
            }
          }
        }
        <pre class="latest-content">{{ latest.body }}</pre>
        @if (latestAnswer(); as answer) {
          <p>Applicability</p>
          <pre>{{ answer.applicability || 'None' }}</pre>
          <p>Verification evidence</p>
          <pre>{{ answer.verification || 'No testing evidence provided.' }}</pre>
        }
        <button mat-button type="button" (click)="useLatest()">
          Use latest version with my draft
        </button>
      }
    }
  `,
})
export class ContentEditor {
  readonly content = input.required<Content>();
  readonly kind = input.required<ContentKind>();
  readonly moderation = input(false);
  readonly changed = output<void>();
  readonly session = inject(IdentityService);
  private readonly api = inject(QuestionsApi);
  readonly busy = signal(false);
  readonly editing = signal(false);
  readonly error = signal('');
  readonly conflict = signal(false);
  readonly latest = signal<Content | null>(null);
  readonly contextFields = ['errorText', 'dependencies', 'versions', 'repository'] as const;
  context: Question['context'] = { errorText: '', dependencies: '', versions: '', repository: '' };
  title = '';
  body = '';
  tags = '';
  applicability = '';
  verification = '';
  private expectedVersion = 0;
  private prefix() {
    return this.moderation() ? '/admin' : '';
  }
  begin() {
    const content = this.content();
    this.expectedVersion = content.version;
    this.body = content.body;
    if ('title' in content) {
      this.title = content.title;
      this.tags = content.tags.join(', ');
      this.context = { ...content.context };
    }
    if ('applicability' in content) {
      this.applicability = content.applicability ?? '';
      this.verification = content.verification ?? '';
    }
    this.error.set('');
    this.conflict.set(false);
    this.latest.set(null);
    this.editing.set(true);
  }
  cancel() {
    this.editing.set(false);
    this.error.set('');
    this.conflict.set(false);
    this.latest.set(null);
  }
  latestQuestion(): Question | null {
    const latest = this.latest();
    return latest && 'title' in latest ? latest : null;
  }
  latestAnswer(): Answer | null {
    const latest = this.latest();
    return latest && 'applicability' in latest ? latest : null;
  }
  private fail(error: unknown) {
    this.error.set(contentError(error));
    this.conflict.set(error instanceof HttpErrorResponse && error.error?.code === 'stale_version');
  }
  async save() {
    if (this.busy() || this.conflict()) return;
    this.busy.set(true);
    this.error.set('');
    const content: Record<string, unknown> = {
      expectedVersion: this.expectedVersion,
      body: this.body,
    };
    if (this.kind() === 'question')
      Object.assign(content, {
        title: this.title,
        tags: this.tags
          .split(',')
          .map((t) => t.trim())
          .filter(Boolean),
        context: this.context,
      });
    if (this.kind() === 'answer')
      Object.assign(content, {
        applicability: this.applicability,
        verification: this.verification,
      });
    const collection = { question: 'questions', answer: 'answers', comment: 'comments' }[
      this.kind()
    ];
    try {
      await this.api.post(`${this.prefix()}/${collection}/${this.content().id}/edit`, content);
      this.editing.set(false);
      this.changed.emit();
    } catch (error) {
      this.fail(error);
    } finally {
      this.busy.set(false);
    }
  }
  async loadLatest() {
    this.busy.set(true);
    try {
      this.latest.set(
        await this.api.get<Content>(`${this.prefix()}/content/${this.kind()}/${this.content().id}`),
      );
    } catch (error) {
      this.error.set(contentError(error));
    } finally {
      this.busy.set(false);
    }
  }
  useLatest() {
    const latest = this.latest();
    if (latest) {
      this.expectedVersion = latest.version;
      this.conflict.set(false);
      this.error.set('');
    }
  }
  async visibility() {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await this.api.post(`/admin/content/${this.kind()}/${this.content().id}/visibility`, {
        expectedVersion: this.content().version,
        hidden: !this.content().hidden,
      });
      this.changed.emit();
    } catch (error) {
      this.error.set(contentError(error));
      this.changed.emit();
    } finally {
      this.busy.set(false);
    }
  }
}
