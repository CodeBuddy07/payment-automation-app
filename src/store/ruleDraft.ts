import { create } from 'zustand';
import type { ProcessorType, Rule, SenderMatchType } from '../native/types';

export type RuleDraft = {
  id?: number;
  name: string;
  senderPattern: string;
  senderMatchType: SenderMatchType;
  processorType: ProcessorType;
  template: string;
  regex: string;
  config: string;
  webhookId: number | null;
  payloadTemplate: string;
  enabled: boolean;
  priority: number;
  sampleMessages: string[];
};

export const emptyDraft = (): RuleDraft => ({
  name: '',
  senderPattern: '',
  senderMatchType: 'contains',
  processorType: 'template',
  template: '',
  regex: '',
  config: '',
  webhookId: null,
  payloadTemplate: '',
  enabled: true,
  priority: 0,
  sampleMessages: [],
});

export function fromRule(rule: Rule): RuleDraft {
  return {
    id: rule.id,
    name: rule.name,
    senderPattern: rule.senderPattern,
    senderMatchType: rule.senderMatchType,
    processorType: rule.processorType,
    template: rule.template ?? '',
    regex: rule.regex ?? '',
    config: rule.config ?? '',
    webhookId: rule.webhookId,
    payloadTemplate: rule.payloadTemplate ?? '',
    enabled: rule.enabled,
    priority: rule.priority,
    sampleMessages: rule.sampleMessages ? safeArray(rule.sampleMessages) : [],
  };
}

function safeArray(s: string): string[] {
  try {
    const v = JSON.parse(s);
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
}

interface DraftState {
  draft: RuleDraft;
  setDraft: (d: RuleDraft) => void;
  patch: (p: Partial<RuleDraft>) => void;
  reset: () => void;
}

// Shared between Create Rule and the Training (SMS picker) screen.
export const useRuleDraftStore = create<DraftState>(set => ({
  draft: emptyDraft(),
  setDraft: draft => set({ draft }),
  patch: p => set(s => ({ draft: { ...s.draft, ...p } })),
  reset: () => set({ draft: emptyDraft() }),
}));
