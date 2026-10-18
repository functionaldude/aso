import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AgentSetup } from './agent-setup';

const details = {
  mcpUrl: 'http://127.0.0.1:8080/mcp',
  issuer: 'http://127.0.0.1:8180/realms/aso-dev',
  scopes: ['openid', 'profile', 'email', 'offline_access'],
  clientId: 'aso',
  codexCallbackUrl: 'http://127.0.0.1:8766/callback',
  recommendedParagraph: 'Use ASO under my individual identity.',
};

describe('Agent setup', () => {
  const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');

  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [AgentSetup],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }),
  );
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    if (originalClipboard) Object.defineProperty(navigator, 'clipboard', originalClipboard);
    else Reflect.deleteProperty(navigator, 'clipboard');
  });

  it('uses the configured backend URL even when the UI origin differs and copies the canonical paragraph', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    const fixture = TestBed.createComponent(AgentSetup);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/agent-setup').flush(details);
    await fixture.whenStable();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect((host.querySelector('#mcp-url') as HTMLTextAreaElement).value).toBe(details.mcpUrl);
    expect((host.querySelector('#codex-config') as HTMLTextAreaElement).value).toContain(
      details.mcpUrl,
    );
    const config = (host.querySelector('#codex-config') as HTMLTextAreaElement).value;
    expect(config).toContain('scopes = ["openid", "profile", "email", "offline_access"]');
    expect(config).toContain('client_id = "aso"');
    expect(config).toContain(
      'callback_url = "http://127.0.0.1:8766/callback"\ncallback_port = 8766',
    );
    expect((host.querySelector('#codex-login') as HTMLTextAreaElement).value).toBe(
      'codex mcp login aso',
    );
    expect(host.textContent).toContain('~/.codex/config.toml');
    expect(host.textContent).toContain('instead of adding a duplicate');
    expect(host.textContent).not.toContain('MCP Jam');
    expect(host.textContent).not.toContain('Actual client verified');
    expect(host.querySelectorAll('details[open]').length).toBe(0);
    expect(
      Array.from(host.querySelectorAll('h2'))
        .slice(0, 4)
        .map((heading) => heading.textContent),
    ).toEqual([
      '1. Open your Codex configuration',
      '2. Paste this configuration and save',
      '3. Sign in to ASO',
      '4. Reload Codex and check your identity',
    ]);
    expect((host.querySelector('#recommended-paragraph') as HTMLTextAreaElement).value).toBe(
      details.recommendedParagraph,
    );
    (
      Array.from(host.querySelectorAll('button')).find((button) =>
        button.textContent?.includes('Copy recommended paragraph'),
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(writeText).toHaveBeenCalledWith(details.recommendedParagraph);
    expect(host.textContent).toContain('Copied to clipboard.');
  });

  it('uses the installation URL and shared ID with an overridden loopback callback', async () => {
    const fixture = TestBed.createComponent(AgentSetup);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/agent-setup')
      .flush({
        ...details,
        mcpUrl: 'https://knowledge.example.invalid/mcp',
        clientId: 'team-knowledge',
        codexCallbackUrl: 'http://127.0.0.1:9876/callback',
      });
    await fixture.whenStable();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const config = (host.querySelector('#codex-config') as HTMLTextAreaElement).value;
    expect(config).toContain('url = "https://knowledge.example.invalid/mcp"');
    expect(config).toContain('client_id = "team-knowledge"');
    expect(config).toContain(
      'callback_url = "http://127.0.0.1:9876/callback"\ncallback_port = 9876',
    );
  });

  it.each([
    ['http://127.0.0.1:80/callback', 80],
    ['https://[::1]:443/callback', 443],
    ['http://localhost:9876/callback', 9876],
  ])('preserves an explicit loopback port in %s', async (callback, port) => {
    const fixture = TestBed.createComponent(AgentSetup);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/agent-setup')
      .flush({
        ...details,
        codexCallbackUrl: callback,
      });
    await fixture.whenStable();
    fixture.detectChanges();
    const config = (fixture.nativeElement.querySelector('#codex-config') as HTMLTextAreaElement)
      .value;
    expect(config).toContain(`callback_url = "${callback}"`);
    expect(config).toContain(`callback_port = ${port}`);
  });

  it('does not infer a local listener port from a remote proxy callback', async () => {
    const fixture = TestBed.createComponent(AgentSetup);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/agent-setup')
      .flush({
        ...details,
        scopes: ['openid', 'profile'],
        codexCallbackUrl: 'https://devbox.example.invalid:8443/callback',
      });
    await fixture.whenStable();
    fixture.detectChanges();
    const config = (fixture.nativeElement.querySelector('#codex-config') as HTMLTextAreaElement)
      .value;
    expect(config).toContain('scopes = ["openid", "profile"]');
    expect(config).toContain('callback_url = "https://devbox.example.invalid:8443/callback"');
    expect(config).not.toContain('callback_port');
  });

  it('selects the text and explains the manual copy fallback when clipboard access fails', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockRejectedValue(new Error('blocked')) },
    });
    const fixture = TestBed.createComponent(AgentSetup);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/agent-setup').flush(details);
    await fixture.whenStable();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    (
      Array.from(host.querySelectorAll('button')).find((button) =>
        button.textContent?.includes('Copy MCP URL'),
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();
    fixture.detectChanges();
    const field = host.querySelector('#mcp-url') as HTMLTextAreaElement;
    expect(document.activeElement).toBe(field);
    expect(field.value.substring(field.selectionStart, field.selectionEnd)).toBe(details.mcpUrl);
    expect(host.textContent).toContain('Text selected; use your device’s copy command.');
  });
  it('switches between client setup instructions and generates installation-specific Cursor JSON', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    const fixture = TestBed.createComponent(AgentSetup);
    fixture.detectChanges();
    const installation = {
      ...details,
      mcpUrl: 'https://knowledge.example.invalid/mcp',
      clientId: 'team-knowledge',
    };
    TestBed.inject(HttpTestingController).expectOne('/api/v1/agent-setup').flush(installation);
    await fixture.whenStable();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const tabs = Array.from(host.querySelectorAll<HTMLElement>('[role="tab"]'));
    expect(tabs.map((tab) => tab.textContent?.trim())).toEqual(['Codex', 'Claude Code', 'Cursor']);
    expect(tabs[0].getAttribute('aria-selected')).toBe('true');
    tabs[1].click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(host.querySelector('#codex-config')).toBeNull();
    expect(host.querySelector('#claude-config')).not.toBeNull();
    expect((host.querySelector('#claude-login') as HTMLTextAreaElement).value).toBe(
      'claude mcp login aso',
    );
    expect((host.querySelector('#claude-import') as HTMLTextAreaElement).value).toBe('@AGENTS.md');
    expect(host.textContent).toContain('claude mcp remove aso --scope user');
    expect(host.querySelectorAll('details[open]').length).toBe(0);
    tabs[2].click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(host.querySelector('#claude-config')).toBeNull();
    const field = host.querySelector('#cursor-config') as HTMLTextAreaElement;
    expect(JSON.parse(field.value)).toEqual({
      mcpServers: {
        aso: {
          url: installation.mcpUrl,
          auth: { CLIENT_ID: installation.clientId, scopes: details.scopes },
        },
      },
    });
    expect(host.textContent).toContain('~/.cursor/mcp.json');
    expect(host.textContent).toContain('Customize → MCPs');
    expect(host.textContent).not.toContain('claude mcp logout');
    (
      Array.from(host.querySelectorAll('button')).find((button) =>
        button.textContent?.includes('Copy Cursor configuration'),
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(writeText).toHaveBeenCalledWith(field.value);
    expect((host.querySelector('#recommended-paragraph') as HTMLTextAreaElement).value).toBe(
      details.recommendedParagraph,
    );
  });

  it('protects quotes and shell substitutions in the Claude command and escapes Cursor JSON', async () => {
    const fixture = TestBed.createComponent(AgentSetup);
    fixture.detectChanges();
    const clientId =
      'team' + "'" + 's "memory" $(touch /tmp/aso-should-not-exist) `id` $HOME\\path';
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/agent-setup')
      .flush({ ...details, clientId });
    await fixture.whenStable();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const tabs = host.querySelectorAll<HTMLElement>('[role="tab"]');
    tabs[1].click();
    await fixture.whenStable();
    fixture.detectChanges();
    const command = (host.querySelector('#claude-config') as HTMLTextAreaElement).value;
    expect(command.startsWith("claude mcp add-json aso '")).toBe(true);
    expect(command.endsWith("' --scope user")).toBe(true);
    // Decode the POSIX single-quote escape, then validate the independent JSON wire shape.
    const payload = command
      .slice("claude mcp add-json aso '".length, -"' --scope user".length)
      .replaceAll("'\"'\"'", "'");
    expect(JSON.parse(payload)).toEqual({
      type: 'http',
      url: details.mcpUrl,
      oauth: {
        clientId,
        callbackPort: 8767,
        scopes: 'openid profile email offline_access',
      },
    });
    expect(payload).not.toContain('clientSecret');
    tabs[2].click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(
      JSON.parse((host.querySelector('#cursor-config') as HTMLTextAreaElement).value).mcpServers.aso
        .auth.CLIENT_ID,
    ).toBe(clientId);
  });
});
