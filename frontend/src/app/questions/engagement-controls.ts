import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { IdentityService } from '../core/identity';
import {
  Answer,
  ContentPage,
  Question,
  QuestionsApi,
  VoteEvidence,
  contentError,
} from './questions-api';

@Component({
  selector: 'app-engagement-controls',
  imports: [DatePipe, MatButtonModule],
  templateUrl: './engagement-controls.html',
})
export class EngagementControls {
  @Input({ required: true }) content!: Question | Answer;
  @Input({ required: true }) kind!: 'question' | 'answer';
  @Input() mode: 'all' | 'votes' | 'encounters' = 'all';
  @Output() changed = new EventEmitter<void>();
  readonly session = inject(IdentityService);
  private readonly api = inject(QuestionsApi);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly evidenceBusy = signal(false);
  readonly evidencePage = signal<ContentPage<VoteEvidence> | null>(null);
  private get storageKey() {
    return `aso:encounter:${this.session.identity()?.id}:${this.content.id}`;
  }
  get question(): Question | null {
    return this.kind === 'question' ? (this.content as Question) : null;
  }
  recorded() {
    return sessionStorage.getItem(this.storageKey)?.startsWith('done:') ?? false;
  }
  independent() {
    sessionStorage.removeItem(this.storageKey);
  }
  async vote() {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await this.api.post(`/content/${this.kind}/${this.content.id}/vote`, {
        active: !this.content.votes.votedByYou,
      });
      this.evidencePage.set(null);
      this.changed.emit();
    } catch (error) {
      this.error.set(contentError(error));
    } finally {
      this.busy.set(false);
    }
  }
  async encounter() {
    if (this.busy() || this.recorded()) return;
    this.busy.set(true);
    this.error.set('');
    const eventId = sessionStorage.getItem(this.storageKey) ?? crypto.randomUUID();
    sessionStorage.setItem(this.storageKey, eventId);
    try {
      await this.api.post(`/questions/${this.content.id}/occurrences`, { eventId });
      sessionStorage.setItem(this.storageKey, `done:${eventId}`);
      this.changed.emit();
    } catch (error) {
      this.error.set(contentError(error));
    } finally {
      this.busy.set(false);
    }
  }
  async evidence(cursor?: string) {
    this.evidenceBusy.set(true);
    this.error.set('');
    try {
      const page = await this.api.get<ContentPage<VoteEvidence>>(
        `/content/${this.kind}/${this.content.id}/votes`,
        cursor ? { cursor } : {},
      );
      this.evidencePage.set({
        ...page,
        items: cursor ? [...(this.evidencePage()?.items ?? []), ...page.items] : page.items,
      });
    } catch (error) {
      this.error.set(contentError(error));
    } finally {
      this.evidenceBusy.set(false);
    }
  }
}
