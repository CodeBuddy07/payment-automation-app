import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import React, { useEffect } from 'react';
import { ActivityIndicator, Image, StyleSheet, Text, View } from 'react-native';
import { SmsGateway } from '../native/SmsGateway';
import type { RootStackParamList } from '../navigation/types';
import { palette, radius, spacing, typography } from '../theme/theme';
import { requestCorePermissions } from '../utils/permissions';

const SplashScreen: React.FC = () => {
  const nav = useNavigation<NativeStackNavigationProp<RootStackParamList>>();

  useEffect(() => {
    let active = true;
    (async () => {
      let destination: 'Main' | 'Onboarding' = 'Onboarding';
      try {
        const status = await SmsGateway.getSetupStatus();
        const critical = status.receiveSms && status.readSms;
        // Skip onboarding only once setup is complete and the user has finished it before.
        destination = critical && status.onboardingDone ? 'Main' : 'Onboarding';
        SmsGateway.detectSimChanges().catch(() => {});
      } catch {
        // Native not ready / non-Android: fall back to requesting permissions and entering the app.
        await requestCorePermissions().catch(() => {});
        destination = 'Main';
      }
      setTimeout(() => active && nav.replace(destination), 600);
    })();
    return () => {
      active = false;
    };
  }, [nav]);

  return (
    <View style={styles.container}>
      <Image source={require('../../assets/brand/logo.png')} style={styles.logo} resizeMode="contain" />
      <Text style={styles.title}>SMS Gateway Agent</Text>
      <Text style={styles.subtitle}>Observe · Process · Forward</Text>
      <ActivityIndicator color={palette.accent} style={{ marginTop: spacing.xl }} />
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: palette.bg, alignItems: 'center', justifyContent: 'center' },
  logo: {
    width: 116,
    height: 116,
    borderRadius: radius.lg,
    marginBottom: spacing.xl,
  },
  title: { ...typography.title, color: palette.text },
  subtitle: { ...typography.body, color: palette.textMuted, marginTop: 6, letterSpacing: 1 },
});

export default SplashScreen;
