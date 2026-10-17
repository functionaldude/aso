import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { IdentityService } from '../core/identity';

@Component({
  selector: 'app-account-menu',
  imports: [MatButtonModule, MatMenuModule],
  template: `
    <button
      mat-button
      class="account-trigger"
      [matMenuTriggerFor]="account"
      aria-label="Open account menu"
    >
      <span class="workspace-dot" aria-hidden="true"></span>
      <span class="account-name">{{ session.identity()?.displayName }}</span
      ><span aria-hidden="true">⌄</span>
    </button>
    <mat-menu #account="matMenu">
      <div class="account-summary">
        <span>Signed in as</span><strong>{{ session.identity()?.displayName }}</strong>
        <span>{{ session.identity()?.admin ? 'Administrator' : 'Workspace member' }}</span>
      </div>
      @if (session.csrf(); as csrf) {
        <form method="post" action="/logout">
          <input type="hidden" [name]="csrf.parameterName" [value]="csrf.token" />
          <button mat-menu-item type="submit">Sign out</button>
        </form>
      }
    </mat-menu>
  `,
  styles: `
    .account-trigger {
      max-width: 260px;
    }
    .workspace-dot {
      display: inline-block;
      width: 5px;
      height: 5px;
      border-radius: 50%;
      background: var(--accent);
    }
    .account-name {
      display: inline-block;
      max-width: 180px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      vertical-align: middle;
      margin: 0 10px;
    }
    .account-summary {
      display: flex;
      flex-direction: column;
      gap: 6px;
      padding: 12px 20px 16px;
      max-width: min(300px, 80vw);
      overflow-wrap: anywhere;
      font: 0.7rem/1.6 var(--font-mono);
      color: var(--muted);
    }
    .account-summary strong {
      color: var(--ink);
      font-weight: 500;
    }
    @media (max-width: 599px) {
      .account-name {
        max-width: 108px;
        margin: 0 5px;
      }
    }
  `,
})
export class AccountMenu {
  protected readonly session = inject(IdentityService);
}
