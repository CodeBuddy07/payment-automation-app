import { useFocusEffect } from '@react-navigation/native';
import React, { useCallback, useState } from 'react';
import { FlatList, Pressable, Share, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Badge, EmptyState, ScreenHeader } from '../components/ui';
import { Segmented } from '../components/Field';
import { SmsGateway } from '../native/SmsGateway';
import type { SmsRecord } from '../native/types';
import { palette, radius, spacing, statusColor, typography } from '../theme/theme';
import { formatTime, prettyJson, truncate } from '../utils/format';

const FILTERS = [
  { label: 'All', value: 'all' },
  { label: 'Sent', value: 'sent' },
  { label: 'Queued', value: 'queued' },
  { label: 'Failed', value: 'failed' },
  { label: 'Dead', value: 'dead' },
];

const SmsHistoryScreen: React.FC = () => {
  const [items, setItems] = useState<SmsRecord[]>([]);
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('all');
  const [expanded, setExpanded] = useState<number | null>(null);

  const load = useCallback(() => {
    SmsGateway.getMessages(search, filter, 200, 0).then(setItems).catch(() => {});
  }, [search, filter]);

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load]),
  );

  const exportJson = async () => {
    const all = await SmsGateway.exportMessages();
    await Share.share({ message: prettyJson(all), title: 'sms-history.json' });
  };

  const renderItem = ({ item }: { item: SmsRecord }) => {
    const open = expanded === item.id;
    return (
      <Pressable style={styles.row} onPress={() => setExpanded(open ? null : item.id)}>
        <View style={styles.rowTop}>
          <Text style={styles.sender}>{item.sender}</Text>
          <Text style={styles.time}>{formatTime(item.timestamp)}</Text>
        </View>
        <Text style={styles.body}>{open ? item.body : truncate(item.body, 110)}</Text>
        <View style={styles.rowBottom}>
          <Badge label={item.webhookStatus.toUpperCase()} color={statusColor(item.webhookStatus)} />
          {item.retryCount > 0 && <Text style={styles.meta}>retries: {item.retryCount}</Text>}
          {item.processorType && <Text style={styles.meta}>· {item.processorType}</Text>}
        </View>
        {open && item.parsedData && (
          <View style={styles.parsed}>
            <Text style={styles.parsedLabel}>PARSED DATA</Text>
            <Text style={styles.parsedJson}>{prettyJson(item.parsedData)}</Text>
          </View>
        )}
      </Pressable>
    );
  };

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'left', 'right']}>
      <View style={styles.header}>
        <ScreenHeader
          title="History"
          subtitle={`${items.length} shown`}
          right={
            <Pressable onPress={exportJson} style={styles.exportBtn}>
              <Text style={styles.exportText}>Export</Text>
            </Pressable>
          }
        />
        <TextInput
          style={styles.searchInput}
          value={search}
          onChangeText={setSearch}
          placeholder="Search sender or body…"
          placeholderTextColor={palette.textFaint}
          autoCapitalize="none"
        />
        <Segmented options={FILTERS} value={filter} onChange={setFilter} />
      </View>
      <FlatList
        data={items}
        keyExtractor={i => String(i.id)}
        renderItem={renderItem}
        contentContainerStyle={styles.list}
        ListEmptyComponent={<EmptyState title="No messages" subtitle="Inbound SMS will appear here." />}
      />
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: palette.bg },
  header: { paddingHorizontal: spacing.lg },
  searchInput: {
    backgroundColor: palette.surfaceAlt,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: palette.border,
    color: palette.text,
    paddingHorizontal: spacing.md,
    paddingVertical: 10,
    marginBottom: spacing.md,
  },
  exportBtn: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: 6,
  },
  exportText: { ...typography.label, color: palette.accent },
  list: { paddingHorizontal: spacing.lg, paddingBottom: spacing.xxl },
  row: {
    backgroundColor: palette.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: palette.border,
    padding: spacing.md,
    marginBottom: spacing.sm,
  },
  rowTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  sender: { ...typography.heading, color: palette.text },
  time: { ...typography.body, color: palette.textFaint, fontSize: 11 },
  body: { ...typography.body, color: palette.textMuted, marginVertical: spacing.sm },
  rowBottom: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  meta: { ...typography.body, color: palette.textFaint, fontSize: 11 },
  parsed: {
    marginTop: spacing.md,
    backgroundColor: palette.bg,
    borderRadius: radius.sm,
    padding: spacing.md,
    borderWidth: 1,
    borderColor: palette.border,
  },
  parsedLabel: { ...typography.label, color: palette.textFaint, marginBottom: 6 },
  parsedJson: { ...typography.mono, color: palette.accent },
});

export default SmsHistoryScreen;
