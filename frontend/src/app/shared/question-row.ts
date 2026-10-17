import { Component, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Question, SearchResult } from '../questions/questions-api';

@Component({
  selector: 'app-question-row',
  imports: [DatePipe, RouterLink],
  template: `
    <article class="question-row">
      <div class="question-summary">
        <div class="row-state">
          <span class="status-badge" [class.resolved]="question().state === 'resolved'"
            ><span aria-hidden="true">{{ question().state === 'resolved' ? '✓' : '○' }}</span
            >{{ question().state === 'resolved' ? 'Resolved' : 'Open' }}</span
          >
          @if (question().hidden) {
            <span class="status-badge hidden">Hidden</span>
          }
        </div>
        <h2>
          <a [routerLink]="[moderation() ? '/moderation' : '/questions', question().id]">{{
            question().title
          }}</a>
        </h2>
        <p class="metadata">
          <span class="author">{{ question().author.displayName }}</span
          ><span aria-hidden="true"> · </span>{{ question().createdAt | date: 'MMM d, y' }} ·
          {{ question().channel }}
        </p>
        @if (match(); as result) {
          <p class="search-snippet">{{ result.snippet }}</p>
          <p class="metadata match-explanation">
            {{ result.match.explanation }} ·
            {{ question().acceptedAnswerId ? 'Has a human-accepted answer' : 'No accepted answer' }}
          </p>
        }
        <div class="tags">
          @for (tag of question().tags; track tag) {
            <a routerLink="/questions" [queryParams]="{ tag, state: 'all' }">{{ tag }}</a>
          }
        </div>
      </div>
      <div class="row-metrics" aria-label="Question engagement">
        <div class="usefulness">
          <span class="metric-label">Usefulness</span>
          <p>
            <strong>{{ question().votes.upvotes }}</strong> upvotes
          </p>
        </div>
        <div class="encounters">
          <span class="metric-label">Encounters</span>
          <p>
            All time: {{ question().occurrences.allTime.encounters }} encounters ·
            {{ question().occurrences.allTime.distinctDevelopers }} distinct developers
          </p>
          <p>
            Last 7 days (UTC): {{ question().occurrences.sevenDay.encounters }} encounters ·
            {{ question().occurrences.sevenDay.distinctDevelopers }} distinct developers
          </p>
        </div>
      </div>
    </article>
  `,
  styleUrl: './question-row.scss',
})
export class QuestionRow {
  readonly question = input.required<Question>();
  readonly match = input<SearchResult>();
  readonly moderation = input(false);
}
