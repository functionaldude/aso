import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Comment, ContentPage, CreateRequest, QuestionsApi, contentError } from './questions-api';
import { ContentEditor } from './content-editor';
@Component({
  selector: 'app-content-comments',
  imports: [
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    ContentEditor,
  ],
  styleUrl: './questions.scss',
  template: `<section aria-label="Comments and context">
    <h3>Comments and context</h3>
    @for (comment of comments(); track comment.id) {
      <div class="comment">
        <p class="metadata">
          {{ comment.author.displayName }} · {{ comment.createdAt | date: 'medium' }} ·
          {{ comment.channel }}
        </p>
        <div class="markdown" [innerHTML]="comment.bodyHtml"></div>
        <app-content-editor
          kind="comment"
          [content]="comment"
          [moderation]="moderation()"
          (changed)="load(); changed.emit()"
        />
      </div>
    }
    @if (next()) {
      <button mat-button [disabled]="busy() || loading()" (click)="load(true)">
        Load more comments
      </button>
    }
    @if (error()) {
      <p role="alert">{{ error() }}</p>
      <button mat-button [disabled]="busy()" (click)="load()">Retry comments</button>
    }
    @if (!moderation()) {
      <form class="content-form" #form="ngForm" (ngSubmit)="submit()">
        <mat-form-field
          ><mat-label>Add context (Markdown)</mat-label
          ><textarea
            matInput
            name="comment"
            [(ngModel)]="body"
            required
            maxlength="20000"
            rows="3"
            [disabled]="busy()"
          ></textarea>
        </mat-form-field>
        <button mat-button [disabled]="form.invalid || busy()">Publish context</button>
      </form>
    }
  </section>`,
})
export class ContentComments {
  readonly kind = input.required<'question' | 'answer'>();
  readonly targetId = input.required<string>();
  readonly moderation = input(false);
  readonly changed = output<void>();
  private readonly api = inject(QuestionsApi);
  private readonly request = new CreateRequest();
  readonly comments = signal<Comment[]>([]);
  readonly next = signal<string | null>(null);
  readonly error = signal('');
  readonly busy = signal(false);
  readonly loading = signal(false);
  private generation = 0;
  body = '';
  constructor() {
    effect(() => {
      this.kind();
      this.targetId();
      this.moderation();
      this.comments.set([]);
      this.next.set(null);
      untracked(() => {
        void this.load();
      });
    });
  }
  private path() {
    return `/content/${this.kind()}/${this.targetId()}/comments`;
  }
  async load(more = false) {
    if (more && this.loading()) return;
    const generation = ++this.generation;
    const previous = this.comments();
    this.loading.set(true);
    this.error.set('');
    try {
      const page = await this.api.get<ContentPage<Comment>>(
        `${this.moderation() ? '/admin' : ''}${this.path()}`,
        more && this.next() ? { cursor: this.next()! } : {},
      );
      if (generation !== this.generation) return;
      this.comments.set(more ? [...previous, ...page.items] : page.items);
      this.next.set(page.nextCursor);
    } catch (error) {
      if (generation === this.generation) this.error.set(contentError(error));
    } finally {
      if (generation === this.generation) this.loading.set(false);
    }
  }
  async submit() {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await this.api.post(this.path(), this.request.payload({ body: this.body }));
      this.body = '';
      this.request.reset();
      await this.load();
    } catch (error) {
      this.error.set(contentError(error));
    } finally {
      this.busy.set(false);
    }
  }
}
