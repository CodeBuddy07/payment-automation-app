import { useFocusEffect } from '@react-navigation/native';
import React, { useCallback, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Badge, Button, Card, KeyValue, Screen, ScreenHeader, SectionLabel } from '../components/ui';
import { SmsGateway } from '../native/SmsGateway';
import type { Diagnostics, DiagnosticLogEntry } from '../native/types';
import { palette, spacing, statusColor, typography } from '../theme/theme';
import { formatTime, relativeTime } from '../utils/format';
import { requestCorePermissions } from '../utils/permissions';

const Check: React.FC<{ label: string; ok: boolean }> = ({ label, ok }) => (
  <View style={styles.checkRow}>
    <Text style={styles.checkLabel}>{label}</Text>
    <Badge label={ok ? 'GRANTED' : 'MISSING'} color={ok ? palette.accent : palette.danger} />
  </View>
);

const DiagnosticsScreen: React.FC = () => {
  const [diag, setDiag] = useState<Diagnostics | null>(null);
  const [log, setLog] = useState<DiagnosticLogEntry[]>([]);

  const load = useCallback(() => {
    SmsGateway.getDiagnostics().then(setDiag).catch(() => {});
    SmsGateway.getDiagnosticsLog(80).then(setLog).catch(() => {});
  }, []);

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load]),
  );

  const grant = async () => {
    await requestCorePermissions();
    load();
  };

  const p = diag?.permissions;
  const lastSms = diag?.lastReceivedSms as any;
  const lastOk = diag?.lastWebhookSuccess as any;
  const lastFail = diag?.lastWebhookFailure as any;

  return (
    <Screen>
      <ScreenHeader title="Diagnostics" subtitle="System health" />

      <SectionLabel>Permissions</SectionLabel>
      <Card>
        <Check label="Receive SMS" ok={!!p?.receiveSms} />
        <Check label="Read SMS" ok={!!p?.readSms} />
        <Check label="Phone state" ok={!!p?.readPhoneState} />
        <Check label="Notifications" ok={!!p?.postNotifications} />
        <Button title="Request permissions" variant="secondary" onPress={grant} style={{ marginTop: spacing.sm }} />
      </Card>

      <SectionLabel>Reliability</SectionLabel>
      <Card>
        <KeyValue label="Battery optimization ignored" value={diag?.batteryOptimizationIgnored ? 'Yes' : 'No'} />
        <KeyValue label="Queue size" value={diag?.queue?.total ?? 0} />
        <KeyValue label="Pending" value={diag?.queue?.pending ?? 0} />
        <KeyValue label="Dead-lettered" value={diag?.queue?.dead ?? 0} />
        <KeyValue label="Messages stored" value={diag?.messageCount ?? 0} />
        <KeyValue label="Last sync" value={diag?.lastSync ? relativeTime(Number(diag.lastSync)) : '—'} />
        <KeyValue label="Processor failures" value={diag?.processorFailures ?? 0} />
        <KeyValue label="Webhook failures" value={diag?.webhookFailures ?? 0} />
      </Card>
      {!diag?.batteryOptimizationIgnored && (
        <Button
          title="Disable battery optimization"
          variant="secondary"
          onPress={() => SmsGateway.requestIgnoreBatteryOptimizations()}
        />
      )}

      <SectionLabel>Activity</SectionLabel>
      <Card>
        <KeyValue label="Last received SMS" value={lastSms ? `${lastSms.sender} · ${relativeTime(Number(lastSms.timestamp))}` : '—'} />
        <KeyValue label="Last webhook success" value={lastOk ? relativeTime(Number(lastOk.timestamp)) : '—'} />
        <KeyValue label="Last webhook failure" value={lastFail ? relativeTime(Number(lastFail.timestamp)) : '—'} />
      </Card>

      <SectionLabel>Event log</SectionLabel>
      {log.map(entry => (
        <View key={entry.id} style={styles.logRow}>
          <View style={[styles.logDot, { backgroundColor: levelColor(entry.level) }]} />
          <View style={{ flex: 1 }}>
            <Text style={styles.logMsg}>{entry.message}</Text>
            <Text style={styles.logMeta}>
              {entry.type} · {formatTime(entry.timestamp)}
            </Text>
          </View>
        </View>
      ))}
    </Screen>
  );
};

function levelColor(level: string): string {
  if (level === 'error') return palette.danger;
  if (level === 'warn') return palette.warn;
  return statusColor('sent');
}

const styles = StyleSheet.create({
  checkRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: spacing.sm },
  checkLabel: { ...typography.body, color: palette.text },
  logRow: { flexDirection: 'row', gap: spacing.md, paddingVertical: spacing.sm, alignItems: 'flex-start' },
  logDot: { width: 8, height: 8, borderRadius: 4, marginTop: 5 },
  logMsg: { ...typography.body, color: palette.textMuted, fontSize: 13 },
  logMeta: { ...typography.body, color: palette.textFaint, fontSize: 11, marginTop: 2 },
});

export default DiagnosticsScreen;
