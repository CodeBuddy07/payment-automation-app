import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { Badge, Button, Screen, ScreenHeader } from '../components/ui';
import { SmsGateway } from '../native/SmsGateway';
import type { SetupStatus } from '../native/types';
import type { RootStackParamList } from '../navigation/types';
import { palette, radius, spacing, typography } from '../theme/theme';
import { requestCorePermissions } from '../utils/permissions';

type Nav = NativeStackNavigationProp<RootStackParamList>;

const OEM_HINTS = ['xiaomi', 'redmi', 'poco', 'oppo', 'vivo', 'iqoo', 'huawei', 'honor', 'realme', 'oneplus', 'samsung'];

interface Item {
  key: string;
  title: string;
  desc: string;
  done: boolean;
  required?: boolean;
  cta: string;
  action: () => Promise<void>;
}

const OnboardingScreen: React.FC = () => {
  const nav = useNavigation<Nav>();
  const [status, setStatus] = useState<SetupStatus | null>(null);
  const [acks, setAcks] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setStatus(await SmsGateway.getSetupStatus());
    } catch {
      /* native not ready */
    }
  }, []);

  // Re-check on focus and whenever the user returns from a system settings page.
  useFocusEffect(
    useCallback(() => {
      refresh();
    }, [refresh]),
  );
  useEffect(() => {
    const sub = AppState.addEventListener('change', s => {
      if (s === 'active') refresh();
    });
    return () => sub.remove();
  }, [refresh]);

  const run = async (key: string, fn: () => Promise<void>) => {
    setBusy(key);
    try {
      await fn();
    } finally {
      setBusy(null);
      setTimeout(refresh, 400);
    }
  };

  const ack = (key: string) => setAcks(prev => new Set(prev).add(key));

  // Guard so a double-tap (or an auto-advance racing a manual tap) can't fire navigation twice.
  const finishing = useRef(false);
  const finish = useCallback(async () => {
    if (finishing.current) return;
    finishing.current = true;
    try {
      await SmsGateway.setSetting('onboarding_done', 'true');
      // Turn on persistent mode by default. The agent's whole job is to never miss a payment SMS,
      // so it should stay resident from the first launch rather than waiting for an opt-in.
      await SmsGateway.startForegroundService().catch(() => {});
    } catch {
      // Persisting the completion flag must never trap the user on the wizard. If the write
      // fails the Splash screen will simply show onboarding again next launch — acceptable.
    } finally {
      nav.replace('Main');
    }
  }, [nav]);

  const s = status;
  const isOem = !!s && OEM_HINTS.some(h => s.manufacturer?.toLowerCase().includes(h));

  const items: Item[] = [];
  if (s) {
    items.push({
      key: 'sms',
      title: 'SMS access',
      desc: 'Required to observe incoming messages and forward them. Grants RECEIVE_SMS & READ_SMS.',
      done: s.receiveSms && s.readSms,
      required: true,
      cta: 'Grant',
      action: async () => {
        const r = await requestCorePermissions();
        if (!(r.receiveSms && r.readSms)) await SmsGateway.openAppSettings();
      },
    });
    items.push({
      key: 'phone',
      title: 'Phone & SIM info',
      desc: 'Lets the agent read SIM slot, carrier and subscription for each message.',
      done: s.readPhoneState,
      cta: 'Grant',
      action: async () => {
        const r = await requestCorePermissions();
        if (!r.readPhoneState) await SmsGateway.openAppSettings();
      },
    });
    items.push({
      key: 'notif',
      title: 'Notifications',
      desc: 'Needed for the persistent foreground service and delivery alerts.',
      done: s.notificationsEnabled && s.postNotifications,
      cta: 'Enable',
      action: async () => {
        await requestCorePermissions();
        await SmsGateway.openNotificationSettings();
      },
    });
    items.push({
      key: 'battery',
      title: 'Ignore battery optimization',
      desc: 'Stops Android from delaying or killing background delivery. Strongly recommended.',
      done: s.batteryOptimizationIgnored,
      cta: 'Allow',
      action: async () => {
        await SmsGateway.requestIgnoreBatteryOptimizations();
      },
    });
    if (s.backgroundRestricted) {
      items.push({
        key: 'bg',
        title: 'Remove background restriction',
        desc: 'Background activity is restricted for this app. Open settings and set it to "Allowed".',
        done: false,
        cta: 'Open settings',
        action: async () => {
          await SmsGateway.openAppSettings();
        },
      });
    }
    if (isOem) {
      items.push({
        key: 'autostart',
        title: 'Allow auto-start',
        desc: `${s.manufacturer} restricts auto-start. Enable it so the agent restarts after reboot and runs in the background.`,
        done: acks.has('autostart'),
        cta: 'Open',
        action: async () => {
          ack('autostart');
          await SmsGateway.openAutoStartSettings();
        },
      });
    }
    items.push({
      key: 'persistent',
      title: 'Persistent mode (optional)',
      desc: 'Runs a foreground service so the agent stays resident on aggressive devices.',
      done: acks.has('persistent'),
      cta: 'Enable',
      action: async () => {
        ack('persistent');
        await SmsGateway.startForegroundService();
      },
    });
  }

  const criticalDone = !!s && s.receiveSms && s.readSms;
  const allDone = items.length > 0 && items.every(i => i.done);

  return (
    <Screen>
      <Image source={require('../../assets/brand/logo.png')} style={styles.brand} resizeMode="contain" />
      <ScreenHeader
        title="Let's set up"
        subtitle="Grant access so the agent runs reliably"
        right={<Badge label={allDone ? 'READY' : 'SETUP'} color={allDone ? palette.accent : palette.warn} />}
      />

      {items.map(item => (
        <View key={item.key} style={[styles.row, item.done && styles.rowDone]}>
          <View style={styles.rowHead}>
            <View style={[styles.statusDot, { backgroundColor: item.done ? palette.accent : item.required ? palette.danger : palette.warn }]} />
            <Text style={styles.title}>{item.title}</Text>
            {item.required && !item.done && <Text style={styles.req}>REQUIRED</Text>}
            {item.done && <Text style={styles.doneText}>DONE</Text>}
          </View>
          <Text style={styles.desc}>{item.desc}</Text>
          {!item.done && (
            <Pressable
              style={({ pressed }) => [styles.cta, pressed && { opacity: 0.85 }]}
              onPress={() => run(item.key, item.action)}>
              <Text style={styles.ctaText}>{busy === item.key ? 'Opening…' : item.cta}</Text>
            </Pressable>
          )}
        </View>
      ))}

      <Button
        title={criticalDone ? 'Continue' : 'Continue anyway'}
        onPress={finish}
        variant={criticalDone ? 'primary' : 'secondary'}
        style={{ marginTop: spacing.lg }}
      />
      <Text style={styles.footer}>
        You can revisit permissions anytime in Settings → Diagnostics. Configure your SIMs & services
        later from Settings → Station setup.
      </Text>
    </Screen>
  );
};

const styles = StyleSheet.create({
  brand: {
    width: 60,
    height: 60,
    borderRadius: 14,
    alignSelf: 'center',
    marginTop: spacing.md,
    marginBottom: spacing.xs,
  },
  row: {
    backgroundColor: palette.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: palette.border,
    padding: spacing.md,
    marginBottom: spacing.sm,
  },
  rowDone: { borderColor: palette.accentDim, backgroundColor: palette.accentSoft },
  rowHead: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  statusDot: { width: 9, height: 9, borderRadius: 5 },
  title: { ...typography.heading, color: palette.text, flex: 1 },
  req: { ...typography.label, color: palette.danger, fontSize: 10 },
  doneText: { ...typography.label, color: palette.accent, fontSize: 10 },
  desc: { ...typography.body, color: palette.textMuted, marginTop: 6, marginBottom: spacing.md, fontSize: 13 },
  cta: {
    alignSelf: 'flex-start',
    backgroundColor: palette.surfaceHigh,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: palette.border,
    paddingHorizontal: spacing.lg,
    paddingVertical: 9,
  },
  ctaText: { ...typography.label, color: palette.accent },
  footer: { ...typography.body, color: palette.textFaint, textAlign: 'center', marginTop: spacing.md, fontSize: 12 },
});

export default OnboardingScreen;
