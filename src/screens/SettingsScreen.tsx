import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import React, { useCallback, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { SwitchRow, TextField } from '../components/Field';
import { Button, Card, Screen, ScreenHeader, SectionLabel } from '../components/ui';
import { SmsGateway } from '../native/SmsGateway';
import type { RootStackParamList } from '../navigation/types';
import { palette, radius, spacing, typography } from '../theme/theme';

type Nav = NativeStackNavigationProp<RootStackParamList>;

const LINKS: { label: string; target: keyof RootStackParamList; hint: string }[] = [
  { label: 'Station setup', target: 'StationSetup', hint: 'Name, SIMs, services & changes' },
  { label: 'Webhooks', target: 'WebhookSettings', hint: 'Destinations, auth & HMAC' },
  { label: 'Processors', target: 'ProcessorManagement', hint: 'Pluggable transformers' },
  { label: 'Diagnostics', target: 'Diagnostics', hint: 'Health & event log' },
  { label: 'About', target: 'About', hint: 'Device & version' },
];

const SettingsScreen: React.FC = () => {
  const nav = useNavigation<Nav>();
  const [persistent, setPersistent] = useState(false);
  const [insecure, setInsecure] = useState(false);
  const [maxRetries, setMaxRetries] = useState('10');
  const [deviceOverride, setDeviceOverride] = useState('');

  const load = useCallback(() => {
    SmsGateway.getAllSettings().then(s => {
      setPersistent(s.foreground_enabled === 'true');
      setInsecure(s.allow_insecure === 'true');
      setMaxRetries(s.max_retries ?? '10');
      setDeviceOverride(s.device_id_override ?? '');
    });
  }, []);

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load]),
  );

  const togglePersistent = async (v: boolean) => {
    setPersistent(v);
    if (v) await SmsGateway.startForegroundService();
    else await SmsGateway.stopForegroundService();
  };

  const toggleInsecure = async (v: boolean) => {
    setInsecure(v);
    await SmsGateway.setSetting('allow_insecure', v ? 'true' : 'false');
  };

  const saveOps = async () => {
    await SmsGateway.setSetting('max_retries', String(parseInt(maxRetries, 10) || 10));
    await SmsGateway.setSetting('device_id_override', deviceOverride.trim());
  };

  return (
    <Screen>
      <ScreenHeader title="Settings" subtitle="Configuration & reliability" />

      <SectionLabel>Reliability</SectionLabel>
      <Card>
        <SwitchRow
          label="Persistent mode"
          description="Runs a foreground service to maximise capture on aggressive battery managers."
          value={persistent}
          onValueChange={togglePersistent}
        />
      </Card>

      <SectionLabel>Security</SectionLabel>
      <Card>
        <SwitchRow
          label="Allow insecure (HTTP) webhooks"
          description="Off by default. HTTPS is strongly recommended for all destinations."
          value={insecure}
          onValueChange={toggleInsecure}
        />
      </Card>

      <SectionLabel>Delivery</SectionLabel>
      <TextField label="Max retries before dead-letter" value={maxRetries} onChangeText={setMaxRetries} keyboardType="numeric" />
      <TextField
        label="Device ID override"
        value={deviceOverride}
        onChangeText={setDeviceOverride}
        placeholder="Leave blank to use hardware ID"
      />
      <Button title="Save" onPress={saveOps} />

      <SectionLabel>More</SectionLabel>
      {LINKS.map(link => (
        <Pressable key={link.target} style={styles.link} onPress={() => nav.navigate(link.target as any)}>
          <View style={{ flex: 1 }}>
            <Text style={styles.linkLabel}>{link.label}</Text>
            <Text style={styles.linkHint}>{link.hint}</Text>
          </View>
          <Text style={styles.chevron}>›</Text>
        </Pressable>
      ))}
    </Screen>
  );
};

const styles = StyleSheet.create({
  link: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: palette.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: palette.border,
    padding: spacing.md,
    marginBottom: spacing.sm,
  },
  linkLabel: { ...typography.heading, color: palette.text },
  linkHint: { ...typography.body, color: palette.textFaint, fontSize: 12, marginTop: 2 },
  chevron: { color: palette.textMuted, fontSize: 28, fontWeight: '300' },
});

export default SettingsScreen;
