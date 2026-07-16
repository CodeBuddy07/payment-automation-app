// Domain types for the SMS Gateway Agent UI. These mirror the native SQLite rows after the
// native bridge maps snake_case columns to camelCase.

export type ProcessorType = 'raw' | 'template' | 'regex' | 'json' | 'javascript' | string;
export type SenderMatchType = 'any' | 'exact' | 'contains' | 'regex';
export type WebhookStatus = 'none' | 'queued' | 'sent' | 'failed' | 'dead' | 'no_webhook';
export type QueueStatus = 'pending' | 'in_progress' | 'sent' | 'failed' | 'dead';

export interface SmsRecord {
  id: number;
  sender: string;
  body: string;
  timestamp: number;
  simSlot: number;
  subscriptionId: number;
  phoneNumber: string | null;
  matchedRuleId: number | null;
  processorType: string | null;
  parsedData: Record<string, unknown> | null;
  webhookStatus: WebhookStatus;
  retryCount: number;
  createdAt: number;
}

/** A raw message read straight from the device SMS inbox (content://sms), for template training. */
export interface InboxMessage {
  id: number;
  sender: string;
  body: string;
  timestamp: number;
}

export interface Rule {
  id: number;
  name: string;
  senderPattern: string;
  senderMatchType: SenderMatchType;
  processorType: ProcessorType;
  template: string | null;
  regex: string | null;
  config: string | null;
  webhookId: number | null;
  payloadTemplate: string | null;
  enabled: boolean;
  priority: number;
  sampleMessages: string | null;
  matchCount: number;
}

export interface Webhook {
  id: number;
  name: string;
  url: string;
  headers: string | null;
  enabled: boolean;
  isDefault: boolean;
  hasBearer: boolean;
  hasHmac: boolean;
}

export interface WebhookInput {
  id?: number;
  name: string;
  url: string;
  headers?: string | null;
  enabled?: boolean;
  isDefault?: boolean;
  bearerToken?: string;
  hmacSecret?: string;
}

export interface QueueRecord {
  id: number;
  messageId: number | null;
  webhookId: number | null;
  webhookUrl: string;
  payload: string;
  idempotencyKey: string;
  status: QueueStatus;
  retryCount: number;
  maxRetries: number;
  nextRetryAt: number;
  lastError: string | null;
  lastStatusCode: number;
  createdAt: number;
  updatedAt: number;
}

export interface QueueStats {
  total: number;
  pending?: number;
  in_progress?: number;
  sent?: number;
  failed?: number;
  dead?: number;
}

/** Why {@link SimInfo.phoneNumber} has (or lacks) a value — see SimManager.kt. */
export type PhoneNumberSource =
  | 'sim'
  | 'manual'
  | 'unavailable_carrier'
  | 'unavailable_permission';

export interface SimInfo {
  slot: number;
  subscriptionId: number;
  carrier: string | null;
  iccid: string | null;
  phoneNumber: string | null;
  phoneNumberSource: PhoneNumberSource;
  displayName: string | null;
  countryIso: string | null;
}

export interface SimEvent {
  id: number;
  event: string;
  slot: number;
  subscriptionId: number;
  carrier: string | null;
  iccid: string | null;
  phoneNumber: string | null;
  timestamp: number;
}

/** A detected SIM merged with its saved capture config (record flag + assigned services). */
export interface SimConfig {
  slot: number;
  subscriptionId: number;
  carrier: string | null;
  iccid: string | null;
  phoneNumber: string | null;
  phoneNumberSource: PhoneNumberSource;
  displayName: string | null;
  recordEnabled: boolean;
  services: string[];
  detected: boolean;
}

/** Payload sent to persist one slot's config. */
export interface SimConfigInput {
  slot: number;
  subscriptionId?: number;
  carrier?: string | null;
  iccid?: string | null;
  phoneNumber?: string | null;
  recordEnabled: boolean;
  services: string[];
}

/** An entry in the dynamic service catalog (bkash, nagad, …). */
export interface ServiceTag {
  id: number;
  name: string;
  enabled: boolean;
}

/** This device's station identity + last server sync. */
export interface StationProfile {
  deviceId: string;
  installationId: string;
  model: string;
  manufacturer: string;
  androidVersion: string;
  sdkInt: number;
  appVersion: string;
  name: string;
  syncedAt: number | null;
}

export interface DeviceInfo {
  deviceId: string;
  installationId: string;
  model: string;
  manufacturer: string;
  androidVersion: string;
  sdkInt: number;
  appVersion: string;
}

export interface ProcessorDescriptor {
  type: string;
  displayName: string;
}

export interface Diagnostics {
  permissions: {
    receiveSms: boolean;
    readSms: boolean;
    readPhoneState: boolean;
    postNotifications: boolean;
  };
  batteryOptimizationIgnored: boolean;
  queue: QueueStats;
  messageCount: number;
  lastReceivedSms: Record<string, unknown> | null;
  lastWebhookSuccess: Record<string, unknown> | null;
  lastWebhookFailure: Record<string, unknown> | null;
  lastSync: string | null;
  processorFailures: number;
  webhookFailures: number;
  device: DeviceInfo;
}

export interface DiagnosticLogEntry {
  id: number;
  type: string;
  level: string;
  message: string;
  timestamp: number;
}

export interface SetupStatus {
  receiveSms: boolean;
  readSms: boolean;
  readPhoneState: boolean;
  postNotifications: boolean;
  notificationsEnabled: boolean;
  batteryOptimizationIgnored: boolean;
  backgroundRestricted: boolean;
  manufacturer: string;
  onboardingDone: boolean;
}

export interface RuleTestResult {
  matched: boolean;
  reason?: string;
  data?: Record<string, unknown>;
  errors?: string[];
}

export interface TrainingResult {
  template: string;
  regex: string;
  placeholders: string[];
  sampleMessages: string[];
}

export interface SendTestResult {
  success: boolean;
  statusCode: number;
  error: string | null;
}
