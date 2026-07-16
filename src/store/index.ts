import { create } from 'zustand';
import { SmsGateway } from '../native/SmsGateway';
import type { QueueStats, Rule, Webhook } from '../native/types';

interface RulesState {
  rules: Rule[];
  loading: boolean;
  load: () => Promise<void>;
  toggle: (id: number, enabled: boolean) => Promise<void>;
  remove: (id: number) => Promise<void>;
}

export const useRulesStore = create<RulesState>(set => ({
  rules: [],
  loading: false,
  load: async () => {
    set({ loading: true });
    try {
      set({ rules: await SmsGateway.getRules() });
    } finally {
      set({ loading: false });
    }
  },
  toggle: async (id, enabled) => {
    await SmsGateway.setRuleEnabled(id, enabled);
    set(s => ({ rules: s.rules.map(r => (r.id === id ? { ...r, enabled } : r)) }));
  },
  remove: async id => {
    await SmsGateway.deleteRule(id);
    set(s => ({ rules: s.rules.filter(r => r.id !== id) }));
  },
}));

interface WebhooksState {
  webhooks: Webhook[];
  loading: boolean;
  load: () => Promise<void>;
  remove: (id: number) => Promise<void>;
}

export const useWebhooksStore = create<WebhooksState>(set => ({
  webhooks: [],
  loading: false,
  load: async () => {
    set({ loading: true });
    try {
      set({ webhooks: await SmsGateway.getWebhooks() });
    } finally {
      set({ loading: false });
    }
  },
  remove: async id => {
    await SmsGateway.deleteWebhook(id);
    set(s => ({ webhooks: s.webhooks.filter(w => w.id !== id) }));
  },
}));

interface QueueState {
  stats: QueueStats;
  load: () => Promise<void>;
}

export const useQueueStore = create<QueueState>(set => ({
  stats: { total: 0 },
  load: async () => set({ stats: await SmsGateway.getQueueStats() }),
}));

interface SettingsState {
  settings: Record<string, string>;
  load: () => Promise<void>;
  set: (key: string, value: string | null) => Promise<void>;
}

export const useSettingsStore = create<SettingsState>(set => ({
  settings: {},
  load: async () => set({ settings: await SmsGateway.getAllSettings() }),
  set: async (key, value) => {
    await SmsGateway.setSetting(key, value);
    set(s => ({ settings: { ...s.settings, [key]: value ?? '' } }));
  },
}));
