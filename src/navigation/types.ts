export type RootStackParamList = {
  Splash: undefined;
  Onboarding: undefined;
  Main: undefined;
  CreateRule: { ruleId?: number };
  RuleTesting: { ruleId: number };
  Training: undefined;
  WebhookSettings: undefined;
  WebhookEdit: { webhookId?: number };
  ProcessorManagement: undefined;
  StationSetup: undefined;
  Diagnostics: undefined;
  Settings: undefined;
  About: undefined;
};

export type MainTabsParamList = {
  Dashboard: undefined;
  History: undefined;
  Rules: undefined;
  Queue: undefined;
  More: undefined;
};
