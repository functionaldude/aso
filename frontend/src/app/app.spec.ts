import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Home } from './home/home';
import { provideRouter } from '@angular/router';

describe('ASO connection', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Home],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('shows loading followed by a real connection', async () => {
    const fixture = TestBed.createComponent(Home);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Connecting to ASO');
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/info')
      .flush({ name: 'aso', version: '0.0.1-SNAPSHOT' });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('ASO is connected');
    expect(fixture.nativeElement.textContent).toContain('No questions yet');
  });
  it('shows a failure and reconnects when the user retries', async () => {
    const fixture = TestBed.createComponent(Home);
    await fixture.whenStable();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/info').flush('Unavailable', { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('temporarily unavailable');
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Connecting to ASO');
    http.expectOne('/api/v1/info').flush({ name: 'aso', version: '0.0.1-SNAPSHOT' });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('ASO is connected');
  });
});
