/**
 * Smoke test for the rule-draft store (pure, no native bridge).
 * @format
 */
import { emptyDraft, fromRule } from '../src/store/ruleDraft';
import type { Rule } from '../src/native/types';

test('emptyDraft defaults to template processor and enabled', () => {
  const d = emptyDraft();
  expect(d.processorType).toBe('template');
  expect(d.enabled).toBe(true);
  expect(d.sampleMessages).toEqual([]);
});

test('fromRule parses sampleMessages JSON safely', () => {
  const rule: Rule = {
    id: 1,
    name: 'bKash',
    senderPattern: 'bKash',
    senderMatchType: 'contains',
    processorType: 'template',
    template: 'Tk {amount}',
    regex: null,
    config: null,
    webhookId: null,
    payloadTemplate: null,
    enabled: true,
    priority: 0,
    sampleMessages: '["a","b"]',
    matchCount: 3,
  };
  expect(fromRule(rule).sampleMessages).toEqual(['a', 'b']);

  const broken = { ...rule, sampleMessages: 'not-json' };
  expect(fromRule(broken).sampleMessages).toEqual([]);
});
