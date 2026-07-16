import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import React, { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Badge, Button, Card, Screen, ScreenHeader, SectionLabel, EmptyState } from '../components/ui';
import { SmsGateway } from '../native/SmsGateway';
import type { InboxMessage, TrainingResult } from '../native/types';
import type { RootStackParamList } from '../navigation/types';
import { useRuleDraftStore } from '../store/ruleDraft';
import { palette, radius, spacing, typography } from '../theme/theme';
import { truncate } from '../utils/format';

type Nav = NativeStackNavigationProp<RootStackParamList>;

const TrainingScreen: React.FC = () => {
  const nav = useNavigation<Nav>();
  const patch = useRuleDraftStore(s => s.patch);
  const [messages, setMessages] = useState<InboxMessage[]>([]);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [result, setResult] = useState<TrainingResult | null>(null);

  useEffect(() => {
    // Read the device's real SMS inbox (historical + pre-install), not just what the app captured.
    SmsGateway.getInboxMessages(300).then(setMessages).catch(() => {});
  }, []);

  const toggle = (id: number) => {
    setResult(null);
    setSelected(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  };

  const generate = async () => {
    const samples = messages.filter(m => selected.has(m.id)).map(m => m.body);
    if (samples.length === 0) return;
    setResult(await SmsGateway.trainTemplate(samples));
  };

  const apply = () => {
    if (!result) return;
    const sample = messages.find(m => selected.has(m.id));
    patch({
      processorType: 'template',
      template: result.template,
      regex: result.regex,
      sampleMessages: result.sampleMessages,
      senderPattern: sample?.sender ?? '',
      senderMatchType: sample?.sender ? 'contains' : 'any',
    });
    nav.goBack();
  };

  return (
    <Screen>
      <ScreenHeader title="Train from SMS" subtitle="Pick real inbox messages to generate a template" />

      {messages.length === 0 && (
        <EmptyState
          title="No inbox messages"
          subtitle="Grant SMS access, or this device's inbox is empty. Existing texts (even from before install) show here once readable."
        />
      )}

      {messages.map(m => {
        const active = selected.has(m.id);
        return (
          <Pressable key={m.id} style={[styles.row, active && styles.rowActive]} onPress={() => toggle(m.id)}>
            <View style={[styles.check, active && styles.checkActive]}>
              {active && <Text style={styles.checkMark}>✓</Text>}
            </View>
            <View style={{ flex: 1 }}>
              <Text style={styles.sender}>{m.sender}</Text>
              <Text style={styles.body}>{truncate(m.body, 100)}</Text>
            </View>
          </Pressable>
        );
      })}

      {selected.size > 0 && (
        <Button
          title={`Generate template from ${selected.size} sample${selected.size > 1 ? 's' : ''}`}
          onPress={generate}
          style={{ marginTop: spacing.md }}
        />
      )}

      {result && (
        <Card style={{ marginTop: spacing.md, borderColor: palette.accent }}>
          <SectionLabel>Generated template</SectionLabel>
          <Text style={styles.template}>{result.template}</Text>
          <SectionLabel>Detected placeholders</SectionLabel>
          <View style={styles.chips}>
            {result.placeholders.map((ph, i) => (
              <Badge key={`${ph}-${i}`} label={ph} color={palette.info} />
            ))}
          </View>
          <Button title="Use this template" onPress={apply} style={{ marginTop: spacing.md }} />
        </Card>
      )}
    </Screen>
  );
};

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: palette.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: palette.border,
    padding: spacing.md,
    marginBottom: spacing.sm,
    gap: spacing.md,
  },
  rowActive: { borderColor: palette.accent, backgroundColor: palette.accentSoft },
  check: {
    width: 22,
    height: 22,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: palette.borderStrong,
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkActive: { backgroundColor: palette.accent, borderColor: palette.accent },
  checkMark: { color: palette.bg, fontWeight: '700', fontSize: 13 },
  sender: { ...typography.heading, color: palette.text, fontSize: 14 },
  body: { ...typography.body, color: palette.textMuted, marginTop: 2, fontSize: 12 },
  template: { ...typography.mono, color: palette.accent, marginBottom: spacing.sm },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
});

export default TrainingScreen;
