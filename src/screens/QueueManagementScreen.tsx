import { useFocusEffect } from '@react-navigation/native';
import React, { useCallback, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Segmented } from '../components/Field';
import { Badge, Button, Card, EmptyState, Row, Screen, ScreenHeader, SectionLabel, StatTile } from '../components/ui';
import { SmsGateway } from '../native/SmsGateway';
import type { QueueRecord, QueueStats } from '../native/types';
import { palette, radius, spacing, statusColor, typography } from '../theme/theme';
import { formatTime, relativeTime, truncate } from '../utils/format';

const FILTERS = [
  { label: 'All', value: 'all' },
  { label: 'Pending', value: 'pending' },
  { label: 'Failed', value: 'failed' },
  { label: 'Sent', value: 'sent' },
  { label: 'Dead', value: 'dead' },
];

const QueueManagementScreen: React.FC = () => {
  const [stats, setStats] = useState<QueueStats>({ total: 0 });
  const [items, setItems] = useState<QueueRecord[]>([]);
  const [filter, setFilter] = useState('all');
  const [expanded, setExpanded] = useState<number | null>(null);

  const load = useCallback(() => {
    SmsGateway.getQueueStats().then(setStats).catch(() => {});
    SmsGateway.getQueue(filter).then(setItems).catch(() => {});
  }, [filter]);

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load]),
  );

  const processNow = async () => {
    await SmsGateway.processQueueNow();
    setTimeout(load, 800);
  };

  const retryAll = async () => {
    await SmsGateway.retryAllDead();
    setTimeout(load, 500);
  };

  return (
    <Screen>
      <ScreenHeader title="Queue" subtitle={`${stats.total} total`} />

      <Row style={{ marginBottom: spacing.md }}>
        <StatTile label="Pending" value={stats.pending ?? 0} color={palette.info} />
        <StatTile label="Sent" value={stats.sent ?? 0} color={palette.accent} />
      </Row>
      <Row style={{ marginBottom: spacing.md }}>
        <StatTile label="Failed" value={stats.failed ?? 0} color={palette.warn} />
        <StatTile label="Dead" value={stats.dead ?? 0} color={palette.danger} />
      </Row>

      <Row style={{ marginBottom: spacing.md }}>
        <Button title="Process now" onPress={processNow} style={{ flex: 1 }} />
        <Button title="Retry dead" variant="secondary" onPress={retryAll} style={{ flex: 1 }} />
      </Row>

      <Segmented options={FILTERS} value={filter} onChange={setFilter} />

      {items.length === 0 && <EmptyState title="Queue empty" subtitle="Delivered payloads will not appear here." />}

      {items.map(item => {
        const open = expanded === item.id;
        return (
          <Card key={item.id} onPress={() => setExpanded(open ? null : item.id)}>
            <View style={styles.head}>
              <Badge label={item.status.toUpperCase()} color={statusColor(item.status)} />
              <Text style={styles.time}>{formatTime(item.createdAt)}</Text>
            </View>
            <Text style={styles.url} numberOfLines={1}>
              {item.webhookUrl}
            </Text>
            <View style={styles.metaRow}>
              <Text style={styles.meta}>tries {item.retryCount}/{item.maxRetries}</Text>
              {item.lastStatusCode > 0 && <Text style={styles.meta}>· HTTP {item.lastStatusCode}</Text>}
              {item.nextRetryAt > Date.now() && (
                <Text style={styles.meta}>· next {relativeTime(item.nextRetryAt)}</Text>
              )}
            </View>
            {item.lastError && <Text style={styles.err}>{truncate(item.lastError, 120)}</Text>}
            {open && (
              <>
                <SectionLabel>Payload</SectionLabel>
                <Text style={styles.payload}>{item.payload}</Text>
                <Row style={{ marginTop: spacing.sm }}>
                  <Pressable onPress={() => SmsGateway.retryQueueItem(item.id).then(() => setTimeout(load, 500))}>
                    <Text style={styles.action}>Retry now</Text>
                  </Pressable>
                  <Pressable onPress={() => SmsGateway.deleteQueueItem(item.id).then(load)}>
                    <Text style={[styles.action, { color: palette.danger }]}>Delete</Text>
                  </Pressable>
                </Row>
              </>
            )}
          </Card>
        );
      })}
    </Screen>
  );
};

const styles = StyleSheet.create({
  head: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: spacing.sm },
  time: { ...typography.body, color: palette.textFaint, fontSize: 11 },
  url: { ...typography.body, color: palette.text, fontSize: 13 },
  metaRow: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.sm },
  meta: { ...typography.body, color: palette.textFaint, fontSize: 11 },
  err: { ...typography.body, color: palette.warn, marginTop: spacing.sm, fontSize: 12 },
  payload: {
    ...typography.mono,
    color: palette.accent,
    backgroundColor: palette.bg,
    borderRadius: radius.sm,
    padding: spacing.md,
    borderWidth: 1,
    borderColor: palette.border,
  },
  action: { ...typography.label, color: palette.accent, paddingVertical: spacing.xs, paddingRight: spacing.lg },
});

export default QueueManagementScreen;
