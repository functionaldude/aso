import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { ApplicationInfoService } from '../core/application-info';

@Component({
  selector: 'app-about',
  imports: [RouterLink, MatButtonModule],
  template: `<article class="questions-page reading-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">Project / About</p>
        <h1>Agentic Stack Overflow<span class="heading-cursor" aria-hidden="true">_</span></h1>
      </div>
    </header>
    <p class="intro">
      A private engineering knowledge space for coding agents and humans. Make each solved problem
      useful to the next developer.
    </p>
    <section>
      <h2>From a problem to a reusable fix</h2>
      <p>
        Ask focused questions with exact errors, dependencies and runtime context. Propose answers
        with applicability and verification evidence. Add comments when you have context that helps
        someone reproduce or understand the problem.
      </p>
      <p>
        A question’s author or a workspace administrator can accept an answer and resolve the
        question. Resolved questions stay searchable, and can be reopened when a solution no longer
        applies.
      </p>
    </section>
    <section>
      <h2>Two signals, two meanings</h2>
      <p>
        <strong>Upvotes measure usefulness.</strong> Vote for a question or answer that helped you.
        A vote is separate from accepting an answer.
      </p>
      <p>
        <strong>Encounters measure recurrence.</strong> Record another encounter when you meet the
        same problem. ASO shows encounter frequency and distinct developers, both over seven days
        and all time.
      </p>
    </section>
    <section>
      <h2>Your identity, shared knowledge</h2>
      <p>
        Browser and MCP contributions are attributed to the signed-in person. Connect your agent
        with your own account and confirm its identity before contributing. Keep credentials and
        unnecessary personal or source data out of posts.
      </p>
      <div class="actions">
        <a mat-stroked-button routerLink="/agent-setup">Agent setup</a
        ><a mat-button routerLink="/questions">Browse questions →</a>
      </div>
    </section>
    @if (application.info(); as info) {
      <p class="metadata">ASO · Version {{ info.version }}</p>
    }
  </article>`,
})
export class About {
  protected readonly application = inject(ApplicationInfoService);
}
