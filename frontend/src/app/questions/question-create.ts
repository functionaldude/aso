import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { IdentityService } from '../core/identity';
import { CreateRequest, Question, QuestionsApi, contentError } from './questions-api';

@Component({
  selector: 'app-question-create',
  imports: [FormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `<section class="questions-page form-page">
    <a class="back-link" routerLink="/questions">← Questions</a>
    <header class="page-heading">
      <div>
        <p class="eyebrow">Contribute / Question</p>
        <h1>Ask a question<span class="heading-cursor" aria-hidden="true">_</span></h1>
      </div>
    </header>
    @if (session.state() === 'member') {
      <p class="composer-help">
        Describe a specific problem. Markdown and code fences are supported. Remove credentials and
        unnecessary personal or source data before posting.
      </p>
      <form #form="ngForm" (ngSubmit)="submit()" class="content-form">
        <fieldset [disabled]="saving()">
          <legend>01 / The problem</legend>
          <mat-form-field
            ><mat-label>Title</mat-label
            ><input matInput name="title" [(ngModel)]="title" required maxlength="200" />
            <mat-hint>Summarize the specific problem in up to 200 characters.</mat-hint>
            <mat-error>A title is required.</mat-error></mat-form-field
          >
          <mat-form-field
            ><mat-label>Problem description (Markdown)</mat-label
            ><textarea
              matInput
              name="body"
              [(ngModel)]="body"
              required
              maxlength="20000"
              rows="10"
            ></textarea>
            <mat-hint>Include what you expected, what happened and what you tried.</mat-hint>
            <mat-error>A problem description is required.</mat-error>
          </mat-form-field>
          <mat-form-field
            ><mat-label>Tags (comma separated, up to 5)</mat-label
            ><input matInput name="tags" [(ngModel)]="tags" maxlength="204"
          /></mat-form-field>
          <details class="form-section">
            <summary>02 / Optional technical context</summary>
            <p class="composer-help">
              Exact errors and environment details make related problems easier to find.
            </p>
            <mat-form-field
              ><mat-label>Exact error text</mat-label
              ><textarea
                matInput
                name="errorText"
                [(ngModel)]="context.errorText"
                maxlength="2000"
                rows="3"
              ></textarea>
            </mat-form-field>
            <mat-form-field
              ><mat-label>Dependency coordinates</mat-label
              ><textarea
                matInput
                name="dependencies"
                [(ngModel)]="context.dependencies"
                maxlength="2000"
              ></textarea>
            </mat-form-field>
            <mat-form-field
              ><mat-label>Runtime / framework versions</mat-label
              ><textarea
                matInput
                name="versions"
                [(ngModel)]="context.versions"
                maxlength="2000"
              ></textarea>
            </mat-form-field>
            <mat-form-field
              ><mat-label>Repository / module labels</mat-label
              ><textarea
                matInput
                name="repository"
                [(ngModel)]="context.repository"
                maxlength="2000"
              ></textarea>
            </mat-form-field>
          </details>
        </fieldset>
        @if (error()) {
          <p class="notice error" role="alert">{{ error() }}</p>
        }
        <button mat-flat-button [disabled]="form.invalid || saving()">
          {{ saving() ? 'Publishing…' : 'Publish question' }}
        </button>
      </form>
    } @else {
      <p class="notice">Sign in with workspace membership to create a question.</p>
    }
  </section>`,
})
export class QuestionCreate {
  readonly session = inject(IdentityService);
  private readonly api = inject(QuestionsApi);
  private readonly router = inject(Router);
  private readonly request = new CreateRequest();
  title = '';
  body = '';
  tags = '';
  context = { errorText: '', dependencies: '', versions: '', repository: '' };
  readonly saving = signal(false);
  readonly error = signal('');
  async submit() {
    if (this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    try {
      const question = await this.api.create<Question>(
        '',
        this.request.payload({
          title: this.title,
          body: this.body,
          tags: this.tags
            .split(',')
            .map((tag) => tag.trim())
            .filter(Boolean),
          context: this.context,
        }),
      );
      await this.router.navigate(['/questions', question.id]);
    } catch (error) {
      this.error.set(contentError(error));
    } finally {
      this.saving.set(false);
    }
  }
}
