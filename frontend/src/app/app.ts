import { Component, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { BreakpointObserver } from '@angular/cdk/layout';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { IdentityService } from './core/identity';
import { ApplicationInfoService } from './core/application-info';
import { CdkTrapFocus } from '@angular/cdk/a11y';
import { AccountMenu } from './shared/account-menu';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, CdkTrapFocus, AccountMenu],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly session = inject(IdentityService);
  protected readonly application = inject(ApplicationInfoService);
  private readonly router = inject(Router);
  protected readonly wide = toSignal(
    inject(BreakpointObserver)
      .observe('(min-width: 1024px)')
      .pipe(map((value) => value.matches)),
    { initialValue: false },
  );
  protected readonly navigationOpen = signal(false);
  protected readonly section = signal('workspace');
  private firstNavigation = true;
  @ViewChild('main') private main?: ElementRef<HTMLElement>;
  @ViewChild('navigationToggle', { read: ElementRef })
  private navigationToggle?: ElementRef<HTMLButtonElement>;

  protected openNavigation() {
    this.navigationOpen.set(true);
  }

  protected closeNavigation() {
    this.navigationOpen.set(false);
    requestAnimationFrame(() => this.navigationToggle?.nativeElement.focus());
  }

  constructor() {
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe((event) => {
        this.section.set(event.urlAfterRedirects.split(/[/?]/)[1] || 'workspace');
        this.navigationOpen.set(false);
        if (!this.firstNavigation) requestAnimationFrame(() => this.main?.nativeElement.focus());
        this.firstNavigation = false;
      });
  }
}
