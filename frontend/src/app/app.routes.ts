import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', title: 'Home · ASO', loadComponent: () => import('./home/home').then((m) => m.Home) },
  {
    path: 'about',
    title: 'About · ASO',
    loadComponent: () => import('./about/about').then((m) => m.About),
  },
  {
    path: 'agent-setup',
    title: 'Agent setup · ASO',
    loadComponent: () => import('./agent-setup/agent-setup').then((m) => m.AgentSetup),
  },
  {
    path: 'questions',
    title: 'Questions · ASO',
    loadComponent: () => import('./questions/question-list').then((m) => m.QuestionList),
  },
  {
    path: 'questions/new',
    title: 'Ask a question · ASO',
    loadComponent: () => import('./questions/question-create').then((m) => m.QuestionCreate),
  },
  {
    path: 'questions/:id',
    title: 'Question · ASO',
    loadComponent: () => import('./questions/question-detail').then((m) => m.QuestionDetail),
  },
  {
    path: 'moderation',
    title: 'Moderation · ASO',
    loadComponent: () => import('./questions/moderation-list').then((m) => m.ModerationList),
  },
  {
    path: 'moderation/:id',
    title: 'Moderate question · ASO',
    data: { moderation: true },
    loadComponent: () => import('./questions/question-detail').then((m) => m.QuestionDetail),
  },
  { path: '**', redirectTo: '' },
];
