import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import React, { useCallback, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Badge, Button, Card, Row, ScreenHeader, SectionLabel, StatTile } from '../components/ui';
import { SmsGateway } from '../native/SmsGateway';
import type { Diagnostics } from '../native/types';
import type { RootStackParamList } from '../navigation/types';
import { Screen } from '../components/ui';
import { palette, radius, spacing, typography } from '../theme/theme';
import { relativeTime, truncate } from '../utils/format';

type Nav = NativeStackNavigationProp<RootStackParamList>;

const QUICK_LINKS: { label: string; target: keyof RootStackParamList }[] = [
  { label: 'Webhooks', target: 'WebhookSettings' },
  { label: 'Processors', target: 'ProcessorManagement' },
  { label: 'Station setup', target: 'StationSetup' },
  { label: 'Diagnostics', target: 'Diagnostics' },
  { label: 'Settings', target: 'Settings' },
  { label: 'About', target: 'About' },
];

const DashboardScreen: React.FC = () => {
  const nav = useNavigation<Nav>();
  const [diag, setDiag] = useState<Diagnostics | null>(null);

  const load = useCallback(() => {
    SmsGateway.getDiagnostics().then(setDiag).catch(() => {});
  }, []);

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load]),
  );

  const perms = diag?.permissions;
  const permsOk = perms ? perms.receiveSms && perms.readSms : false;
  const lastSms = diag?.lastReceivedSms as any;

  return (
    <Screen>
      <ScreenHeader
        title="Agent"
        subtitle={diag?.device ? `${diag.device.manufacturer} ${diag.device.model}` : 'SMS Gateway'}
        right={<Badge label={permsOk ? 'ACTIVE' : 'SETUP'} color={permsOk ? palette.accent : palette.warn} />}
      />

      {!permsOk && (
        <Card style={{ borderColor: palette.warn }}>
          <Text style={styles.warnTitle}>Permissions required</Text>
          <Text style={styles.warnBody}>
            SMS observation needs RECEIVE_SMS and READ_SMS. Grant them to start forwarding.
          </Text>
          <Button title="Open Diagnostics" variant="secondary" onPress={() => nav.navigate('Diagnostics')} />
        </Card>
      )}

      <Row style={{ marginBottom: spacing.md }}>
        <StatTile label="Messages" value={diag?.messageCount ?? 0} />
        <StatTile label="Queued" value={diag?.queue?.pending ?? 0} color={palette.info} />
      </Row>
      <Row style={{ marginBottom: spacing.md }}>
        <StatTile label="Sent" value={diag?.queue?.sent ?? 0} color={palette.accent} />
        <StatTile label="Dead" value={diag?.queue?.dead ?? 0} color={palette.danger} />
      </Row>

      <SectionLabel>Last received</SectionLabel>
      <Card>
        {lastSms ? (
          <>
            <Row style={{ justifyContent: 'space-between' }}>
              <Text style={styles.sender}>{lastSms.sender}</Text>
              <Text style={styles.time}>{relativeTime(Number(lastSms.timestamp))}</Text>
            </Row>
            <Text style={styles.body}>{truncate(String(lastSms.body), 140)}</Text>
            <Badge
              label={String(lastSms.webhook_status ?? 'none').toUpperCase()}
              color={palette.textMuted}
            />
          </>
        ) : (
          <Text style={styles.body}>No messages observed yet.</Text>
        )}
      </Card>

      <SectionLabel>Manage</SectionLabel>
      <View style={styles.grid}>
        {QUICK_LINKS.map(link => (
          <Pressable
            key={link.target}
            style={({ pressed }) => [styles.tile, pressed && { opacity: 0.8 }]}
            onPress={() => nav.navigate(link.target as any)}>
            <Text style={styles.tileText}>{link.label}</Text>
          </Pressable>
        ))}
      </View>

      <Button title="Process queue now" variant="secondary" onPress={() => SmsGateway.processQueueNow()} />
    </Screen>
  );
};

const styles = StyleSheet.create({
  warnTitle: { ...typography.heading, color: palette.warn, marginBottom: 6 },
  warnBody: { ...typography.body, color: palette.textMuted, marginBottom: spacing.md },
  sender: { ...typography.heading, color: palette.text },
  time: { ...typography.body, color: palette.textFaint, fontSize: 12 },
  body: { ...typography.body, color: palette.textMuted, marginVertical: spacing.sm },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm, marginBottom: spacing.md },
  tile: {
    width: '31.5%',
    backgroundColor: palette.surfaceAlt,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: palette.border,
    paddingVertical: spacing.lg,
    alignItems: 'center',
  },
  tileText: { ...typography.label, color: palette.text, fontSize: 12 },
});

export default DashboardScreen;
