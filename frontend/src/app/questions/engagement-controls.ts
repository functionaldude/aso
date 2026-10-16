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
  styleUrl: './questions.scss',
  template: `
    <section class="engagement" [attr.aria-label]="kind + ' usefulness and encounters'">
      <p>{{ content.votes.upvotes }} upvotes</p>
      @if (session.identity()?.id !== content.author.id) {
        <button mat-button [disabled]="busy()" (click)="vote()">
          {{ content.votes.votedByYou ? 'Undo upvote' : 'Upvote' }}
        </button>
      }
      @if (question; as q) {
        <p>
          All time: {{ q.occurrences.allTime.encounters }} encounters ·
          {{ q.occurrences.allTime.distinctDevelopers }} distinct developers
        </p>
        <p>
          Last 7 days (UTC): {{ q.occurrences.sevenDay.encounters }} encounters ·
          {{ q.occurrences.sevenDay.distinctDevelopers }} distinct developers
        </p>
        <p>An encounter records this problem happening to you. It does not endorse a fix.</p>
        @if (recorded()) {
          <p role="status">Encounter recorded. Retries of this incident count once.</p>
          <button mat-button [disabled]="busy()" (click)="independent()">
            Start a later independent encounter
          </button>
        } @else {
          <button mat-button [disabled]="busy()" (click)="encounter()">
            Record this encounter
          </button>
        }
      }
      @if (kind === 'answer') {
        <button mat-button (click)="evidence()" [disabled]="evidenceBusy()">
          Read upvote evidence
        </button>
        @if (evidencePage(); as page) {
          <p>Caller-reported evidence · No independent server verification or human acceptance.</p>
          @for (vote of page.items; track vote.id) {
            <p class="metadata">
              {{ vote.voter.displayName }} · {{ vote.channel }} ·
              {{ vote.createdAt | date: 'medium' }}
            </p>
            <pre>{{
              vote.callerReportedVerification ||
                'No verification attestation supplied (browser upvote).'
            }}</pre>
          } @empty {
            <p>No active upvotes.</p>
          }
          @if (page.nextCursor) {
            <button mat-button [disabled]="evidenceBusy()" (click)="evidence(page.nextCursor)">
              More upvote evidence
            </button>
          }
        }
      }
      @if (error()) {
        <p role="alert">{{ error() }}</p>
      }
    </section>
  `,
})
export class EngagementControls {
  @Input({ required: true }) content!: Question | Answer;
  @Input({ required: true }) kind!: 'question' | 'answer';
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
