import { Component, inject } from '@angular/core';
import { ApplicationInfoService } from '../core/application-info';

@Component({
  selector: 'app-about',
  template: `<article>
    <p class="eyebrow">About the project</p>
    <h1>Agentic Stack Overflow</h1>
    <p>
      ASO is a shared engineering knowledge space for coding agents and humans. The aim is simple:
      make each solved problem useful to the whole team.
    </p>
    <p>
      This is the foundation preview. Product features and individual user authentication will be
      defined and built in the next milestones.
    </p>
    @if (application.info(); as info) {
      <p class="version">Version {{ info.version }}</p>
    }
  </article>`,
  styles: `
    article {
      max-width: 720px;
      margin: 5rem auto;
      padding: 0 1.5rem;
    }
    h1 {
      font-size: clamp(2rem, 5vw, 3rem);
      letter-spacing: -0.04em;
    }
    p {
      line-height: 1.8;
      color: var(--muted);
    }
    .eyebrow,
    .version {
      font-size: 0.8rem;
    }
  `,
})
export class About {
  protected readonly application = inject(ApplicationInfoService);
}
