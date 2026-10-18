import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';

interface AgentSetupInfo {
  mcpUrl: string;
  issuer: string;
  scopes: string[];
  clientId: string;
  codexCallbackUrl: string | null;
  mcpJamCallbackUrl: string | null;
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
    const url = callback ? new URL(callback) : null;
    const port = url && ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) ? url.port : '';
    const scopes = value.scopes.map((scope) => JSON.stringify(scope)).join(', ');
    return `[mcp_servers.aso]\nurl = ${JSON.stringify(value.mcpUrl)}\nscopes = [${scopes}]\n\n[mcp_servers.aso.oauth]\nclient_id = ${JSON.stringify(value.clientId)}\ncallback_url = ${JSON.stringify(callback ?? 'YOUR_EXACT_REGISTERED_CALLBACK_URL')}${port ? `\ncallback_port = ${port}` : ''}`;
  }

  protected jamCommands(value: AgentSetupInfo): string {
    if (!value.mcpJamCallbackUrl) return '';
    return `npx --yes @mcpjam/cli@5.16.1 --no-telemetry oauth login \\\n  --url ${this.shellQuote(value.mcpUrl)} --protocol-version 2025-11-25 \\\n  --registration preregistered --client-id ${this.shellQuote(value.clientId)} \\\n  --redirect-url ${this.shellQuote(value.mcpJamCallbackUrl)} \\\n  --scopes ${this.shellQuote(value.scopes.join(' '))} --verify-tools --verify-call-tool identity \\\n  --credentials-out /tmp/aso-mcpjam-credentials.json\n\nnpx --yes @mcpjam/cli@5.16.1 --no-telemetry tools call \\\n  --url ${this.shellQuote(value.mcpUrl)} --credentials-file /tmp/aso-mcpjam-credentials.json \\\n  --tool-name identity --tool-args '{}' --validate-response --expect-success`;
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
