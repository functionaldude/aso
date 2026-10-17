import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AgentSetup } from './agent-setup';

const details = {
  mcpUrl: 'http://127.0.0.1:8080/mcp',
  issuer: 'http://127.0.0.1:8180/realms/aso-dev',
  scopes: ['openid', 'profile', 'email'],
  codex: { clientId: 'aso-codex', callbackUrl: 'http://127.0.0.1:8766/callback' },
  mcpJam: { clientId: 'aso-mcpjam', callbackUrl: 'http://localhost:6274/oauth/callback' },
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
    expect((host.querySelector('#jam-commands') as HTMLTextAreaElement).value).toContain(
      details.mcpUrl,
    );
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
});
