import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';

interface ClientRegistration {
  clientId: string;
  callbackUrl: string;
}

interface AgentSetupInfo {
  mcpUrl: string;
  issuer: string;
  scopes: string[];
  codex: ClientRegistration | null;
  mcpJam: ClientRegistration | null;
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

  protected codexConfig(value: AgentSetupInfo): string {
    const client = value.codex;
    if (!client) return '';
    const port = new URL(client.callbackUrl).port;
    return `[mcp_servers.aso]\nurl = ${JSON.stringify(value.mcpUrl)}\n\n[mcp_servers.aso.oauth]\nclient_id = ${JSON.stringify(client.clientId)}\ncallback_url = ${JSON.stringify(client.callbackUrl)}${port ? `\ncallback_port = ${port}` : ''}`;
  }

  protected codexLogin(value: AgentSetupInfo): string {
    return `codex mcp login aso --scopes ${value.scopes.join(',')}\n# Ask Codex to call ASO's identity tool and check your name.\ncodex mcp logout aso  # when signing out`;
  }

  protected jamCommands(value: AgentSetupInfo): string {
    const client = value.mcpJam;
    if (!client) return '';
    return `npx --yes @mcpjam/cli@5.16.1 --no-telemetry oauth login \\\n  --url ${this.shellQuote(value.mcpUrl)} --protocol-version 2025-11-25 \\\n  --registration preregistered --client-id ${this.shellQuote(client.clientId)} \\\n  --redirect-url ${this.shellQuote(client.callbackUrl)} \\\n  --scopes '${value.scopes.join(' ')}' --verify-tools --verify-call-tool identity \\\n  --credentials-out /tmp/aso-mcpjam-credentials.json\n\nnpx --yes @mcpjam/cli@5.16.1 --no-telemetry tools call \\\n  --url ${this.shellQuote(value.mcpUrl)} --credentials-file /tmp/aso-mcpjam-credentials.json \\\n  --tool-name identity --tool-args '{}' --validate-response --expect-success`;
  }

  private shellQuote(value: string): string {
    return `'${value.replaceAll("'", "'\\''")}'`;
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
