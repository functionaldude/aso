import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./home/home').then((m) => m.Home) },
  { path: 'about', loadComponent: () => import('./about/about').then((m) => m.About) },
  {
    path: 'questions',
    loadComponent: () => import('./questions/question-list').then((m) => m.QuestionList),
  },
  {
    path: 'questions/new',
    loadComponent: () => import('./questions/question-create').then((m) => m.QuestionCreate),
  },
  {
    path: 'questions/:id',
    loadComponent: () => import('./questions/question-detail').then((m) => m.QuestionDetail),
  },
  {
    path: 'moderation',
    loadComponent: () => import('./questions/moderation-list').then((m) => m.ModerationList),
  },
  {
    path: 'moderation/:id',
    data: { moderation: true },
    loadComponent: () => import('./questions/question-detail').then((m) => m.QuestionDetail),
  },
  { path: '**', redirectTo: '' },
];
