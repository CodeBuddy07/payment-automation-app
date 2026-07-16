/**
 * SMS Gateway Agent
 * Standalone Android agent: observe SMS → rule match → process → forward to webhooks.
 *
 * @format
 */
import React, { useEffect } from 'react';
import { AppState, StatusBar } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import RootNavigator from './src/navigation/RootNavigator';
import { SmsGateway } from './src/native/SmsGateway';
import { palette } from './src/theme/theme';

/**
 * Re-asserts persistent mode every time the app is foregrounded. Starting a foreground service is
 * always allowed while the app is visible, so this is the reliable place to make sure the resident
 * agent process is running after the OS may have killed it. Respects an explicit user opt-out
 * (`foreground_enabled === 'false'`).
 */
function useEnsurePersistentAgent() {
  useEffect(() => {
    const ensure = async () => {
      try {
        const s = await SmsGateway.getAllSettings();
        if (s.onboarding_done === 'true' && s.foreground_enabled !== 'false') {
          await SmsGateway.startForegroundService().catch(() => {});
        }
      } catch {
        /* native not ready */
      }
    };
    ensure();
    const sub = AppState.addEventListener('change', state => {
      if (state === 'active') ensure();
    });
    return () => sub.remove();
  }, []);
}

const App: React.FC = () => {
  useEnsurePersistentAgent();
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" backgroundColor={palette.bg} />
      <RootNavigator />
    </SafeAreaProvider>
  );
};

export default App;
