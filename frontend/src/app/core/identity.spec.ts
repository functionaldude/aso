import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IdentityService } from './identity';

describe('Browser identity recovery', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('clears stale identity after session expiry and recovers on refresh', () => {
    const session = TestBed.inject(IdentityService);
    const http = TestBed.inject(HttpTestingController);
    const member = {
      id: 'one',
      subject: 'alice',
      displayName: 'Alice',
      admin: false,
      channel: 'web',
    };
    http.expectOne('/api/v1/identity').flush(member);
    http.expectOne('/api/v1/csrf').flush({ token: 'one', parameterName: '_csrf' });
    expect(session.state()).toBe('member');
    session.refresh();
    http.expectOne('/api/v1/identity').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/csrf').flush({ token: 'two', parameterName: '_csrf' });
    expect(session.identity()).toBeNull();
    expect(session.state()).toBe('signedOut');
    session.refresh();
    http.expectOne('/api/v1/identity').flush(member);
    http.expectOne('/api/v1/csrf').flush({ token: 'three', parameterName: '_csrf' });
    expect(session.state()).toBe('member');
  });
  it('distinguishes membership denial from an outage', () => {
    const session = TestBed.inject(IdentityService);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/identity').flush({}, { status: 403, statusText: 'Forbidden' });
    http.expectOne('/api/v1/csrf').flush({ token: 'one', parameterName: '_csrf' });
    expect(session.state()).toBe('denied');
    session.refresh();
    http.expectOne('/api/v1/identity').flush({}, { status: 503, statusText: 'Unavailable' });
    http.expectOne('/api/v1/csrf').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(session.state()).toBe('unavailable');
    expect(session.csrf()).toBeNull();
  });
});
