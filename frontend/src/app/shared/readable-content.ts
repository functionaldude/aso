import { afterEveryRender, DestroyRef, Directive, ElementRef, inject } from '@angular/core';

/** Adds keyboard scrolling to wide rendered code/tables without altering sanitized HTML. */
@Directive({ selector: '[appReadableContent]' })
export class ReadableContent {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  constructor() {
    const update = () => {
      this.host.querySelectorAll<HTMLElement>('pre, table').forEach((block) => {
        if (block.scrollWidth > block.clientWidth || block.scrollHeight > block.clientHeight) {
          block.tabIndex = 0;
          block.setAttribute('role', 'region');
          block.setAttribute(
            'aria-label',
            `${block.tagName === 'PRE' ? 'Code block' : 'Table'}, ${block.scrollWidth > block.clientWidth ? 'scroll horizontally' : 'scroll to read'}`,
          );
        } else {
          block.removeAttribute('tabindex');
          block.removeAttribute('role');
          block.removeAttribute('aria-label');
        }
      });
    };
    afterEveryRender(update);
    if (typeof ResizeObserver !== 'undefined') {
      const observer = new ResizeObserver(update);
      observer.observe(this.host);
      inject(DestroyRef).onDestroy(() => observer.disconnect());
    }
  }
}
