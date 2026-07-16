import { NativeEventEmitter, NativeModules } from 'react-native';
import type {
  Diagnostics,
  DiagnosticLogEntry,
  DeviceInfo,
  InboxMessage,
  ProcessorDescriptor,
  QueueRecord,
  QueueStats,
  Rule,
  RuleTestResult,
  SendTestResult,
  ServiceTag,
  SetupStatus,
  SimConfig,
  SimConfigInput,
  SimEvent,
  SimInfo,
  SmsRecord,
  StationProfile,
  TrainingResult,
  Webhook,
  WebhookInput,
} from './types';

const Native = NativeModules.SmsGateway;

if (!Native) {
  // Helps during development if the native module failed to link.
  // eslint-disable-next-line no-console
  console.warn('SmsGateway native module is not available. Run a native rebuild.');
}

const parse = <T>(value: string | null | undefined, fallback: T): T =>
  value ? (JSON.parse(value) as T) : fallback;

// ---- snake_case (SQLite) → camelCase (UI) mappers -------------------------------
const num = (v: unknown): number => (typeof v === 'number' ? v : Number(v ?? 0));
const bool = (v: unknown): boolean => v === 1 || v === true || v === '1';

function mapMessage(r: any): SmsRecord {
  return {
    id: num(r.id),
    sender: r.sender ?? '',
    body: r.body ?? '',
    timestamp: num(r.timestamp),
    simSlot: num(r.sim_slot),
    subscriptionId: num(r.subscription_id),
    phoneNumber: r.phone_number ?? null,
    matchedRuleId: r.matched_rule_id != null ? num(r.matched_rule_id) : null,
    processorType: r.processor_type ?? null,
    parsedData: r.parsed_data ? safeParse(r.parsed_data) : null,
    webhookStatus: r.webhook_status ?? 'none',
    retryCount: num(r.retry_count),
    createdAt: num(r.created_at),
  };
}

function mapRule(r: any): Rule {
  return {
    id: num(r.id),
    name: r.name ?? '',
    senderPattern: r.sender_pattern ?? '',
    senderMatchType: r.sender_match_type ?? 'contains',
    processorType: r.processor_type ?? 'raw',
    template: r.template ?? null,
    regex: r.regex ?? null,
    config: r.config ?? null,
    webhookId: r.webhook_id != null ? num(r.webhook_id) : null,
    payloadTemplate: r.payload_template ?? null,
    enabled: bool(r.enabled),
    priority: num(r.priority),
    sampleMessages: r.sample_messages ?? null,
    matchCount: num(r.match_count),
  };
}

function mapWebhook(r: any): Webhook {
  return {
    id: num(r.id),
    name: r.name ?? '',
    url: r.url ?? '',
    headers: r.headers ?? null,
    enabled: bool(r.enabled),
    isDefault: bool(r.is_default),
    hasBearer: bool(r.hasBearer),
    hasHmac: bool(r.hasHmac),
  };
}

function mapQueue(r: any): QueueRecord {
  return {
    id: num(r.id),
    messageId: r.message_id != null ? num(r.message_id) : null,
    webhookId: r.webhook_id != null ? num(r.webhook_id) : null,
    webhookUrl: r.webhook_url ?? '',
    payload: r.payload ?? '',
    idempotencyKey: r.idempotency_key ?? '',
    status: r.status ?? 'pending',
    retryCount: num(r.retry_count),
    maxRetries: num(r.max_retries),
    nextRetryAt: num(r.next_retry_at),
    lastError: r.last_error ?? null,
    lastStatusCode: num(r.last_status_code),
    createdAt: num(r.created_at),
    updatedAt: num(r.updated_at),
  };
}

function mapSimEvent(r: any): SimEvent {
  return {
    id: num(r.id),
    event: r.event ?? '',
    slot: num(r.slot),
    subscriptionId: num(r.subscription_id),
    carrier: r.carrier ?? null,
    iccid: r.iccid ?? null,
    phoneNumber: r.phone_number ?? null,
    timestamp: num(r.timestamp),
  };
}

function mapService(r: any): ServiceTag {
  return { id: num(r.id), name: r.name ?? '', enabled: bool(r.enabled) };
}

function mapInboxMessage(r: any): InboxMessage {
  return { id: num(r.id), sender: r.sender ?? '', body: r.body ?? '', timestamp: num(r.timestamp) };
}

function safeParse(s: string): Record<string, unknown> | null {
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
}

// ---- Typed API -------------------------------------------------------------------
export const SmsGateway = {
  // Device
  getDeviceInfo: async (): Promise<DeviceInfo> => parse(await Native.getDeviceInfo(), {} as DeviceInfo),

  // Messages
  getMessages: async (search = '', status = 'all', limit = 50, offset = 0): Promise<SmsRecord[]> =>
    parse<any[]>(await Native.getMessages(search, status, limit, offset), []).map(mapMessage),
  /** Real device SMS inbox (content://sms) — includes messages from before the app was installed. */
  getInboxMessages: async (limit = 200): Promise<InboxMessage[]> =>
    parse<any[]>(await Native.getInboxMessages(limit), []).map(mapInboxMessage),
  getMessageCount: (): Promise<number> => Native.getMessageCount(),
  exportMessages: async (): Promise<SmsRecord[]> =>
    parse<any[]>(await Native.exportMessages(), []).map(mapMessage),

  // Rules
  getRules: async (): Promise<Rule[]> => parse<any[]>(await Native.getRules(), []).map(mapRule),
  saveRule: (rule: Partial<Rule>): Promise<number> => Native.saveRule(JSON.stringify(rule)),
  deleteRule: (id: number): Promise<void> => Native.deleteRule(id),
  setRuleEnabled: (id: number, enabled: boolean): Promise<void> => Native.setRuleEnabled(id, enabled),

  // Webhooks
  getWebhooks: async (): Promise<Webhook[]> =>
    parse<any[]>(await Native.getWebhooks(), []).map(mapWebhook),
  saveWebhook: (webhook: WebhookInput): Promise<number> => Native.saveWebhook(JSON.stringify(webhook)),
  deleteWebhook: (id: number): Promise<void> => Native.deleteWebhook(id),
  testWebhook: async (webhookId: number, payload: string): Promise<SendTestResult> =>
    parse(await Native.testWebhook(webhookId, payload), {} as SendTestResult),

  // Queue
  getQueue: async (status = 'all'): Promise<QueueRecord[]> =>
    parse<any[]>(await Native.getQueue(status), []).map(mapQueue),
  getQueueStats: async (): Promise<QueueStats> => parse(await Native.getQueueStats(), { total: 0 }),
  retryQueueItem: (id: number): Promise<void> => Native.retryQueueItem(id),
  retryAllDead: (): Promise<void> => Native.retryAllDead(),
  deleteQueueItem: (id: number): Promise<void> => Native.deleteQueueItem(id),
  processQueueNow: (): Promise<void> => Native.processQueueNow(),

  // Settings
  getAllSettings: async (): Promise<Record<string, string>> => parse(await Native.getAllSettings(), {}),
  setSetting: (key: string, value: string | null): Promise<void> => Native.setSetting(key, value),

  // SIM
  getSims: async (): Promise<SimInfo[]> => parse(await Native.getSims(), []),
  getSimHistory: async (): Promise<SimEvent[]> =>
    parse<any[]>(await Native.getSimHistory(), []).map(mapSimEvent),
  detectSimChanges: async (): Promise<unknown[]> => parse(await Native.detectSimChanges(), []),

  // SIM config / services / station
  getSimConfigs: async (): Promise<SimConfig[]> => parse(await Native.getSimConfigs(), []),
  saveSimConfig: (cfg: SimConfigInput): Promise<boolean> => Native.saveSimConfig(JSON.stringify(cfg)),
  getServices: async (): Promise<ServiceTag[]> =>
    parse<any[]>(await Native.getServices(), []).map(mapService),
  addService: (name: string): Promise<number> => Native.addService(name),
  setServiceEnabled: (id: number, enabled: boolean): Promise<void> => Native.setServiceEnabled(id, enabled),
  deleteService: (id: number): Promise<void> => Native.deleteService(id),
  getStationProfile: async (): Promise<StationProfile> =>
    parse(await Native.getStationProfile(), {} as StationProfile),
  setStationName: (name: string): Promise<boolean> => Native.setStationName(name),
  syncStationConfig: (): Promise<boolean> => Native.syncStationConfig(),

  // Processors
  getProcessors: async (): Promise<ProcessorDescriptor[]> => parse(await Native.getProcessors(), []),

  // Training
  generateTemplate: (sample: string): Promise<string> => Native.generateTemplate(sample),
  trainTemplate: async (samples: string[]): Promise<TrainingResult> =>
    parse(await Native.trainTemplate(JSON.stringify(samples)), {} as TrainingResult),
  testRule: async (rule: Partial<Rule>, sender: string, body: string): Promise<RuleTestResult> =>
    parse(await Native.testRule(JSON.stringify(rule), sender, body), { matched: false }),
  testRuleAgainstHistory: async (
    rule: Partial<Rule>,
  ): Promise<{ totalMatches: number; samples: Array<{ body: string; data: Record<string, unknown> }> }> =>
    parse(await Native.testRuleAgainstHistory(JSON.stringify(rule)), { totalMatches: 0, samples: [] }),

  // Diagnostics
  getDiagnostics: async (): Promise<Diagnostics> => parse(await Native.getDiagnostics(), {} as Diagnostics),
  getDiagnosticsLog: async (limit = 100): Promise<DiagnosticLogEntry[]> =>
    parse(await Native.getDiagnosticsLog(limit), []),

  // Power / permissions
  isIgnoringBatteryOptimizations: (): Promise<boolean> => Native.isIgnoringBatteryOptimizations(),
  requestIgnoreBatteryOptimizations: (): Promise<boolean> => Native.requestIgnoreBatteryOptimizations(),
  openAppSettings: (): Promise<boolean> => Native.openAppSettings(),

  // Guided onboarding
  getSetupStatus: async (): Promise<SetupStatus> => parse(await Native.getSetupStatus(), {} as SetupStatus),
  areNotificationsEnabled: (): Promise<boolean> => Native.areNotificationsEnabled(),
  openNotificationSettings: (): Promise<boolean> => Native.openNotificationSettings(),
  isBackgroundRestricted: (): Promise<boolean> => Native.isBackgroundRestricted(),
  openAutoStartSettings: (): Promise<boolean> => Native.openAutoStartSettings(),

  // Foreground service (persistent mode)
  startForegroundService: (): Promise<boolean> => Native.startForegroundService(),
  stopForegroundService: (): Promise<boolean> => Native.stopForegroundService(),
};

export const SmsGatewayEvents = new NativeEventEmitter(Native);
