import { DarkTheme, NavigationContainer } from '@react-navigation/native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import React from 'react';
import { StyleSheet, Text } from 'react-native';
import { palette, typography } from '../theme/theme';
import type { MainTabsParamList, RootStackParamList } from './types';

import AboutScreen from '../screens/AboutScreen';
import CreateRuleScreen from '../screens/CreateRuleScreen';
import DashboardScreen from '../screens/DashboardScreen';
import DiagnosticsScreen from '../screens/DiagnosticsScreen';
import OnboardingScreen from '../screens/OnboardingScreen';
import ProcessorManagementScreen from '../screens/ProcessorManagementScreen';
import QueueManagementScreen from '../screens/QueueManagementScreen';
import RuleTestingScreen from '../screens/RuleTestingScreen';
import RulesScreen from '../screens/RulesScreen';
import SettingsScreen from '../screens/SettingsScreen';
import SmsHistoryScreen from '../screens/SmsHistoryScreen';
import SplashScreen from '../screens/SplashScreen';
import StationSetupScreen from '../screens/StationSetupScreen';
import TrainingScreen from '../screens/TrainingScreen';
import WebhookEditScreen from '../screens/WebhookEditScreen';
import WebhookSettingsScreen from '../screens/WebhookSettingsScreen';

const Stack = createNativeStackNavigator<RootStackParamList>();
const Tabs = createBottomTabNavigator<MainTabsParamList>();

const GLYPHS: Record<keyof MainTabsParamList, string> = {
  Dashboard: '▣',
  History: '≣',
  Rules: '⌥',
  Queue: '⇅',
  More: '⋯',
};

const TabIcon: React.FC<{ route: keyof MainTabsParamList; focused: boolean }> = ({ route, focused }) => (
  <Text style={[styles.tabIcon, { color: focused ? palette.accent : palette.textFaint }]}>{GLYPHS[route]}</Text>
);

const MainTabs: React.FC = () => (
  <Tabs.Navigator
    screenOptions={({ route }) => ({
      headerShown: false,
      tabBarStyle: styles.tabBar,
      tabBarActiveTintColor: palette.accent,
      tabBarInactiveTintColor: palette.textFaint,
      tabBarLabelStyle: styles.tabLabel,
      tabBarIcon: ({ focused }) => <TabIcon route={route.name} focused={focused} />,
    })}>
    <Tabs.Screen name="Dashboard" component={DashboardScreen} />
    <Tabs.Screen name="History" component={SmsHistoryScreen} />
    <Tabs.Screen name="Rules" component={RulesScreen} />
    <Tabs.Screen name="Queue" component={QueueManagementScreen} />
    <Tabs.Screen name="More" component={SettingsScreen} />
  </Tabs.Navigator>
);

const navTheme = {
  ...DarkTheme,
  colors: {
    ...DarkTheme.colors,
    background: palette.bg,
    card: palette.surface,
    text: palette.text,
    border: palette.border,
    primary: palette.accent,
    notification: palette.accent,
  },
};

const headerOptions = {
  headerStyle: { backgroundColor: palette.bg },
  headerTintColor: palette.text,
  headerTitleStyle: { color: palette.text },
  headerShadowVisible: false,
  contentStyle: { backgroundColor: palette.bg },
};

const RootNavigator: React.FC = () => (
  <NavigationContainer theme={navTheme}>
    <Stack.Navigator screenOptions={headerOptions}>
      <Stack.Screen name="Splash" component={SplashScreen} options={{ headerShown: false }} />
      <Stack.Screen name="Onboarding" component={OnboardingScreen} options={{ headerShown: false }} />
      <Stack.Screen name="Main" component={MainTabs} options={{ headerShown: false }} />
      <Stack.Screen name="CreateRule" component={CreateRuleScreen} options={{ title: 'Rule' }} />
      <Stack.Screen name="RuleTesting" component={RuleTestingScreen} options={{ title: 'Test Rule' }} />
      <Stack.Screen name="Training" component={TrainingScreen} options={{ title: 'Train' }} />
      <Stack.Screen name="WebhookSettings" component={WebhookSettingsScreen} options={{ title: 'Webhooks' }} />
      <Stack.Screen name="WebhookEdit" component={WebhookEditScreen} options={{ title: 'Webhook' }} />
      <Stack.Screen name="ProcessorManagement" component={ProcessorManagementScreen} options={{ title: 'Processors' }} />
      <Stack.Screen name="StationSetup" component={StationSetupScreen} options={{ title: 'Station' }} />
      <Stack.Screen name="Diagnostics" component={DiagnosticsScreen} options={{ title: 'Diagnostics' }} />
      <Stack.Screen name="Settings" component={SettingsScreen} options={{ title: 'Settings' }} />
      <Stack.Screen name="About" component={AboutScreen} options={{ title: 'About' }} />
    </Stack.Navigator>
  </NavigationContainer>
);

const styles = StyleSheet.create({
  tabBar: {
    backgroundColor: palette.surface,
    borderTopColor: palette.border,
    height: 62,
    paddingBottom: 8,
    paddingTop: 6,
  },
  tabLabel: { ...typography.label, fontSize: 10 },
  tabIcon: { fontSize: 18 },
});

export default RootNavigator;
