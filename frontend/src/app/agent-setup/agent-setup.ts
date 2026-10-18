import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';

interface AgentSetupInfo {
  mcpUrl: string;
  issuer: string;
  scopes: string[];
  clientId: string;
  codexCallbackUrl: string;
  recommendedParagraph: string;
}

@Component({
  selector: 'app-agent-setup',
  imports: [MatButtonModule],
  templateUrl: './agent-setup.html',
  styleUrl: './agent-setup.scss',
})
export class AgentSetup {
  private readonly http = inject(HttpClient);
  protected readonly setup = signal<AgentSetupInfo | null>(null);
  protected readonly error = signal(false);
  protected readonly copied = signal<Record<string, string>>({});

  constructor() {
    this.load();
  }

  protected load(): void {
    this.error.set(false);
    this.http.get<AgentSetupInfo>('/api/v1/agent-setup').subscribe({
      next: (value) => this.setup.set(value),
      error: () => this.error.set(true),
    });
  }

  protected readonly codexOpenConfig =
    'mkdir -p "${CODEX_HOME:-$HOME/.codex}" && nano "${CODEX_HOME:-$HOME/.codex}/config.toml"';
  protected readonly codexLogin = 'codex mcp login aso';
  protected readonly codexLogout = 'codex mcp logout aso';

  protected codexConfig(value: AgentSetupInfo): string {
    const callback = value.codexCallbackUrl;
    const url = new URL(callback);
    const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
    // URL.port omits explicit default ports, which still need a matching listener.
    const explicitPort = /^https?:\/\/(?:\[[^\]]+\]|[^/:]+):[0-9]+(?:\/|$)/.test(callback);
    const port =
      loopback && explicitPort ? Number(url.port || (url.protocol === 'https:' ? 443 : 80)) : null;
    const scopes = value.scopes.map((scope) => JSON.stringify(scope)).join(', ');
    return `[mcp_servers.aso]\nurl = ${JSON.stringify(value.mcpUrl)}\nscopes = [${scopes}]\n\n[mcp_servers.aso.oauth]\nclient_id = ${JSON.stringify(value.clientId)}\ncallback_url = ${JSON.stringify(callback)}${port !== null ? `\ncallback_port = ${port}` : ''}`;
  }

  protected async copy(key: string, value: string, field: HTMLTextAreaElement): Promise<void> {
    try {
      await navigator.clipboard.writeText(value);
      this.copied.update((current) => ({ ...current, [key]: 'Copied to clipboard.' }));
    } catch {
      field.focus();
      field.select();
      this.copied.update((current) => ({
        ...current,
        [key]: 'Clipboard unavailable. Text selected; use your device’s copy command.',
      }));
    }
  }
}
