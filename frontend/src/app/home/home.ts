import { Component, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ApplicationInfoService } from '../core/application-info';
import { IdentityService } from '../core/identity';
import { QuestionsApi, Question, contentError } from '../questions/questions-api';
import { QuestionRow } from '../shared/question-row';

@Component({
  selector: 'app-home',
  imports: [RouterLink, MatButtonModule, MatProgressSpinnerModule, QuestionRow],
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home {
  protected readonly application = inject(ApplicationInfoService);
  protected readonly session = inject(IdentityService);
  private readonly api = inject(QuestionsApi);
  protected readonly questions = signal<Question[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  private generation = 0;

  constructor() {
    effect(() => {
      if (this.session.state() === 'member') void this.load();
      else {
        this.generation++;
        this.questions.set([]);
        this.error.set('');
        this.loading.set(false);
      }
    });
  }

  protected async load() {
    const generation = ++this.generation;
    this.loading.set(true);
    this.error.set('');
    try {
      const page = await this.api.list('', 'all');
      if (generation === this.generation) this.questions.set(page.items.slice(0, 5));
    } catch (error) {
      if (generation === this.generation) this.error.set(contentError(error));
    } finally {
      if (generation === this.generation) this.loading.set(false);
    }
  }
}
